package rtxremix.remix;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4fc;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rtxremix.RtxRemixMod;
import rtxremix.settings.RtxSettingsStore;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns the Remix side of a frame. All methods run on Minecraft's render thread unless noted.
 *
 * <p>Frame flow while Remix owns the window:
 * <ol>
 * <li>Minecraft's world pass is skipped; {@link #renderWorld} submits camera, terrain and lights
 *     through the Remix API instead.</li>
 * <li>Minecraft's Vulkan backend still draws the HUD and menus into its main render target, which
 *     {@link #captureUi} copies back to the CPU.</li>
 * <li>{@link #presentFrame} draws that layer as a UI quad on Remix's D3D9 device and presents;
 *     Remix path-traces the scene and composites the UI over it.</li>
 * </ol>
 */
public final class RemixRenderer {
	private static final Logger LOG = LoggerFactory.getLogger("rtxremix");
	/**
	 * Section uploads run on the render thread for up to this long per frame (and at least
	 * {@link #MIN_MESH_UPLOADS_PER_FRAME}), so a world loads quickly without long frames once it has.
	 */
	private static final long MESH_UPLOAD_BUDGET_NS = 3_000_000L;
	private static final int MIN_MESH_UPLOADS_PER_FRAME = 8;
	/**
	 * Sections are drawn merged into regions of 2^shift sections per axis (64x32x64 blocks): Remix pays per instance
	 * on the CPU every frame, and one instance per section layer runs to tens of thousands at long render distances.
	 */
	private static final int REGION_SHIFT_XZ = 2;
	private static final int REGION_SHIFT_Y = 1;
	/** Half the diagonal of a region, so distance culling by its center keeps every section the old test kept. */
	private static final double REGION_RADIUS = Math.sqrt(32 * 32 + 16 * 16 + 32 * 32);
	/** Regions rebuilt per frame: at least the minimum, then as many as fit in the budget. */
	private static final long REGION_REBUILD_BUDGET_NS = 4_000_000L;
	private static final int MIN_REGION_REBUILDS_PER_FRAME = 2;
	/** Alpha test reference of the cutout materials; their textures snap alpha to 0 or 255 at it. */
	private static final int CUTOUT_ALPHA_REF = 96;
	/** How long present waits for this frame's HUD copy before reusing the previous one. */
	private static final long UI_WAIT_NS = 50_000_000L;
	private static RemixRenderer instance;
	private static String status = "RTX Remix: not started";
	private static boolean failed;

	private final RemixApi api;
	private final D3D9Bridge d3d9;
	private final long hwnd;
	private boolean remixHotkeyDown;
	private final Path cacheDir;
	private final Map<Long, SectionEntry> sections = new HashMap<>();
	/** Keyed like sections, by region coordinates. */
	private final Map<Long, RegionMeshes> regions = new HashMap<>();
	/** Regions whose sections changed since their meshes were built, in the order they changed. */
	private final Set<Long> dirtyRegions = new LinkedHashSet<>();
	/**
	 * Section changes not yet handed to Remix: the latest compile of each section (empty once it is removed), and
	 * the order sections were first queued in. A section compiled again before its upload replaces the older
	 * result instead of queueing behind it, so a slow render thread cannot pile up compiles until memory runs out.
	 */
	private final ConcurrentHashMap<Long, Optional<SectionCapture.Section>> pendingSections = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Long> pendingOrder = new ConcurrentLinkedQueue<>();
	private final Map<ChunkSectionLayer, Long> materials = new EnumMap<>(ChunkSectionLayer.class);
	private List<Path> atlasFiles = List.of();
	private int atlasGeneration;
	private long meshSerial;
	private long sunLight;
	private long moonLight;
	private long skyLight;
	/** The parameters the sun, moon and sky lights were made with; they are only remade when these change. */
	private final float[] sunParams = new float[6];
	private final float[] moonParams = new float[6];
	private final float[] skyParams = new float[2];
	private final SkyDome skyDome = new SkyDome();
	private SkyDome.State skyState;
	private Path skyTexture;
	private Path previousSkyTexture;
	private int skyGen;
	/** The bake generation {@link #skyTexture} came from. */
	private int skyTextureGen;
	private long skyBakedAt;
	/** The bake in flight on a background thread: its state and the file it is writing, null when none. */
	private java.util.concurrent.CompletableFuture<Path> skyBake;
	private SkyDome.State skyBakeState;
	private int skyBakeGen;
	/** Bakes the sky at most this often, however fast it changes: each bake is a new texture for Remix to load. */
	private static final long SKY_BAKE_INTERVAL_NS = 500_000_000L;
	/** Sunlight color away from sunrise and sunset. */
	private static final float[] SUN_COLOR = {1f, 0.95f, 0.88f};
	private final CloudMesh clouds = new CloudMesh();
	private long cloudMesh;
	private long cloudMaterial;
	private int cloudCenterX;
	private int cloudCenterZ;
	private int cloudRadius;
	private boolean cloudFancy;
	private float cloudOpacity;
	/** Cloud range is capped so the mesh stays small enough to rebuild while flying. */
	private static final int MAX_CLOUD_CHUNKS = 48;
	/**
	 * Remix options the mod sets unless the player has overridden them. The runtime's local tonemapper defaults
	 * (shadows 2, highlights 4) lift shadows and pull down highlights, which flattens the contrast between lit
	 * and shaded faces; these keep more of it.
	 */
	private static final float MOON_TO_SUN = 0.01f;
	private static final Map<String, String> DEFAULT_OPTIONS = Map.of(
			// Biome tint lives in the vertex color; the runtime default (0.6) washes it out.
			"rtx.vertexColorStrength", "1.0",
			"rtx.localtonemap.shadows", "1.0",
			"rtx.localtonemap.highlights", "2.0",
			"rtx.localtonemap.boostLocalContrast", "True",
			// The global tonemapper keeps more contrast and color than the local one, which evens out lit and shaded
			// areas until everything sits at a similar, washed-out brightness. The localtonemap values above apply
			// if the player switches back to it (rtx.tonemappingMode = 1).
			"rtx.tonemappingMode", "0",
			// Auto exposure may otherwise brighten a dark scene by up to 2^5 over daylight, so night looks like day.
			// A floor of EV 0 still brightens the night, but it stays clearly darker than the day.
			"rtx.autoExposure.evMinValue", "0.0");
	private boolean remixMenuOpen;
	private GpuBuffer uiBuffer;
	private GpuFence uiFence;
	private int uiWidth;
	private int uiHeight;
	private ByteBuffer uiPixels;
	private boolean uiDirty;
	private int uiDebugFrames;
	private boolean worldSubmitted;
	/** Whether the underwater volumetrics are applied (see {@link #updateUnderwaterFog}). */
	private boolean underwater;
	/** Distance through water that leaves the transmittance color, in blocks. */
	private static final float UNDERWATER_FOG_BLOCKS = 32f;
	/** Index of refraction of the water and translucent block materials. */
	private static final float WATER_IOR = 1.33f;
	private Runnable deferredCamera;
	private final float[] transform = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
	/** Sprite sheets of the animated block sprites, in the order of {@link #animatedIndex}. */
	private List<AnimatedSheet> animatedSheets = List.of();
	private SectionCapture.AnimatedIndex animatedIndex;
	/** Keyed by sprite * 4 + layer ordinal. */
	private final Map<Integer, Long> animatedMaterials = new HashMap<>();
	/** Translucent material with a tiling wave normal map, which Remix animates for animated-water draws. */
	private long waterMaterial;
	/** Textures of the atlases items and particles are drawn with. */
	private final Map<Identifier, AtlasTextures> atlasTextures = new HashMap<>();
	private final Map<Identifier, List<Path>> dynamicAtlasFiles = new HashMap<>();
	private final Map<DynamicMaterialKey, Long> dynamicMaterials = new HashMap<>();
	/** Entity textures (mob textures, skins, capes) written for Remix, and those that could not be read. */
	private final Map<Identifier, Path> entityTextureFiles = new HashMap<>();
	private final Set<Identifier> unreadableEntityTextures = new HashSet<>();
	/** This frame's item, entity and particle meshes; they are rebuilt every frame. */
	private final List<Long> dynamicMeshes = new ArrayList<>();
	private long materialSerial;

	/**
	 * A section's latest compile, kept so its region can be rebuilt when a neighbor changes, and the handles of its
	 * lights, in {@code data.lights()} order.
	 */
	private record SectionEntry(SectionCapture.Section data, List<Long> lights, float lightScale) {
	}

	/** @param categories the Remix category flags each mesh is drawn with */
	private record RegionMeshes(long[] meshes, int[] categories) {
	}

	/** One merged region mesh: every part drawn with the same material and categories. */
	private record MeshGroup(long material, int categories) {
	}

	/** A section's layer and the offset of its section within the region. */
	private record Part(SectionCapture.Layer layer, float dx, float dy, float dz) {
	}

	/** @param cutoutAlbedo the albedo with alpha snapped for alpha testing */
	private record AnimatedSheet(Path albedo, Path cutoutAlbedo, Path normal, Path roughness, Path metallic, Path emissive,
			Path subsurface, RemixApi.SpriteSheet sheet) {
	}

	private record AtlasTextures(Path albedo, Path normal, Path roughness, Path metallic, Path emissive) {
	}

	private record DynamicMaterialKey(Identifier atlas, DynamicCapture.Kind kind) {
	}

	private RemixRenderer(RemixApi api, D3D9Bridge d3d9, long hwnd, Path cacheDir) {
		this.api = api;
		this.d3d9 = d3d9;
		this.hwnd = hwnd;
		this.cacheDir = cacheDir;
		// Atlas generations restart at 1 every launch, so files left by an earlier run would never be deleted.
		try (var old = Files.newDirectoryStream(cacheDir, "{blocks,items,particles,sky,entity}-*.dds")) {
			for (Path p : old) {
				Files.deleteIfExists(p);
			}
		} catch (IOException e) {
			LOG.warn("Could not clear old atlases from {}", cacheDir, e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Startup

	public static boolean active() {
		return instance != null;
	}

	public static RemixRenderer get() {
		return instance;
	}

	public static String statusLine() {
		return status;
	}

	/**
	 * Called before Minecraft acquires a swapchain image. On the first frame with the mod enabled this
	 * tears down Minecraft's swapchain (via {@code releaseSwapchain}) and starts Remix on the window.
	 */
	public static void ensureStarted(Runnable releaseSwapchain) {
		if (instance != null || failed) {
			return;
		}
		RtxSettingsStore.ModSettings mod = RtxRemixMod.settings().mod();
		if (!mod.enabled) {
			status = "RTX Remix: disabled in settings";
			failed = true;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Path gameDir = mc.gameDirectory.toPath();
		Path runtime = gameDir.resolve(mod.runtimePath).normalize();
		try {
			long hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(mc.getWindow().handle()),
					SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
			if (hwnd == 0) {
				throw new RemixException("could not get the window's HWND");
			}
			preferWindowProcInput();
			RemixApi api = RemixApi.load(runtime);
			releaseSwapchain.run();
			D3D9Bridge d3d9 = new D3D9Bridge(api, hwnd, mod.vsync);
			Path cache = gameDir.resolve("rtx-remix").resolve("cache");
			Files.createDirectories(cache);
			RemixRenderer r = new RemixRenderer(api, d3d9, hwnd, cache);
			instance = r;
			RtxRemixMod.settings().setSink((k, v) -> {
				int code = api.setConfigVariable(k, v);
				if (code != ErrorCodes.SUCCESS) {
					LOG.warn("Remix rejected {}={}: {}", k, v, ErrorCodes.describe(code));
				}
			});
			DEFAULT_OPTIONS.forEach((k, v) -> {
				if (!RtxRemixMod.settings().overrides().containsKey(k)) {
					api.setConfigVariable(k, v);
				}
			});
			RtxRemixMod.settings().applyAll();
			r.rebuildAtlasFromCurrent();
			status = "RTX Remix 1.6.1 active (" + runtime + ")";
			LOG.info("RTX Remix started from {}", runtime);
		} catch (Throwable t) {
			failed = true;
			status = "RTX Remix failed: " + t.getMessage();
			LOG.error("Could not start RTX Remix; falling back to vanilla rendering", t);
		}
	}

	/**
	 * Remix's default GUI input method registers raw keyboard input for its own overlay window, which
	 * takes keyboard messages away from Minecraft's window (the mouse still works, the keyboard does not).
	 * The older method subclasses the game window and passes input through while the Remix menu is closed.
	 * It is read only at startup and only from a .conf file, so it goes into rtx.conf in the working
	 * directory (where the runtime looks), unless the user already set it there.
	 */
	private static void preferWindowProcInput() {
		Path conf = Path.of("rtx.conf").toAbsolutePath();
		String key = "rtx.useNewGuiInputMethod";
		try {
			String text = Files.isRegularFile(conf) ? Files.readString(conf) : "";
			if (text.lines().anyMatch(l -> l.strip().startsWith(key))) {
				return;
			}
			String sep = text.isEmpty() || text.endsWith("\n") ? "" : System.lineSeparator();
			Files.writeString(conf, text + sep + key + " = False" + System.lineSeparator());
			LOG.info("Set {} = False in {} so Minecraft keeps keyboard input", key, conf);
		} catch (IOException e) {
			LOG.warn("Could not write {}; keyboard input may not reach Minecraft", conf, e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Scene input (any thread)

	/** Section changes not yet handed to Remix. */
	public static int pendingUploads() {
		RemixRenderer r = instance;
		return r != null ? r.pendingSections.size() + r.dirtyRegions.size() : 0;
	}

	/** Sets a Remix option for this session only, without saving it. */
	public static void setOption(String key, String value) {
		RemixRenderer r = instance;
		if (r != null) {
			int code = r.api.setConfigVariable(key, value);
			if (code != ErrorCodes.SUCCESS) {
				LOG.warn("Remix rejected {}={}: {}", key, value, ErrorCodes.describe(code));
			}
		}
	}

	public static void sectionCompiled(SectionCapture.Section section) {
		RemixRenderer r = instance;
		if (r != null) {
			r.queueSection(section.key(), Optional.of(section));
		}
	}

	public static void sectionRemoved(long key) {
		RemixRenderer r = instance;
		if (r != null) {
			r.queueSection(key, Optional.empty());
		}
	}

	private void queueSection(long key, Optional<SectionCapture.Section> section) {
		if (pendingSections.put(key, section) == null) {
			pendingOrder.add(key);
		}
	}

	/** Called on the render thread after the block, item or particle atlas is (re)stitched. */
	public static void atlasChanged(Identifier location, BlockAtlas atlas) {
		RemixRenderer r = instance;
		if (r != null) {
			r.applyAtlas(location, atlas);
		} else {
			AtlasSnapshot.store(location, atlas);
		}
	}

	private void rebuildAtlasFromCurrent() {
		AtlasSnapshot.take().forEach(this::applyAtlas);
	}

	private void applyAtlas(Identifier location, BlockAtlas atlas) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(location)) {
			// Resource packs reloaded: the sky and cloud textures may have changed too.
			skyDome.reloadTextures();
			skyState = null;
			clouds.reload();
			destroyClouds();
			clearEntityTextures();
			writeAtlas(atlas);
		} else {
			writeDynamicAtlas(location, atlas);
		}
	}

	private void writeAtlas(BlockAtlas atlas) {
		List<Path> old = atlasFiles;
		int gen = ++atlasGeneration;
		Path file = cacheDir.resolve("blocks-" + gen + ".dds");
		Path cutoutFile = cacheDir.resolve("blocks-" + gen + "-cutout.dds");
		Path solidFile = cacheDir.resolve("blocks-" + gen + "-solid.dds");
		Path normal = null;
		Path roughness = null;
		Path metallic = null;
		Path emissive = null;
		Path height = null;
		Path subsurface = null;
		Path waterNormal = cacheDir.resolve("blocks-" + gen + "-water-normal.dds");
		RtxSettingsStore.ModSettings settings = RtxRemixMod.settings().mod();
		List<Path> files = new ArrayList<>(List.of(file, cutoutFile, solidFile, waterNormal));
		try {
			DdsWriter.write(file, atlas.albedo(), atlas.width(), atlas.height(), true, 5);
			DdsWriter.writeCutout(cutoutFile, atlas.albedo(), atlas.width(), atlas.height(), 5, CUTOUT_ALPHA_REF);
			// Solid meshes also hold the overlay pieces of split grass sides, whose lower mips have partial alpha.
			DdsWriter.writeOpaque(solidFile, atlas.albedo(), atlas.width(), atlas.height(), 5);
			// Every mip: Remix blurs the second water sample with a LoD bias.
			DdsWriter.write(waterNormal, WaterNormals.generate(), WaterNormals.SIZE, WaterNormals.SIZE, false, 9);
			BlockAtlas.Pbr pbr = atlas.pbr();
			if (pbr != null) {
				normal = writeLinear("blocks-" + gen + "-normal.dds", pbr.normal(), 2, atlas, files);
				roughness = writeLinear("blocks-" + gen + "-roughness.dds", pbr.roughness(), 1, atlas, files);
				metallic = writeLinear("blocks-" + gen + "-metallic.dds", pbr.metallic(), 1, atlas, files);
				emissive = writeColor("blocks-" + gen + "-emissive.dds", pbr.emissive(), atlas, files);
				if (pbr.height() != null && settings.pomDepth > 0f) {
					// Quadtree POM walks the mip chain as a max-height quadtree, so it gets every level.
					height = cacheDir.resolve("blocks-" + gen + "-height.dds");
					DdsWriter.writeLinear(height, pbr.height(), atlas.width(), atlas.height(), 1, 32, true);
					files.add(height);
				}
				if (pbr.subsurface() != null && settings.subsurfaceScattering) {
					subsurface = writeColor("blocks-" + gen + "-subsurface.dds", pbr.subsurface(), atlas, files);
				}
			}
		} catch (IOException e) {
			LOG.error("Could not write block atlas for Remix", e);
			return;
		}
		// Every mesh references the old materials: drop them all; Minecraft recompiles the sections.
		clearSections();
		materials.values().forEach(api::destroyMaterial);
		materials.clear();
		if (waterMaterial != 0) {
			api.destroyMaterial(waterMaterial);
		}
		atlasFiles = files;
		long base = 0x6d63626c6f636bL + atlasGeneration * 16L;
		float emissiveIntensity = emissive != null ? settings.emissiveIntensity : 0f;
		// displaceIn is relative to the texture's UV width, and one block spans one sprite of the atlas.
		float displaceIn = settings.pomDepth * atlas.spriteUv();
		// Thin enough for leaves and plants; the texture's transmittance color is what lets light through. Only the
		// cutout layer gets it: Remix flags the whole material as subsurface, and solid blocks are not see-through.
		RemixApi.Subsurface sss = subsurface != null ? new RemixApi.Subsurface(subsurface, file, 0.1f) : null;
		materials.put(ChunkSectionLayer.SOLID, api.createOpaqueMaterial(new RemixApi.OpaqueMaterial(
				base + 1, solidFile, normal, roughness, metallic, emissive, emissiveIntensity, height, displaceIn, null,
				false, 0, true)));
		materials.put(ChunkSectionLayer.CUTOUT, api.createOpaqueMaterial(new RemixApi.OpaqueMaterial(
				base + 2, cutoutFile, normal, roughness, metallic, emissive, emissiveIntensity, height, displaceIn, sss,
				true, CUTOUT_ALPHA_REF, true)));
		materials.put(ChunkSectionLayer.TRANSLUCENT, api.createTranslucentMaterial(new RemixApi.TranslucentMaterial(
				base + 3, file, normal, WATER_IOR, 0.92f, 0.96f, 1.0f, 4f, true)));
		waterMaterial = api.createTranslucentMaterial(new RemixApi.TranslucentMaterial(
				base + 4, null, waterNormal, WATER_IOR, 0.92f, 0.96f, 1.0f, 4f, false));
		animatedMaterials.values().forEach(api::destroyMaterial);
		animatedMaterials.clear();
		animatedSheets = writeAnimatedSheets(atlas, gen, files);
		animatedIndex = SectionCapture.setAnimated(atlas.animated(), atlas.water());
		SectionCapture.setAtlasAlpha(SectionCapture.AtlasAlpha.of(atlas, CUTOUT_ALPHA_REF));
		setAtlasTextures(TextureAtlas.LOCATION_BLOCKS, new AtlasTextures(file, normal, roughness, metallic, emissive));
		for (Path p : old) {
			try {
				Files.deleteIfExists(p);
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * Writes each animated sprite as a sprite sheet. Its LabPBR maps do not animate: their first frame is repeated
	 * in every cell, so the maps line up with the albedo whichever cell Remix shows.
	 */
	private List<AnimatedSheet> writeAnimatedSheets(BlockAtlas atlas, int gen, List<Path> files) {
		List<AnimatedSheet> out = new ArrayList<>();
		BlockAtlas.Pbr pbr = atlas.pbr();
		RtxSettingsStore.ModSettings settings = RtxRemixMod.settings().mod();
		try {
			for (int i = 0; i < atlas.animated().size(); i++) {
				BlockAtlas.Animated a = atlas.animated().get(i);
				int w = a.cols() * a.w(), h = a.rows() * a.h();
				String name = "blocks-" + gen + "-anim" + i;
				Path albedo = writeSheet(name + ".dds", a.sheet(), w, h, 4, files);
				Path cutoutAlbedo = cacheDir.resolve(name + "-cutout.dds");
				DdsWriter.writeCutout(cutoutAlbedo, a.sheet(), w, h, 3, CUTOUT_ALPHA_REF);
				files.add(cutoutAlbedo);
				Path normal = null, roughness = null, metallic = null, emissive = null, subsurface = null;
				if (pbr != null) {
					normal = writeSheet(name + "-normal.dds", a.tile(pbr.normal(), atlas.width()), w, h, 2, files);
					roughness = writeSheet(name + "-roughness.dds", a.tile(pbr.roughness(), atlas.width()), w, h, 1, files);
					metallic = writeSheet(name + "-metallic.dds", a.tile(pbr.metallic(), atlas.width()), w, h, 1, files);
					emissive = writeSheet(name + "-emissive.dds", a.tile(pbr.emissive(), atlas.width()), w, h, 4, files);
					if (pbr.subsurface() != null && settings.subsurfaceScattering) {
						subsurface = writeSheet(name + "-subsurface.dds", a.tile(pbr.subsurface(), atlas.width()), w, h,
								4, files);
					}
				}
				out.add(new AnimatedSheet(albedo, cutoutAlbedo, normal, roughness, metallic, emissive, subsurface,
						new RemixApi.SpriteSheet(a.cols(), a.rows(), a.fps())));
			}
		} catch (IOException e) {
			LOG.error("Could not write animated block textures for Remix; they will not be drawn", e);
			return List.of();
		}
		LOG.info("Wrote {} animated block textures as sprite sheets", out.size());
		return out;
	}

	/** @param channels 4 for sRGB color; 1 or 2 for linear data, as for {@link #writeLinear} */
	private Path writeSheet(String name, byte[] rgba, int w, int h, int channels, List<Path> files) throws IOException {
		Path p = cacheDir.resolve(name);
		// Few mips: lower levels would blend neighbouring cells together.
		if (channels == 4) {
			DdsWriter.write(p, rgba, w, h, true, 3);
		} else {
			DdsWriter.writeLinear(p, rgba, w, h, channels, 3, false);
		}
		files.add(p);
		return p;
	}

	/** Material for one animated sprite in one layer, made the first time a section uses it; 0 if unavailable. */
	private long animatedMaterial(int sprite, ChunkSectionLayer layer) {
		if (sprite >= animatedSheets.size()) {
			return 0;
		}
		return animatedMaterials.computeIfAbsent(sprite * 4 + layer.ordinal(), k -> {
			AnimatedSheet a = animatedSheets.get(sprite);
			RtxSettingsStore.ModSettings settings = RtxRemixMod.settings().mod();
			long hash = 0x616e696d6d6174L + (++materialSerial);
			if (layer == ChunkSectionLayer.TRANSLUCENT) {
				return api.createTranslucentMaterial(new RemixApi.TranslucentMaterial(hash, a.albedo(), a.normal(), 1.33f,
						0.92f, 0.96f, 1.0f, 4f, true, a.sheet()));
			}
			RemixApi.Subsurface sss = a.subsurface() != null && layer == ChunkSectionLayer.CUTOUT ? new RemixApi.Subsurface(a.subsurface(), a.albedo(), 0.1f) : null;
			boolean cutout = layer == ChunkSectionLayer.CUTOUT;
			return api.createOpaqueMaterial(new RemixApi.OpaqueMaterial(hash, cutout ? a.cutoutAlbedo() : a.albedo(),
					a.normal(), a.roughness(), a.metallic(), a.emissive(),
					a.emissive() != null ? settings.emissiveIntensity : 0f, null, 0f, sss, cutout, CUTOUT_ALPHA_REF, true,
					false, a.sheet()));
		});
	}

	/** Writes the item or particle atlas (with any LabPBR maps) for the items and particles drawn from it. */
	private void writeDynamicAtlas(Identifier location, BlockAtlas atlas) {
		int gen = ++atlasGeneration;
		String name = location.getPath().replace("textures/atlas/", "").replace(".png", "") + "-" + gen;
		List<Path> files = new ArrayList<>();
		try {
			Path albedo = writeColor(name + ".dds", atlas.albedo(), atlas, files);
			Path normal = null, roughness = null, metallic = null, emissive = null;
			BlockAtlas.Pbr pbr = atlas.pbr();
			if (pbr != null) {
				normal = writeLinear(name + "-normal.dds", pbr.normal(), 2, atlas, files);
				roughness = writeLinear(name + "-roughness.dds", pbr.roughness(), 1, atlas, files);
				metallic = writeLinear(name + "-metallic.dds", pbr.metallic(), 1, atlas, files);
				emissive = writeColor(name + "-emissive.dds", pbr.emissive(), atlas, files);
			}
			setAtlasTextures(location, new AtlasTextures(albedo, normal, roughness, metallic, emissive));
		} catch (IOException e) {
			LOG.error("Could not write {} for Remix", location, e);
			return;
		}
		List<Path> old = dynamicAtlasFiles.put(location, files);
		if (old != null) {
			for (Path p : old) {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			}
		}
	}

	/** Swaps an atlas's textures; materials made from the old ones go, along with the meshes that use them. */
	private void setAtlasTextures(Identifier location, AtlasTextures textures) {
		dynamicMeshes.forEach(api::destroyMesh);
		dynamicMeshes.clear();
		dynamicMaterials.entrySet().removeIf(e -> {
			if (e.getKey().atlas().equals(location)) {
				api.destroyMaterial(e.getValue());
				return true;
			}
			return false;
		});
		atlasTextures.put(location, textures);
	}

	/**
	 * Makes an entity texture available as {@link #atlasTextures} on first use. Downloaded skins and capes are
	 * {@link DynamicTexture}s that keep their pixels; everything else is read from the resource packs. Minecraft's
	 * entity textures are not created for copying, so they cannot be read back from the GPU.
	 */
	private boolean loadEntityTexture(Identifier id) {
		if (atlasTextures.containsKey(id)) {
			return true;
		}
		if (unreadableEntityTextures.contains(id)) {
			return false;
		}
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(id);
		byte[] rgba = null;
		int w = 0, h = 0;
		try {
			if (texture instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
				NativeImage img = dynamic.getPixels();
				rgba = abgrToRgba(img.getPixelsABGR());
				w = img.getWidth();
				h = img.getHeight();
			} else if (!(texture instanceof TextureAtlas)) {
				try (InputStream in = Minecraft.getInstance().getResourceManager().open(id); NativeImage img = NativeImage.read(in)) {
					rgba = abgrToRgba(img.getPixelsABGR());
					w = img.getWidth();
					h = img.getHeight();
				}
			}
			if (rgba == null) {
				unreadableEntityTextures.add(id);
				return false;
			}
			Path p = cacheDir.resolve("entity-" + (++atlasGeneration) + ".dds");
			DdsWriter.write(p, rgba, w, h, true, 5);
			entityTextureFiles.put(id, p);
			atlasTextures.put(id, new AtlasTextures(p, null, null, null, null));
			return true;
		} catch (IOException | RuntimeException e) {
			LOG.warn("Could not read entity texture {} for Remix", id, e);
			unreadableEntityTextures.add(id);
			return false;
		}
	}

	/** NativeImage packs pixels as ABGR ints, which are R, G, B, A in memory order. */
	private static byte[] abgrToRgba(int[] abgr) {
		byte[] out = new byte[abgr.length * 4];
		for (int i = 0; i < abgr.length; i++) {
			int c = abgr[i];
			out[i * 4] = (byte) c;
			out[i * 4 + 1] = (byte) (c >> 8);
			out[i * 4 + 2] = (byte) (c >> 16);
			out[i * 4 + 3] = (byte) (c >> 24);
		}
		return out;
	}

	/** Resource packs reloaded: entity textures are read again on their next use. */
	private void clearEntityTextures() {
		for (Map.Entry<Identifier, Path> e : entityTextureFiles.entrySet()) {
			setAtlasTextures(e.getKey(), null);
			atlasTextures.remove(e.getKey());
			try {
				Files.deleteIfExists(e.getValue());
			} catch (IOException ignored) {
			}
		}
		entityTextureFiles.clear();
		unreadableEntityTextures.clear();
	}

	private long dynamicMaterial(Identifier atlas, DynamicCapture.Kind kind) {
		AtlasTextures t = atlasTextures.get(atlas);
		if (t == null) {
			return 0;
		}
		return dynamicMaterials.computeIfAbsent(new DynamicMaterialKey(atlas, kind), k -> {
			float intensity = RtxRemixMod.settings().mod().emissiveIntensity;
			// Particles at full block light (flames, lava drips) glow with their own color.
			Path emissive = kind == DynamicCapture.Kind.GLOW ? t.albedo() : t.emissive();
			return api.createOpaqueMaterial(new RemixApi.OpaqueMaterial(0x64796e6d6174L + (++materialSerial), t.albedo(),
					t.normal(), t.roughness(), t.metallic(), emissive, emissive != null ? intensity : 0f, null, 0f, null,
					kind == DynamicCapture.Kind.CUTOUT, 96, true, kind != DynamicCapture.Kind.CUTOUT, null));
		});
	}

	/** @param channels 1 for roughness, metallic and height; 2 for octahedral normals */
	private Path writeLinear(String name, byte[] rgba, int channels, BlockAtlas atlas, List<Path> files) throws IOException {
		Path p = cacheDir.resolve(name);
		DdsWriter.writeLinear(p, rgba, atlas.width(), atlas.height(), channels, 5, false);
		files.add(p);
		return p;
	}

	private Path writeColor(String name, byte[] rgba, BlockAtlas atlas, List<Path> files) throws IOException {
		Path p = cacheDir.resolve(name);
		DdsWriter.write(p, rgba, atlas.width(), atlas.height(), true, 5);
		files.add(p);
		return p;
	}

	/** Stores a section's compile (or its removal, for null) and marks its region for a rebuild. */
	private void replaceSection(long key, SectionCapture.Section data) {
		SectionEntry old = sections.remove(key);
		float lightScale = 40f * RtxRemixMod.settings().mod().blockLightIntensity;
		// Editing a block recompiles its section; lights that did not change keep their handle rather than being
		// remade, which would cost Remix their sampling history.
		Map<SectionCapture.Light, Long> unchanged = new HashMap<>();
		if (old != null) {
			List<SectionCapture.Light> oldLights = old.data().lights();
			for (int i = 0; i < old.lights().size(); i++) {
				if (old.lightScale() == lightScale) {
					unchanged.put(oldLights.get(i), old.lights().get(i));
				} else {
					api.destroyLight(old.lights().get(i));
				}
			}
			dirtyRegions.add(regionOf(key));
		}
		if (data == null || data.isEmpty() || materials.isEmpty()) {
			unchanged.values().forEach(api::destroyLight);
			return;
		}
		List<Long> lights = new ArrayList<>();
		for (SectionCapture.Light l : data.lights()) {
			Long kept = unchanged.remove(l);
			lights.add(kept != null ? kept : api.createSphereLight(l.hash(), l.r() * lightScale, l.g() * lightScale,
					l.b() * lightScale, l.x(), l.y(), l.z(), l.radius()));
		}
		unchanged.values().forEach(api::destroyLight);
		sections.put(key, new SectionEntry(data, lights, lightScale));
		dirtyRegions.add(regionOf(key));
	}

	/** Releases every section's lights and every region's meshes now, since their materials are about to go. */
	private void clearSections() {
		for (Long key : List.copyOf(sections.keySet())) {
			replaceSection(key, null);
		}
		for (RegionMeshes r : regions.values()) {
			for (long mesh : r.meshes()) {
				api.destroyMesh(mesh);
			}
		}
		regions.clear();
		dirtyRegions.clear();
	}

	private static long regionOf(long section) {
		return SectionPos.asLong(SectionPos.x(section) >> REGION_SHIFT_XZ, SectionPos.y(section) >> REGION_SHIFT_Y,
				SectionPos.z(section) >> REGION_SHIFT_XZ);
	}

	/** Merges the region's sections into one mesh per material and draw category. */
	private void rebuildRegion(long region) {
		RegionMeshes old = regions.remove(region);
		if (old != null) {
			for (long mesh : old.meshes()) {
				api.destroyMesh(mesh);
			}
		}
		int rx = SectionPos.x(region) << REGION_SHIFT_XZ;
		int ry = SectionPos.y(region) << REGION_SHIFT_Y;
		int rz = SectionPos.z(region) << REGION_SHIFT_XZ;
		Map<MeshGroup, List<Part>> groups = new LinkedHashMap<>();
		for (int x = 0; x < 1 << REGION_SHIFT_XZ; x++) {
			for (int y = 0; y < 1 << REGION_SHIFT_Y; y++) {
				for (int z = 0; z < 1 << REGION_SHIFT_XZ; z++) {
					SectionEntry entry = sections.get(SectionPos.asLong(rx + x, ry + y, rz + z));
					if (entry == null) {
						continue;
					}
					SectionCapture.Section data = entry.data();
					float dx = x << 4, dy = y << 4, dz = z << 4;
					for (Map.Entry<ChunkSectionLayer, SectionCapture.Layer> e : data.layers().entrySet()) {
						addPart(groups, materials.get(e.getKey()), 0, new Part(e.getValue(), dx, dy, dz));
					}
					// Sprite numbers refer to the atlas the section was compiled against; after a reload it is recompiled.
					if (data.index() == animatedIndex) {
						for (SectionCapture.AnimatedLayer a : data.animated()) {
							addPart(groups, animatedMaterial(a.sprite(), a.layer()), 0, new Part(a.data(), dx, dy, dz));
						}
						if (data.water() != null) {
							addPart(groups, waterMaterial, RemixApi.CATEGORY_ANIMATED_WATER,
									new Part(data.water(), dx, dy, dz));
						}
					}
					if (data.overlay() != null) {
						addPart(groups, materials.get(ChunkSectionLayer.CUTOUT), RemixApi.CATEGORY_DECAL_STATIC,
								new Part(data.overlay(), dx, dy, dz));
					}
				}
			}
		}
		if (groups.isEmpty()) {
			return;
		}
		long[] meshes = new long[groups.size()];
		int[] categories = new int[groups.size()];
		int i = 0;
		for (Map.Entry<MeshGroup, List<Part>> e : groups.entrySet()) {
			meshes[i] = createMesh(e.getValue(), e.getKey().material());
			categories[i++] = e.getKey().categories();
		}
		regions.put(region, new RegionMeshes(meshes, categories));
	}

	private static void addPart(Map<MeshGroup, List<Part>> groups, Long material, int categories, Part part) {
		if (material != null && material != 0 && part.layer().vertexCount() > 0) {
			groups.computeIfAbsent(new MeshGroup(material, categories), k -> new ArrayList<>()).add(part);
		}
	}

	/** One mesh of all the parts, each moved by its section's offset within the region. */
	private long createMesh(List<Part> parts, long material) {
		int vertexCount = 0;
		int indexCount = 0;
		for (Part p : parts) {
			vertexCount += p.layer().vertexCount();
			indexCount += p.layer().indices().length;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment verts = arena.allocate((long) vertexCount * RemixApi.VERTEX_STRIDE, 16);
			MemorySegment idx = arena.allocate((long) indexCount * Integer.BYTES, Integer.BYTES);
			long vertexOffset = 0;
			long indexPos = 0;
			int base = 0;
			for (Part p : parts) {
				SectionCapture.Layer layer = p.layer();
				long bytes = (long) layer.vertexCount() * RemixApi.VERTEX_STRIDE;
				MemorySegment.copy(layer.vertices(), 0, verts, ValueLayout.JAVA_BYTE, vertexOffset, (int) bytes);
				if (p.dx() != 0 || p.dy() != 0 || p.dz() != 0) {
					for (long o = vertexOffset; o < vertexOffset + bytes; o += RemixApi.VERTEX_STRIDE) {
						verts.set(ValueLayout.JAVA_FLOAT, o, verts.get(ValueLayout.JAVA_FLOAT, o) + p.dx());
						verts.set(ValueLayout.JAVA_FLOAT, o + 4, verts.get(ValueLayout.JAVA_FLOAT, o + 4) + p.dy());
						verts.set(ValueLayout.JAVA_FLOAT, o + 8, verts.get(ValueLayout.JAVA_FLOAT, o + 8) + p.dz());
					}
				}
				for (int index : layer.indices()) {
					idx.setAtIndex(ValueLayout.JAVA_INT, indexPos++, index + base);
				}
				vertexOffset += bytes;
				base += layer.vertexCount();
			}
			// Unique per creation: Remix destroys meshes lazily, so reusing a hash on a rebuild could clash.
			long hash = 0x7274786d65736800L + (++meshSerial);
			return api.createMesh(hash, verts, vertexCount, idx, indexCount, material);
		}
	}

	private long createMesh(SectionCapture.Layer layer, long material) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment verts = arena.allocate(layer.vertices().length, 16);
			MemorySegment.copy(layer.vertices(), 0, verts, ValueLayout.JAVA_BYTE, 0, layer.vertices().length);
			MemorySegment idx = arena.allocateFrom(ValueLayout.JAVA_INT, layer.indices());
			long hash = 0x7274786d65736800L + (++meshSerial);
			return api.createMesh(hash, verts, layer.vertexCount(), idx, layer.indices().length, material);
		}
	}

	/** Items, entities and particles: Minecraft rebuilds them every frame, so their meshes only live for one frame. */
	private void drawDynamic(Vec3 camera) {
		dynamicMeshes.forEach(api::destroyMesh);
		dynamicMeshes.clear();
		// Minecraft builds them relative to the camera.
		float[] t = {1, 0, 0, (float) camera.x, 0, 1, 0, (float) camera.y, 0, 0, 1, (float) camera.z};
		for (Map.Entry<DynamicCapture.Key, MeshBuilder> e : DynamicCapture.take().entrySet()) {
			DynamicCapture.Key key = e.getKey();
			if (key.entity() && !loadEntityTexture(key.atlas())) {
				continue;
			}
			long material = dynamicMaterial(key.atlas(), key.kind());
			if (material == 0 || e.getValue().isEmpty()) {
				continue;
			}
			long mesh = createMesh(e.getValue().build(), material);
			dynamicMeshes.add(mesh);
			api.drawInstance(mesh, t, key.particle() ? RemixApi.CATEGORY_PARTICLE : 0, true, true);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Frame

	/** Replaces Minecraft's world pass. */
	public void renderWorld(CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !camera.initialized || D3D9Bridge.exp == 6) {
			return;
		}
		long uploadDeadline = System.nanoTime() + MESH_UPLOAD_BUDGET_NS;
		for (int i = 0; i < MIN_MESH_UPLOADS_PER_FRAME || System.nanoTime() < uploadDeadline; i++) {
			Long key = pendingOrder.poll();
			if (key == null) {
				break;
			}
			// Removed after polling, so a compile that lands in between is either taken now or queued anew.
			Optional<SectionCapture.Section> section = pendingSections.remove(key);
			if (section != null) {
				replaceSection(key, section.orElse(null));
			}
		}
		long rebuildDeadline = System.nanoTime() + REGION_REBUILD_BUDGET_NS;
		Iterator<Long> dirty = dirtyRegions.iterator();
		for (int i = 0; dirty.hasNext() && (i < MIN_REGION_REBUILDS_PER_FRAME || System.nanoTime() < rebuildDeadline); i++) {
			long region = dirty.next();
			dirty.remove();
			rebuildRegion(region);
		}

		Vec3 pos = camera.pos;
		Vector3f forward = new Vector3f(0, 0, -1).rotate(camera.orientation);
		Vector3f up = new Vector3f(0, 1, 0).rotate(camera.orientation);
		Vector3f right = new Vector3f(1, 0, 0).rotate(camera.orientation);
		Matrix4f proj = camera.projectionMatrix;
		float fovY = (float) Math.toDegrees(2.0 * Math.atan(1.0 / proj.m11()));
		float aspect = proj.m11() / proj.m00();
		Runnable cam = () -> api.setupCamera((float) pos.x, (float) pos.y, (float) pos.z,
				new float[] {forward.x, forward.y, forward.z}, new float[] {up.x, up.y, up.z},
				new float[] {right.x, right.y, right.z}, fovY, aspect, 0.05f, Math.max(512f, camera.depthFar));
		if (D3D9Bridge.exp == 5) {
			deferredCamera = cam;
		} else {
			cam.run();
		}

		double maxDist = (mc.options.getEffectiveRenderDistance() + 1) * 16.0;
		double maxDistSq = maxDist * maxDist;
		List<SectionCapture.Light> nearLights = new ArrayList<>();
		List<Long> nearLightHandles = new ArrayList<>();
		double regionDistSq = (maxDist + REGION_RADIUS) * (maxDist + REGION_RADIUS);
		for (Map.Entry<Long, RegionMeshes> e : regions.entrySet()) {
			long key = e.getKey();
			int rx = SectionPos.x(key) << (4 + REGION_SHIFT_XZ);
			int ry = SectionPos.y(key) << (4 + REGION_SHIFT_Y);
			int rz = SectionPos.z(key) << (4 + REGION_SHIFT_XZ);
			double dx = rx + (8 << REGION_SHIFT_XZ) - pos.x;
			double dy = ry + (8 << REGION_SHIFT_Y) - pos.y;
			double dz = rz + (8 << REGION_SHIFT_XZ) - pos.z;
			if (dx * dx + dy * dy + dz * dz > regionDistSq) {
				continue;
			}
			transform[3] = rx;
			transform[7] = ry;
			transform[11] = rz;
			long[] meshes = e.getValue().meshes();
			int[] categories = e.getValue().categories();
			for (int i = 0; i < meshes.length; i++) {
				api.drawInstance(meshes[i], transform, categories[i], true);
			}
		}
		for (Map.Entry<Long, SectionEntry> e : sections.entrySet()) {
			long key = e.getKey();
			double dx = (SectionPos.x(key) << 4) + 8 - pos.x;
			double dy = (SectionPos.y(key) << 4) + 8 - pos.y;
			double dz = (SectionPos.z(key) << 4) + 8 - pos.z;
			if (dx * dx + dy * dy + dz * dz > maxDistSq) {
				continue;
			}
			List<SectionCapture.Light> lights = e.getValue().data().lights();
			for (int i = 0; i < lights.size(); i++) {
				nearLights.add(lights.get(i));
				nearLightHandles.add(e.getValue().lights().get(i));
			}
		}
		drawNearestLights(pos, nearLights, nearLightHandles);
		drawDynamic(pos);
		drawSkyLights(mc, level, camera);
		drawClouds(mc, level, camera);
		updateUnderwaterFog(mc.gameRenderer.mainCamera().getFluidInCamera() == FogType.WATER);
		worldSubmitted = true;
	}

	/**
	 * Remix has no notion of the camera being inside a medium: from below, the water surface refracts straight into
	 * the full-brightness sky while the dimmer underwater scene drives auto exposure up, so the sky saturates to flat
	 * blue. While the camera is in water the volumetrics stand in for the water itself, absorbing red first and
	 * scattering a blue-green glow, the way Minecraft's water fog does. The player's own values come back on leaving.
	 */
	private void updateUnderwaterFog(boolean underwater) {
		if (underwater == this.underwater) {
			return;
		}
		this.underwater = underwater;
		// Volumetric distances are in meters of rendering units (1 cm), scaled by rtx.sceneScale per game unit.
		float metersPerBlock = 0.01f * Float.parseFloat(userOption("rtx.sceneScale", "1"));
		// Seen from below, water's index of refraction squeezes the world above into a narrow cone of sky and mirrors
		// the rest. Minecraft shows the surface as a plain window, so translucent surfaces stop refracting meanwhile.
		float iorScale = Float.parseFloat(userOption("rtx.translucentMaterial.refractiveIndexScale", "1")) / WATER_IOR;
		Map<String, String> water = Map.of(
				"rtx.translucentMaterial.refractiveIndexScale", Float.toString(iorScale),
				"rtx.volumetrics.transmittanceColor", "0.25, 0.55, 0.7",
				"rtx.volumetrics.transmittanceMeasurementDistanceMeters", Float.toString(UNDERWATER_FOG_BLOCKS * metersPerBlock),
				"rtx.volumetrics.singleScatteringAlbedo", "0.3, 0.7, 0.85");
		water.forEach((k, v) -> setOption(k, underwater ? v : userOption(k, null)));
	}

	/** The player's override of a Remix option, else its runtime default, else {@code fallback}. */
	private static String userOption(String key, String fallback) {
		String v = RtxRemixMod.settings().overrides().get(key);
		if (v == null) {
			var option = RtxRemixMod.catalog().get(key);
			v = option != null ? option.defaultText() : fallback;
		}
		return v;
	}

	private void drawNearestLights(Vec3 pos, List<SectionCapture.Light> lights, List<Long> handles) {
		int max = RtxRemixMod.settings().mod().maxBlockLights;
		if (lights.size() <= max) {
			handles.forEach(api::drawLightInstance);
			return;
		}
		Integer[] order = new Integer[lights.size()];
		double[] dist = new double[lights.size()];
		for (int i = 0; i < order.length; i++) {
			SectionCapture.Light l = lights.get(i);
			order[i] = i;
			dist[i] = pos.distanceToSqr(l.x(), l.y(), l.z());
		}
		java.util.Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));
		for (int i = 0; i < max; i++) {
			api.drawLightInstance(handles.get(order[i]));
		}
	}

	private void drawSkyLights(Minecraft mc, ClientLevel level, CameraRenderState camera) {
		RtxSettingsStore.ModSettings mod = RtxRemixMod.settings().mod();
		if (level.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD) {
			destroySkyLights();
			return;
		}
		Camera cam = mc.gameRenderer.mainCamera();
		float pt = camera.cameraEntityPartialTicks;
		var probe = cam.attributeProbe();
		float sunAngle = (float) Math.toRadians(probe.getValue(EnvironmentAttributes.SUN_ANGLE, pt));
		float moonAngle = (float) Math.toRadians(probe.getValue(EnvironmentAttributes.MOON_ANGLE, pt));
		Vector4fc sunrise = probe.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, pt);
		float rain = level.getRainLevel(pt);
		float clear = 1f - rain * 0.8f;

		// Sunlight takes on the sunrise/sunset color while the sun is low.
		float tilt = (float) Math.toRadians(mod.sunPathRotation);
		Vector3f sunDir = SkyDome.celestialDir(sunAngle, tilt);
		if (sunDir.y > -0.1f) {
			float k = mod.sunIntensity * clear * smooth(sunDir.y, -0.1f, 0.15f);
			float r = SkyDome.srgbToLinear(sunrise.x()), g = SkyDome.srgbToLinear(sunrise.y()), b = SkyDome.srgbToLinear(sunrise.z());
			float max = Math.max(r, Math.max(g, b));
			float w = max > 0 ? 0.85f * Math.clamp(sunrise.w(), 0f, 1f) : 0f;
			if (max > 0) {
				r /= max;
				g /= max;
				b /= max;
			}
			float[] p = {k * Mth.lerp(w, SUN_COLOR[0], r), k * Mth.lerp(w, SUN_COLOR[1], g), k * Mth.lerp(w, SUN_COLOR[2], b),
					-sunDir.x, -sunDir.y, -sunDir.z};
			sunLight = remakeIfChanged(sunLight, sunParams, p,
					() -> api.createDistantLight(0x73756eL, p[0], p[1], p[2], p[3], p[4], p[5], 0.53f));
			api.drawLightInstance(sunLight);
		} else {
			api.destroyLight(sunLight);
			sunLight = 0;
		}
		Vector3f moonDir = SkyDome.celestialDir(moonAngle, tilt);
		if (moonDir.y > 0f) {
			// Moonlight at about 1/100 of sunlight: dim enough that night reads as night after auto exposure.
			float k = MOON_TO_SUN * mod.sunIntensity * clear * smooth(moonDir.y, 0f, 0.2f);
			float[] p = {k * 0.8f, k * 0.9f, k, -moonDir.x, -moonDir.y, -moonDir.z};
			moonLight = remakeIfChanged(moonLight, moonParams, p,
					() -> api.createDistantLight(0x6d6f6f6eL, p[0], p[1], p[2], p[3], p[4], p[5], 0.5f));
			api.drawLightInstance(moonLight);
		} else {
			api.destroyLight(moonLight);
			moonLight = 0;
		}

		SkyDome.State state = new SkyDome.State(tilt, sunAngle, moonAngle,
				(float) Math.toRadians(probe.getValue(EnvironmentAttributes.STAR_ANGLE, pt)),
				probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, pt),
				probe.getValue(EnvironmentAttributes.MOON_PHASE, pt), 1f - rain,
				new Vector3f(probe.getValue(EnvironmentAttributes.SKY_COLOR, pt)),
				new Vector3f(probe.getValue(EnvironmentAttributes.FOG_COLOR, pt)), new org.joml.Vector4f(sunrise));
		finishSkyBake();
		long now = System.nanoTime();
		if (skyBake == null && (skyTexture == null || (state.differs(skyState) && now - skyBakedAt > SKY_BAKE_INTERVAL_NS))) {
			startSkyBake(state);
			skyBakedAt = now;
		}
		if (skyTexture != null) {
			float k = mod.skyIntensity * clear;
			Path texture = skyTexture;
			// Each bake is a new file, so its generation stands in for the texture.
			skyLight = remakeIfChanged(skyLight, skyParams, new float[] {k, skyTextureGen},
					() -> api.createDomeLight(0x736b79L, k, k, k, SkyDome.SKY_TO_WORLD, texture));
			api.drawLightInstance(skyLight);
		}
	}

	/**
	 * Returns {@code handle} if it was made with {@code params} (kept in {@code last}); otherwise destroys it and
	 * makes a new light. The sun stands still while time does, and the sky changes at most once per bake.
	 */
	private long remakeIfChanged(long handle, float[] last, float[] params, java.util.function.LongSupplier create) {
		if (handle != 0 && java.util.Arrays.equals(last, params)) {
			return handle;
		}
		api.destroyLight(handle);
		System.arraycopy(params, 0, last, 0, params.length);
		return create.getAsLong();
	}

	private void destroySkyLights() {
		api.destroyLight(sunLight);
		api.destroyLight(moonLight);
		api.destroyLight(skyLight);
		sunLight = moonLight = skyLight = 0;
	}

	/** Writes the sky to a new file each time, on a background thread: Remix keeps a texture it has loaded from a path. */
	private void startSkyBake(SkyDome.State state) {
		skyDome.prepare();
		Path file = cacheDir.resolve("sky-" + (++skyGen) + ".dds");
		skyBakeState = state;
		skyBakeGen = skyGen;
		skyBake = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				DdsWriter.writeHdr(file, skyDome.bake(state), SkyDome.WIDTH, SkyDome.HEIGHT);
				return file;
			} catch (IOException e) {
				LOG.error("Could not write sky texture", e);
				return null;
			}
		});
	}

	/**
	 * Switches to a finished bake. The file before the current one is deleted; the dome light that used it was
	 * destroyed a frame ago.
	 */
	private void finishSkyBake() {
		if (skyBake == null || !skyBake.isDone()) {
			return;
		}
		Path file = skyBake.join();
		skyBake = null;
		if (file == null) {
			return;
		}
		if (previousSkyTexture != null) {
			try {
				Files.deleteIfExists(previousSkyTexture);
			} catch (IOException ignored) {
			}
		}
		previousSkyTexture = skyTexture;
		skyTexture = file;
		skyTextureGen = skyBakeGen;
		skyState = skyBakeState;
	}

	/** Clouds as lit geometry, following Minecraft's cloud settings; see {@link CloudMesh}. */
	private void drawClouds(Minecraft mc, ClientLevel level, CameraRenderState camera) {
		CloudStatus status = mc.options.getCloudStatus();
		Camera cam = mc.gameRenderer.mainCamera();
		float pt = camera.cameraEntityPartialTicks;
		Vector4fc color = cam.attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, pt);
		if (!RtxRemixMod.settings().mod().clouds || status == CloudStatus.OFF || color.w() <= 0f || !clouds.available()) {
			destroyClouds();
			return;
		}
		float bottomY = cam.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, pt);
		Vec3 pos = camera.pos;
		// The same scrolling as CloudRenderer.prepare.
		float offset = (float) (level.getGameTime() % (clouds.width() * 400L)) + pt;
		double cloudX = pos.x + offset * 0.030000001F;
		double cloudZ = pos.z + 3.96F;
		double widthBlocks = clouds.width() * (double) CloudMesh.CELL;
		double heightBlocks = clouds.height() * (double) CloudMesh.CELL;
		cloudX -= Math.floor(cloudX / widthBlocks) * widthBlocks;
		cloudZ -= Math.floor(cloudZ / heightBlocks) * heightBlocks;
		int cellX = Mth.floor(cloudX / CloudMesh.CELL);
		int cellZ = Mth.floor(cloudZ / CloudMesh.CELL);
		float xInCell = (float) (cloudX - cellX * CloudMesh.CELL);
		float zInCell = (float) (cloudZ - cellZ * CloudMesh.CELL);
		int chunks = Math.min(mc.options.cloudRange().get(), MAX_CLOUD_CHUNKS);
		int radius = Mth.ceil(chunks * 16 / CloudMesh.CELL);
		boolean fancy = status == CloudStatus.FANCY;
		float opacity = RtxRemixMod.settings().mod().cloudOpacity;
		if (cloudMesh == 0 || fancy != cloudFancy || radius != cloudRadius || opacity != cloudOpacity
				|| Math.abs(cellX - cloudCenterX) > CloudMesh.MARGIN || Math.abs(cellZ - cloudCenterZ) > CloudMesh.MARGIN) {
			destroyClouds();
			if (cloudMaterial == 0) {
				cloudMaterial = createCloudMaterial();
				if (cloudMaterial == 0) {
					return;
				}
			}
			MeshBuilder mesh = clouds.build(cellX, cellZ, radius + CloudMesh.MARGIN, fancy, opacity);
			if (mesh.isEmpty()) {
				return;
			}
			cloudMesh = createMesh(mesh.build(), cloudMaterial);
			cloudCenterX = cellX;
			cloudCenterZ = cellZ;
			cloudRadius = radius;
			cloudFancy = fancy;
			cloudOpacity = opacity;
		}
		float[] t = {1, 0, 0, (float) (pos.x - xInCell - (cellX - cloudCenterX) * CloudMesh.CELL),
				0, 1, 0, bottomY,
				0, 0, 1, (float) (pos.z - zInCell - (cellZ - cloudCenterZ) * CloudMesh.CELL)};
		api.drawInstance(cloudMesh, t, 0, true, true);
	}

	/** Matte white and alpha-blended; the cells' color and the soft edges come through the vertex color. */
	private long createCloudMaterial() {
		Path white = cacheDir.resolve("cloud.dds");
		byte[] px = new byte[4 * 4 * 4];
		java.util.Arrays.fill(px, (byte) 0xFF);
		try {
			DdsWriter.write(white, px, 4, 4, true, 1);
		} catch (IOException e) {
			LOG.error("Could not write cloud texture", e);
			return 0;
		}
		return api.createOpaqueMaterial(new RemixApi.OpaqueMaterial(0x636c6f7564L, white, null, null, null, null, 0f,
				null, 0f, null, false, 0, false, true, null));
	}

	private void destroyClouds() {
		if (cloudMesh != 0) {
			api.destroyMesh(cloudMesh);
			cloudMesh = 0;
		}
	}

	private static float smooth(float v, float lo, float hi) {
		float t = Math.clamp((v - lo) / (hi - lo), 0f, 1f);
		return t * t * (3 - 2 * t);
	}

	// ---------------------------------------------------------------------------------------------
	// UI layer and presentation

	/**
	 * Replaces the blit to Minecraft's swapchain: records a GPU-to-CPU copy of the HUD/menu layer plus a
	 * fence for this frame's submit, which {@link #presentFrame} waits on so the HUD is never frames behind.
	 */
	public void captureUi(CommandEncoder encoder, GpuTextureView view) {
		if (!RtxRemixMod.settings().mod().drawHudThroughRemix) {
			return;
		}
		GpuTexture tex = view.texture();
		int w = tex.getWidth(0);
		int h = tex.getHeight(0);
		long size = (long) w * h * tex.getFormat().blockSize();
		if (uiBuffer == null || uiBuffer.size() != size) {
			if (uiBuffer != null) {
				uiBuffer.close();
			}
			uiBuffer = RenderSystem.getDevice().createBuffer(() -> "RTX Remix UI readback",
					GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
		}
		uiWidth = w;
		uiHeight = h;
		encoder.copyTextureToBuffer(tex, uiBuffer, 0L, () -> {
		}, 0);
		if (uiFence != null) {
			uiFence.close();
		}
		uiFence = encoder.createFence();
	}

	/** Replaces Minecraft's swapchain present. */
	public void presentFrame() {
		Minecraft mc = Minecraft.getInstance();
		pollRemixHotkey();
		D3D9Bridge.pollExp();
		collectUi();
		if (deferredCamera != null) {
			deferredCamera.run();
			deferredCamera = null;
		}
		try {
			d3d9.ensureBackBuffer(RtxRemixMod.settings().mod().vsync);
			d3d9.beginScene();
			if (uiDirty && uiPixels != null) {
				d3d9.uploadUi(uiPixels, uiWidth, uiHeight);
				uiDirty = false;
			}
			d3d9.drawUi();
			d3d9.endSceneAndPresent();
		} catch (RemixException e) {
			LOG.error("Remix present failed", e);
		}
		if (!worldSubmitted && mc.level == null && (!sections.isEmpty() || !regions.isEmpty())) {
			// Left the world: release everything that belonged to it.
			clearSections();
			pendingOrder.clear();
			pendingSections.clear();
			dynamicMeshes.forEach(api::destroyMesh);
			dynamicMeshes.clear();
			destroyClouds();
			destroySkyLights();
		}
		worldSubmitted = false;
	}

	/**
	 * The runtime opens and closes its own menu on Alt+X. Minecraft's screen is left untouched; only the
	 * tracked state is flipped so setRemixMenuOpen does not toggle the menu a second time.
	 */
	private void pollRemixHotkey() {
		boolean down = Win32.isForeground(hwnd) && Win32.isKeyDown(0x12 /* VK_MENU */) && Win32.isKeyDown('X');
		if (down && !remixHotkeyDown) {
			remixMenuOpen = !remixMenuOpen;
		}
		remixHotkeyDown = down;
	}

	/** Runs after Minecraft's submit, so the fence covers this frame's HUD copy. */
	private void collectUi() {
		GpuFence fence = uiFence;
		if (fence == null) {
			return;
		}
		try {
			if (!fence.awaitCompletion(UI_WAIT_NS)) {
				return; // GPU is slow this frame: keep showing the previous HUD rather than stall
			}
		} catch (IllegalStateException notSubmittedYet) {
			return;
		}
		fence.close();
		uiFence = null;
		int bytes = uiWidth * uiHeight * 4;
		if (uiPixels == null || uiPixels.capacity() < bytes) {
			uiPixels = ByteBuffer.allocateDirect(bytes);
		}
		try (GpuBufferSlice.MappedView view = uiBuffer.map(true, false)) {
			ByteBuffer src = view.data().duplicate();
			src.limit(src.position() + bytes);
			uiPixels.clear();
			uiPixels.put(src);
			uiPixels.flip();
		}
		uiDirty = true;
		if (Boolean.getBoolean("rtxremix.debugUi") && (++uiDebugFrames % 300) == 1) {
			int opaque = 0;
			for (int i = 3; i < bytes; i += 4 * 61) {
				if (uiPixels.get(i) != 0) {
					opaque++;
				}
			}
			LOG.info("[ui-debug] frame {} {}x{} sampled pixels with alpha: {}/{}", uiDebugFrames, uiWidth, uiHeight,
					opaque, bytes / (4 * 61));
			byte[] dump = new byte[bytes];
			uiPixels.get(0, dump);
			try {
				DdsWriter.write(cacheDir.resolve("ui-debug.dds"), dump, uiWidth, uiHeight, false, 1);
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * Shows or hides the runtime's own Remix menu. Callers keep a Minecraft screen with no widgets open
	 * while it is shown (see RemixMenuScreen) so mouse input only reaches the Remix menu.
	 */
	public static void setRemixMenuOpen(boolean open) {
		RemixRenderer r = instance;
		if (r == null || r.remixMenuOpen == open) {
			return;
		}
		r.remixMenuOpen = open;
		r.api.setConfigVariable("rtx.showUI", open ? "2" : "0");
	}

	public static void shutdown() {
		RemixRenderer r = instance;
		if (r == null) {
			return;
		}
		instance = null;
		if (r.uiFence != null) {
			r.uiFence.close();
		}
		if (r.uiBuffer != null) {
			r.uiBuffer.close();
		}
		r.clearSections();
		r.materials.values().forEach(r.api::destroyMaterial);
		r.dynamicMeshes.forEach(r.api::destroyMesh);
		r.destroyClouds();
		r.destroySkyLights();
		if (r.cloudMaterial != 0) {
			r.api.destroyMaterial(r.cloudMaterial);
		}
		r.animatedMaterials.values().forEach(r.api::destroyMaterial);
		if (r.waterMaterial != 0) {
			r.api.destroyMaterial(r.waterMaterial);
		}
		r.dynamicMaterials.values().forEach(r.api::destroyMaterial);
		// Releasing the registered D3D9 device tears the runtime down. remixapi_Shutdown is only for
		// remixapi_Startup's standalone mode; calling it here touches freed state and crashes on exit.
		r.d3d9.close();
	}
}
