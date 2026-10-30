package rtxremix.remix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Foreign Function & Memory bindings for the Remix C API (remix_c.h) as shipped with the
 * public RTX Remix 1.6.1 runtime, which implements API version 0.6.
 *
 * <p>Struct layouts mirror remix_c.h for x64; offsets are spelled out next to each write.
 */
public final class RemixApi {
	public static final int VERSION_MAJOR = 0;
	public static final int VERSION_MINOR = 6;
	public static final int VERSION_PATCH = 5;

	// remixapi_StructType
	static final int ST_INITIALIZE_LIBRARY_INFO = 1;
	static final int ST_MATERIAL_INFO = 2;
	static final int ST_MATERIAL_INFO_TRANSLUCENT_EXT = 4;
	static final int ST_MATERIAL_INFO_OPAQUE_EXT = 5;
	static final int ST_LIGHT_INFO = 6;
	static final int ST_LIGHT_INFO_DISTANT_EXT = 7;
	static final int ST_LIGHT_INFO_SPHERE_EXT = 11;
	static final int ST_MESH_INFO = 12;
	static final int ST_INSTANCE_INFO = 13;
	static final int ST_INSTANCE_INFO_BLEND_EXT = 15;
	// RtTextureArgSource and DxvkRtTextureOperation (dxvk-remix surface_shared.h)
	private static final int TEX_ARG_TEXTURE = 1;
	private static final int TEX_ARG_VERTEX_COLOR = 2;
	private static final int TEX_OP_SELECT_ARG1 = 1;
	private static final int TEX_OP_MODULATE = 3;
	static final int ST_CAMERA_INFO = 16;
	static final int ST_CAMERA_INFO_PARAMETERIZED_EXT = 17;
	static final int ST_MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT = 18;
	static final int ST_LIGHT_INFO_DOME_EXT = 20;
	static final int ST_STARTUP_INFO = 22;
	static final int ST_PRESENT_INFO = 23;

	// remixapi_InstanceCategoryBit
	public static final int CATEGORY_WORLD_UI = 1 << 0;
	public static final int CATEGORY_SKY = 1 << 2;
	public static final int CATEGORY_IGNORE = 1 << 3;
	public static final int CATEGORY_PARTICLE = 1 << 9;
	public static final int CATEGORY_DECAL_STATIC = 1 << 11;
	public static final int CATEGORY_TERRAIN = 1 << 16;
	public static final int CATEGORY_ANIMATED_WATER = 1 << 17;
	public static final int CATEGORY_IGNORE_BAKED_LIGHTING = 1 << 20;

	public static final int VERTEX_STRIDE = 64; // sizeof(remixapi_HardcodedVertex)

	// Indices into remixapi_Interface (API 0.6): one function pointer each.
	private static final int FN_SHUTDOWN = 0;
	private static final int FN_CREATE_MATERIAL = 1;
	private static final int FN_DESTROY_MATERIAL = 2;
	private static final int FN_CREATE_MESH = 3;
	private static final int FN_DESTROY_MESH = 4;
	private static final int FN_SETUP_CAMERA = 5;
	private static final int FN_DRAW_INSTANCE = 6;
	private static final int FN_CREATE_LIGHT = 7;
	private static final int FN_DESTROY_LIGHT = 8;
	private static final int FN_DRAW_LIGHT_INSTANCE = 9;
	private static final int FN_SET_CONFIG_VARIABLE = 10;
	private static final int FN_DXVK_CREATE_D3D9 = 11;
	private static final int FN_DXVK_REGISTER_D3D9_DEVICE = 12;
	private static final int FN_STARTUP = 19;
	private static final int FN_PRESENT = 20;
	private static final int INTERFACE_FUNCTION_COUNT = 22;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final FunctionDescriptor I_P = FunctionDescriptor.of(JAVA_INT, ADDRESS);
	private static final FunctionDescriptor I_PP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
	private static final FunctionDescriptor I_V = FunctionDescriptor.of(JAVA_INT);
	private static final FunctionDescriptor I_IP = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);

	private final Path runtimeDir;
	private final MethodHandle shutdown;
	private final MethodHandle createMaterial;
	private final MethodHandle destroyMaterial;
	private final MethodHandle createMesh;
	private final MethodHandle destroyMesh;
	private final MethodHandle setupCamera;
	private final MethodHandle drawInstance;
	private final MethodHandle createLight;
	private final MethodHandle destroyLight;
	private final MethodHandle drawLightInstance;
	private final MethodHandle setConfigVariable;
	private final MethodHandle dxvkCreateD3D9;
	private final MethodHandle dxvkRegisterD3D9Device;
	private final MethodHandle startup;
	private final MethodHandle present;
	private MethodHandle direct3DCreate9Ex;
	/** remixapi_InstanceInfo reused by every {@link #drawInstance}, with its two possible blend extensions. */
	private final MemorySegment instanceInfo;
	private final MemorySegment blendTextureAlpha;
	private final MemorySegment blendVertexAlpha;

	private RemixApi(Path runtimeDir, MemorySegment iface) {
		this.runtimeDir = runtimeDir;
		instanceInfo = Arena.global().allocate(88, 8);
		instanceInfo.set(JAVA_INT, 0, ST_INSTANCE_INFO);
		blendTextureAlpha = blendModulateVertexColor(Arena.global(), false);
		blendVertexAlpha = blendModulateVertexColor(Arena.global(), true);
		shutdown = fn(iface, FN_SHUTDOWN, I_V);
		createMaterial = fn(iface, FN_CREATE_MATERIAL, I_PP);
		destroyMaterial = fn(iface, FN_DESTROY_MATERIAL, I_P);
		createMesh = fn(iface, FN_CREATE_MESH, I_PP);
		destroyMesh = fn(iface, FN_DESTROY_MESH, I_P);
		setupCamera = fn(iface, FN_SETUP_CAMERA, I_P);
		drawInstance = fn(iface, FN_DRAW_INSTANCE, I_P);
		createLight = fn(iface, FN_CREATE_LIGHT, I_PP);
		destroyLight = fn(iface, FN_DESTROY_LIGHT, I_P);
		drawLightInstance = fn(iface, FN_DRAW_LIGHT_INSTANCE, I_P);
		setConfigVariable = fn(iface, FN_SET_CONFIG_VARIABLE, I_PP);
		dxvkCreateD3D9 = fn(iface, FN_DXVK_CREATE_D3D9, I_IP);
		dxvkRegisterD3D9Device = fn(iface, FN_DXVK_REGISTER_D3D9_DEVICE, I_P);
		startup = fn(iface, FN_STARTUP, I_P);
		present = fn(iface, FN_PRESENT, I_P);
	}

	private static MethodHandle fn(MemorySegment iface, int index, FunctionDescriptor desc) {
		MemorySegment ptr = iface.get(ADDRESS, (long) index * ADDRESS.byteSize());
		if (ptr.equals(MemorySegment.NULL)) {
			throw new RemixException("Remix runtime does not implement interface function #" + index);
		}
		return LINKER.downcallHandle(ptr, desc);
	}

	/** Loads d3d9.dll from the runtime folder and initializes the API. */
	public static RemixApi load(Path runtimeDir) {
		Path dll = runtimeDir.resolve("d3d9.dll");
		if (!Files.isRegularFile(dll)) {
			throw new RemixException("RTX Remix runtime not found: " + dll);
		}
		Win32.setDllDirectory(runtimeDir.toAbsolutePath().toString());
		SymbolLookup lib = SymbolLookup.libraryLookup(dll, Arena.global());
		MemorySegment init = lib.find("remixapi_InitializeLibrary")
				.orElseThrow(() -> new RemixException("d3d9.dll does not export remixapi_InitializeLibrary; is it the RTX Remix runtime?"));
		MethodHandle initialize = LINKER.downcallHandle(init, I_PP);

		MemorySegment iface = Arena.global().allocate(ADDRESS.byteSize() * INTERFACE_FUNCTION_COUNT, 8);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment info = arena.allocate(24, 8);
			info.set(JAVA_INT, 0, ST_INITIALIZE_LIBRARY_INFO);
			info.set(JAVA_LONG, 16, ((long) VERSION_MAJOR << 48) | ((long) VERSION_MINOR << 16) | VERSION_PATCH);
			check((int) initialize.invokeExact(info, iface), "remixapi_InitializeLibrary");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("remixapi_InitializeLibrary failed", t);
		}
		MemorySegment create9Ex = lib.find("Direct3DCreate9Ex")
				.orElseThrow(() -> new RemixException("d3d9.dll does not export Direct3DCreate9Ex"));
		RemixApi api = new RemixApi(runtimeDir, iface);
		api.direct3DCreate9Ex = LINKER.downcallHandle(create9Ex, FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
		return api;
	}

	public Path runtimeDir() {
		return runtimeDir;
	}

	static void check(int code, String what) {
		if (code != 0) {
			throw new RemixException(what + " failed: " + ErrorCodes.describe(code), code);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Lifecycle

	/** Starts the API-only renderer (no D3D9 device); Remix presents directly into {@code hwnd}. */
	public void startup(long hwnd) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment info = arena.allocate(40, 8);
			info.set(JAVA_INT, 0, ST_STARTUP_INFO);
			info.set(JAVA_LONG, 16, hwnd);
			check((int) startup.invokeExact(info), "Startup");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("Startup failed", t);
		}
	}

	/** API-only presentation; not used when a D3D9 device is registered. */
	public void present() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment info = arena.allocate(24, 8);
			info.set(JAVA_INT, 0, ST_PRESENT_INFO);
			check((int) present.invokeExact(info), "Present");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("Present failed", t);
		}
	}

	public void shutdown() {
		try {
			int ignored = (int) shutdown.invokeExact();
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Returns an IDirect3D9Ex* from the runtime's regular Direct3DCreate9Ex. Unlike dxvk_CreateD3D9, its
	 * devices keep draw call conversion on, which Remix needs to see the HUD quad as UI and composite the
	 * path-traced image underneath it; with conversion off it composites at Present, over the HUD.
	 */
	public MemorySegment direct3DCreate9Ex() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ADDRESS);
			int hr = (int) direct3DCreate9Ex.invokeExact(32 /* D3D_SDK_VERSION */, out);
			if (hr < 0) {
				throw new RemixException("Direct3DCreate9Ex failed: HRESULT 0x" + Integer.toHexString(hr), hr);
			}
			return out.get(ADDRESS, 0);
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("Direct3DCreate9Ex failed", t);
		}
	}

	/** Returns an IDirect3D9Ex* created by the Remix runtime. */
	public MemorySegment createD3D9() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ADDRESS);
			check((int) dxvkCreateD3D9.invokeExact(0, out), "dxvk_CreateD3D9");
			return out.get(ADDRESS, 0);
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("dxvk_CreateD3D9 failed", t);
		}
	}

	public void registerD3D9Device(MemorySegment device) {
		try {
			check((int) dxvkRegisterD3D9Device.invokeExact(device), "dxvk_RegisterD3D9Device");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("dxvk_RegisterD3D9Device failed", t);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Options

	/** Returns the raw error code so callers can report an invalid key or value without throwing. */
	public int setConfigVariable(String key, String value) {
		try (Arena arena = Arena.ofConfined()) {
			return (int) setConfigVariable.invokeExact(arena.allocateFrom(key, StandardCharsets.UTF_8),
					arena.allocateFrom(value, StandardCharsets.UTF_8));
		} catch (Throwable t) {
			return ErrorCodes.GENERAL_FAILURE;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Materials

	/**
	 * @param height     height map (1 = surface, 0 = deepest), or {@code null} for no parallax occlusion mapping
	 * @param displaceIn how deep 0 in the height map lies, as a fraction of the texture's UV width
	 * @param subsurface thin-opaque subsurface scattering, or {@code null}
	 * @param alphaBlend blends by texture alpha (times vertex alpha) instead of being opaque
	 * @param sheet      plays the textures as a sprite sheet, or {@code null}
	 */
	public record OpaqueMaterial(long hash, Path albedo, Path normal, Path roughness, Path metallic, Path emissive,
			float emissiveIntensity, Path height, float displaceIn, Subsurface subsurface, boolean alphaTest,
			int alphaReference, boolean nearestFilter, boolean alphaBlend, SpriteSheet sheet) {
		public OpaqueMaterial(long hash, Path albedo, Path normal, Path roughness, Path metallic, Path emissive,
				float emissiveIntensity, Path height, float displaceIn, Subsurface subsurface, boolean alphaTest,
				int alphaReference, boolean nearestFilter) {
			this(hash, albedo, normal, roughness, metallic, emissive, emissiveIntensity, height, displaceIn, subsurface,
					alphaTest, alphaReference, nearestFilter, false, null);
		}
	}

	/**
	 * Remix advances one cell every {@code 1 / fps} seconds, left to right then top to bottom; texture
	 * coordinates (0-1) address a single cell.
	 */
	public record SpriteSheet(int cols, int rows, int fps) {
	}

	/**
	 * @param transmittance       sRGB color that light keeps after passing through the surface; black disables it
	 * @param singleScattering    sRGB single-scattering albedo
	 * @param measurementDistance thickness the transmittance color is measured at, in world units
	 */
	public record Subsurface(Path transmittance, Path singleScattering, float measurementDistance) {
	}

	public record TranslucentMaterial(long hash, Path albedo, Path normal, float refractiveIndex, float r, float g, float b,
			float measurementDistance, boolean nearestFilter, SpriteSheet sheet) {
		public TranslucentMaterial(long hash, Path albedo, Path normal, float refractiveIndex, float r, float g, float b,
				float measurementDistance, boolean nearestFilter) {
			this(hash, albedo, normal, refractiveIndex, r, g, b, measurementDistance, nearestFilter, null);
		}
	}

	public long createOpaqueMaterial(OpaqueMaterial m) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment opaque = arena.allocate(128, 8);
			opaque.set(JAVA_INT, 0, ST_MATERIAL_INFO_OPAQUE_EXT);
			opaque.set(ADDRESS, 16, path(arena, m.roughness()));
			opaque.set(ADDRESS, 24, path(arena, m.metallic()));
			opaque.set(JAVA_FLOAT, 32, 0f);       // anisotropy
			opaque.set(JAVA_FLOAT, 36, 0.2f);     // albedoConstant
			opaque.set(JAVA_FLOAT, 40, 0.2f);
			opaque.set(JAVA_FLOAT, 44, 0.2f);
			opaque.set(JAVA_FLOAT, 48, 1f);       // opacityConstant
			opaque.set(JAVA_FLOAT, 52, 1f);       // roughnessConstant: fully matte, like dirt, stone and grass
			opaque.set(JAVA_FLOAT, 56, 0f);       // metallicConstant
			opaque.set(JAVA_INT, 60, 0);          // thinFilmThickness_hasvalue
			opaque.set(JAVA_INT, 68, 0);          // alphaIsThinFilmThickness
			opaque.set(ADDRESS, 72, path(arena, m.height())); // heightTexture
			opaque.set(JAVA_FLOAT, 80, m.height() != null ? m.displaceIn() : 0f); // displaceIn
			opaque.set(JAVA_INT, 84, 0);          // useDrawCallAlphaState: the material owns alpha state
			opaque.set(JAVA_INT, 88, m.alphaBlend() ? 1 : 0); // blendType_hasvalue
			opaque.set(JAVA_INT, 92, 0);          // blendType: alpha
			opaque.set(JAVA_INT, 96, 0);          // invertedBlend
			opaque.set(JAVA_INT, 100, m.alphaTest() ? 4 /* greater */ : 7 /* always */);
			opaque.set(ValueLayout.JAVA_BYTE, 104, (byte) m.alphaReference());
			opaque.set(JAVA_FLOAT, 108, 0f);      // displaceOut
			opaque.set(JAVA_INT, 112, 0);         // enableDlssControlMask
			if (m.subsurface() != null) {
				MemorySegment sss = arena.allocate(112, 8);
				sss.set(JAVA_INT, 0, ST_MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT);
				sss.set(ADDRESS, 16, path(arena, m.subsurface().transmittance()));
				sss.set(ADDRESS, 24, MemorySegment.NULL); // subsurfaceThicknessTexture: the constant below
				sss.set(ADDRESS, 32, path(arena, m.subsurface().singleScattering()));
				sss.set(JAVA_FLOAT, 40, 0.5f);        // subsurfaceTransmittanceColor (the texture wins)
				sss.set(JAVA_FLOAT, 44, 0.5f);
				sss.set(JAVA_FLOAT, 48, 0.5f);
				sss.set(JAVA_FLOAT, 52, m.subsurface().measurementDistance());
				sss.set(JAVA_FLOAT, 56, 0.5f);        // subsurfaceSingleScatteringAlbedo (the texture wins)
				sss.set(JAVA_FLOAT, 60, 0.5f);
				sss.set(JAVA_FLOAT, 64, 0.5f);
				sss.set(JAVA_FLOAT, 68, 0f);          // subsurfaceVolumetricAnisotropy
				sss.set(JAVA_INT, 72, 0);             // subsurfaceDiffusionProfile: thin opaque instead
				sss.set(JAVA_FLOAT, 92, 0f);          // subsurfaceMaxSampleRadius
				sss.set(ADDRESS, 96, MemorySegment.NULL); // subsurfaceRadiusTexture
				opaque.set(ADDRESS, 8, sss);
			}
			MemorySegment info = baseMaterial(arena, m.hash(), m.albedo(), m.normal(), m.emissive(),
					m.emissiveIntensity(), m.nearestFilter(), m.sheet(), opaque);
			return createMaterial(arena, info);
		}
	}

	public long createTranslucentMaterial(TranslucentMaterial m) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment tr = arena.allocate(56, 8);
			tr.set(JAVA_INT, 0, ST_MATERIAL_INFO_TRANSLUCENT_EXT);
			tr.set(ADDRESS, 16, MemorySegment.NULL); // transmittanceTexture
			tr.set(JAVA_FLOAT, 24, m.refractiveIndex());
			tr.set(JAVA_FLOAT, 28, m.r());
			tr.set(JAVA_FLOAT, 32, m.g());
			tr.set(JAVA_FLOAT, 36, m.b());
			tr.set(JAVA_FLOAT, 40, m.measurementDistance());
			tr.set(JAVA_INT, 44, 0);  // thinWallThickness_hasvalue
			tr.set(JAVA_INT, 52, 0);  // useDiffuseLayer
			MemorySegment info = baseMaterial(arena, m.hash(), m.albedo(), m.normal(), null, 0f, m.nearestFilter(), m.sheet(), tr);
			return createMaterial(arena, info);
		}
	}

	private static MemorySegment baseMaterial(Arena arena, long hash, Path albedo, Path normal, Path emissive,
			float emissiveIntensity, boolean nearest, SpriteSheet sheet, MemorySegment ext) {
		MemorySegment info = arena.allocate(80, 8);
		info.set(JAVA_INT, 0, ST_MATERIAL_INFO);
		info.set(ADDRESS, 8, ext);
		info.set(JAVA_LONG, 16, hash);
		info.set(ADDRESS, 24, path(arena, albedo));
		info.set(ADDRESS, 32, path(arena, normal));
		info.set(ADDRESS, 40, MemorySegment.NULL); // tangentTexture
		info.set(ADDRESS, 48, path(arena, emissive));
		info.set(JAVA_FLOAT, 56, emissiveIntensity);
		info.set(ValueLayout.JAVA_BYTE, 72, (byte) (sheet != null ? sheet.rows() : 1)); // spriteSheetRow
		info.set(ValueLayout.JAVA_BYTE, 73, (byte) (sheet != null ? sheet.cols() : 1)); // spriteSheetCol
		info.set(ValueLayout.JAVA_BYTE, 74, (byte) (sheet != null ? sheet.fps() : 0)); // spriteSheetFps
		info.set(ValueLayout.JAVA_BYTE, 75, (byte) (nearest ? 0 : 1)); // filterMode
		info.set(ValueLayout.JAVA_BYTE, 76, (byte) 1); // wrapModeU: repeat
		info.set(ValueLayout.JAVA_BYTE, 77, (byte) 1); // wrapModeV: repeat
		return info;
	}

	private long createMaterial(Arena arena, MemorySegment info) {
		MemorySegment out = arena.allocate(ADDRESS);
		try {
			check((int) createMaterial.invokeExact(info, out), "CreateMaterial");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("CreateMaterial failed", t);
		}
		return out.get(JAVA_LONG, 0);
	}

	public void destroyMaterial(long handle) {
		invokeHandle(destroyMaterial, handle);
	}

	/** Remix paths are wide strings (wchar_t*, UTF-16 on Windows). */
	private static MemorySegment path(Arena arena, Path p) {
		if (p == null) {
			return MemorySegment.NULL;
		}
		return arena.allocateFrom(p.toAbsolutePath().toString() + '\0', StandardCharsets.UTF_16LE);
	}

	// ---------------------------------------------------------------------------------------------
	// Meshes

	/**
	 * Creates a single-surface mesh.
	 *
	 * @param vertices packed remixapi_HardcodedVertex array ({@link #VERTEX_STRIDE} bytes each)
	 * @param indices  triangle list indices
	 */
	public long createMesh(long hash, MemorySegment vertices, int vertexCount, MemorySegment indices, int indexCount,
			long material) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment surface = arena.allocate(88, 8);
			surface.set(ADDRESS, 0, vertices);
			surface.set(JAVA_LONG, 8, vertexCount);
			surface.set(ADDRESS, 16, indices);
			surface.set(JAVA_LONG, 24, indexCount);
			surface.set(JAVA_INT, 32, 0); // skinning_hasvalue
			surface.set(JAVA_LONG, 80, material);
			MemorySegment info = arena.allocate(40, 8);
			info.set(JAVA_INT, 0, ST_MESH_INFO);
			info.set(JAVA_LONG, 16, hash);
			info.set(ADDRESS, 24, surface);
			info.set(JAVA_INT, 32, 1);
			MemorySegment out = arena.allocate(ADDRESS);
			check((int) createMesh.invokeExact(info, out), "CreateMesh");
			return out.get(JAVA_LONG, 0);
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("CreateMesh failed", t);
		}
	}

	public void destroyMesh(long handle) {
		invokeHandle(destroyMesh, handle);
	}

	/**
	 * Submits one instance of a mesh for the current frame.
	 *
	 * @param transform row-major 3x4 matrix, {@code float[12]}
	 */
	public void drawInstance(long mesh, float[] transform, int categoryFlags, boolean doubleSided) {
		drawInstance(mesh, transform, categoryFlags, doubleSided, false);
	}

	/**
	 * Called for every section mesh every frame, so the structs are written into memory kept for the purpose
	 * instead of being allocated per call. Render thread only.
	 *
	 * @param vertexAlpha multiplies texture alpha by vertex alpha too (particles fade out through it)
	 */
	public void drawInstance(long mesh, float[] transform, int categoryFlags, boolean doubleSided, boolean vertexAlpha) {
		MemorySegment info = instanceInfo;
		info.set(ADDRESS, 8, vertexAlpha ? blendVertexAlpha : blendTextureAlpha);
		info.set(JAVA_INT, 16, categoryFlags);
		info.set(JAVA_LONG, 24, mesh);
		MemorySegment.copy(transform, 0, info, JAVA_FLOAT, 32, 12);
		info.set(JAVA_INT, 80, doubleSided ? 1 : 0);
		try {
			check((int) drawInstance.invokeExact(info), "DrawInstance");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("DrawInstance failed", t);
		}
	}

	/**
	 * remixapi_InstanceInfoBlendEXT that multiplies the texture by vertex color. Without it Remix uses the
	 * texture alone, which drops Minecraft's biome tint (grass, leaves and water textures are greyscale).
	 * Alpha test and blending stay off here; the materials control those.
	 */
	private static MemorySegment blendModulateVertexColor(Arena arena, boolean vertexAlpha) {
		MemorySegment blend = arena.allocate(96, 8);
		blend.set(JAVA_INT, 0, ST_INSTANCE_INFO_BLEND_EXT);
		blend.set(JAVA_INT, 24, 7);           // alphaTestCompareOp: VK_COMPARE_OP_ALWAYS
		blend.set(JAVA_INT, 32, 1);           // srcColorBlendFactor: ONE
		blend.set(JAVA_INT, 44, TEX_ARG_TEXTURE);
		blend.set(JAVA_INT, 48, TEX_ARG_VERTEX_COLOR);
		blend.set(JAVA_INT, 52, TEX_OP_MODULATE);
		blend.set(JAVA_INT, 56, TEX_ARG_TEXTURE);
		blend.set(JAVA_INT, 60, TEX_ARG_VERTEX_COLOR);
		blend.set(JAVA_INT, 64, vertexAlpha ? TEX_OP_MODULATE : TEX_OP_SELECT_ARG1);
		blend.set(JAVA_INT, 68, 0xFFFFFFFF);  // tFactor
		blend.set(JAVA_INT, 76, 1);           // srcAlphaBlendFactor: ONE
		blend.set(JAVA_INT, 88, 0xF);         // writeMask: RGBA
		// isVertexColorBakedLighting stays false: the capture already divides out Minecraft's face shading.
		return blend;
	}

	// ---------------------------------------------------------------------------------------------
	// Camera

	public void setupCamera(float px, float py, float pz, float[] forward, float[] up, float[] right,
			float fovYDegrees, float aspect, float near, float far) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment p = arena.allocate(80, 8);
			p.set(JAVA_INT, 0, ST_CAMERA_INFO_PARAMETERIZED_EXT);
			p.set(JAVA_FLOAT, 16, px);
			p.set(JAVA_FLOAT, 20, py);
			p.set(JAVA_FLOAT, 24, pz);
			MemorySegment.copy(forward, 0, p, JAVA_FLOAT, 28, 3);
			MemorySegment.copy(up, 0, p, JAVA_FLOAT, 40, 3);
			MemorySegment.copy(right, 0, p, JAVA_FLOAT, 52, 3);
			p.set(JAVA_FLOAT, 64, fovYDegrees);
			p.set(JAVA_FLOAT, 68, aspect);
			p.set(JAVA_FLOAT, 72, near);
			p.set(JAVA_FLOAT, 76, far);
			MemorySegment info = arena.allocate(152, 8);
			info.set(JAVA_INT, 0, ST_CAMERA_INFO);
			info.set(ADDRESS, 8, p);
			info.set(JAVA_INT, 16, 0); // REMIXAPI_CAMERA_TYPE_WORLD
			check((int) setupCamera.invokeExact(info), "SetupCamera");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("SetupCamera failed", t);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Lights

	public long createSphereLight(long hash, float r, float g, float b, float x, float y, float z, float radius) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment sphere = arena.allocate(64, 8);
			sphere.set(JAVA_INT, 0, ST_LIGHT_INFO_SPHERE_EXT);
			sphere.set(JAVA_FLOAT, 16, x);
			sphere.set(JAVA_FLOAT, 20, y);
			sphere.set(JAVA_FLOAT, 24, z);
			sphere.set(JAVA_FLOAT, 28, radius);
			sphere.set(JAVA_INT, 32, 0); // shaping_hasvalue
			sphere.set(JAVA_FLOAT, 60, 1f); // volumetricRadianceScale
			return createLight(arena, hash, r, g, b, sphere);
		}
	}

	public long createDistantLight(long hash, float r, float g, float b, float dx, float dy, float dz,
			float angularDiameterDegrees) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment distant = arena.allocate(40, 8);
			distant.set(JAVA_INT, 0, ST_LIGHT_INFO_DISTANT_EXT);
			distant.set(JAVA_FLOAT, 16, dx);
			distant.set(JAVA_FLOAT, 20, dy);
			distant.set(JAVA_FLOAT, 24, dz);
			distant.set(JAVA_FLOAT, 28, angularDiameterDegrees);
			distant.set(JAVA_FLOAT, 32, 1f);
			return createLight(arena, hash, r, g, b, distant);
		}
	}

	/** Dome (sky) light lit by an equirectangular texture file; {@code transform} is a row-major 3x4. */
	public long createDomeLight(long hash, float r, float g, float b, float[] transform, Path texture) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment dome = arena.allocate(72, 8);
			dome.set(JAVA_INT, 0, ST_LIGHT_INFO_DOME_EXT);
			MemorySegment.copy(transform, 0, dome, JAVA_FLOAT, 16, 12);
			dome.set(ADDRESS, 64, path(arena, texture));
			return createLight(arena, hash, r, g, b, dome);
		}
	}

	private long createLight(Arena arena, long hash, float r, float g, float b, MemorySegment ext) {
		MemorySegment info = arena.allocate(40, 8);
		info.set(JAVA_INT, 0, ST_LIGHT_INFO);
		info.set(ADDRESS, 8, ext);
		info.set(JAVA_LONG, 16, hash);
		info.set(JAVA_FLOAT, 24, r);
		info.set(JAVA_FLOAT, 28, g);
		info.set(JAVA_FLOAT, 32, b);
		MemorySegment out = arena.allocate(ADDRESS);
		try {
			check((int) createLight.invokeExact(info, out), "CreateLight");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("CreateLight failed", t);
		}
		return out.get(JAVA_LONG, 0);
	}

	public void destroyLight(long handle) {
		invokeHandle(destroyLight, handle);
	}

	public void drawLightInstance(long handle) {
		invokeHandle(drawLightInstance, handle);
	}

	private static void invokeHandle(MethodHandle mh, long handle) {
		if (handle == 0) {
			return;
		}
		try {
			int ignored = (int) mh.invokeExact(MemorySegment.ofAddress(handle));
		} catch (Throwable ignored) {
		}
	}
}
