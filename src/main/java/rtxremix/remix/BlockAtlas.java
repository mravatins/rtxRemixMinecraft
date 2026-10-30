package rtxremix.remix;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rtxremix.RtxRemixMod;
import rtxremix.mixin.SpriteContentsAccessor;
import rtxremix.mixin.TextureAtlasSpriteAccessor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * CPU copy of the block atlas (first animation frame of each sprite), plus PBR atlases with the same layout
 * when the resource packs ship LabPBR 1.3 maps ({@code <texture>_n.png} and {@code <texture>_s.png}).
 * All atlases are RGBA8, top row first; Remix reads the red channel of roughness, metallic and height.
 *
 * <p>Every LabPBR channel is used: normal XY, ambient occlusion (partly baked into albedo, Remix has no AO input),
 * height (Remix POM), smoothness, F0 (dielectric reflectance, hardcoded metals and albedo metals), subsurface
 * scattering and emission. Porosity is ignored: Remix renders no rain or wetness.
 *
 * @param spriteUv the width of a typical block sprite in atlas UV units, which scales the POM depth
 * @param animated sprites with more than one animation frame, as sprite sheets Remix can play back
 * @param water    atlas UV rectangles (u0, v0, u1, v1) of the water sprites, which Remix renders as animated water
 */
public record BlockAtlas(int width, int height, byte[] albedo, Pbr pbr, float spriteUv, List<Animated> animated,
		float[] water) {
	/**
	 * {@code null} in {@link BlockAtlas#pbr} when no sprite has a LabPBR map.
	 *
	 * @param height     {@code null} when no normal map carries height
	 * @param subsurface sRGB transmittance color; {@code null} when no specular map marks subsurface scattering
	 */
	public record Pbr(byte[] normal, byte[] roughness, byte[] metallic, byte[] emissive, byte[] height,
			byte[] subsurface) {
	}

	/**
	 * An animated sprite as a Remix sprite sheet: {@code cols * rows} cells played left to right, top to bottom at
	 * {@code fps}. A frame that lasts several cells is repeated, so per-frame times in .mcmeta files are kept.
	 *
	 * @param x0     the sprite's left edge in the atlas, in pixels (past the padding)
	 * @param y0     the sprite's top edge in the atlas, in pixels
	 * @param sheet  RGBA8 albedo, {@code cols * w} by {@code rows * h}, top row first
	 */
	public record Animated(int x0, int y0, int w, int h, float u0, float v0, float u1, float v1, int cols, int rows,
			int fps, byte[] sheet) {
		/** Copies this sprite's area of a same-layout atlas into every cell, for maps that do not animate. */
		public byte[] tile(byte[] atlas, int atlasWidth) {
			int sheetWidth = cols * w;
			byte[] out = new byte[sheetWidth * rows * h * 4];
			for (int cell = 0; cell < cols * rows; cell++) {
				int cx = (cell % cols) * w;
				int cy = (cell / cols) * h;
				for (int y = 0; y < h; y++) {
					System.arraycopy(atlas, ((y0 + y) * atlasWidth + x0) * 4, out, ((cy + y) * sheetWidth + cx) * 4, w * 4);
				}
			}
			return out;
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger("rtxremix");
	/** Remix plays sprite sheets at a whole number of frames per second; Minecraft counts frame times in 20 Hz ticks. */
	private static final int[] TICKS_PER_CELL = {20, 10, 5, 4, 2, 1};
	/** Keeps sheets within texture limits and Remix's 8-bit row and column counts. */
	private static final int MAX_SHEET_SIZE = 8192;
	private static final int MAX_SHEET_CELLS = 255;
	/** Octahedral (0, 0), a normal pointing straight out of the surface. */
	private static final int FLAT_NORMAL = 0xFF808000;
	/** Matches the roughness constant of the block materials, for sprites without a specular map. */
	private static final int DEFAULT_ROUGHNESS = gray(255);
	private static final int BLACK = 0xFF000000;
	private static final int WHITE = 0xFFFFFFFF;
	/** Remix's dielectric F0; LabPBR F0 above it is approximated with partial metalness. */
	private static final float DIELECTRIC_F0 = 0.04f;
	private static final float[] SRGB_TO_LINEAR = new float[256];
	/**
	 * Linear F0 of LabPBR's hardcoded metals (F0 values 230-237: iron, gold, aluminum, chrome, copper, lead,
	 * platinum, silver), from the complex refractive indices the spec lists.
	 */
	private static final float[][] METAL_F0 = {
			f0(2.9114f, 2.9497f, 2.5845f, 3.0893f, 2.9318f, 2.7670f),
			f0(0.18299f, 0.42108f, 1.3734f, 3.4242f, 2.3459f, 1.7704f),
			f0(1.3456f, 0.96521f, 0.61722f, 7.4746f, 6.3995f, 5.3031f),
			f0(3.1071f, 3.1812f, 2.3230f, 3.3314f, 3.3291f, 3.1350f),
			f0(0.27105f, 0.67693f, 1.31640f, 3.6092f, 2.6248f, 2.2921f),
			f0(1.9100f, 1.8300f, 1.4400f, 3.5100f, 3.4000f, 3.1800f),
			f0(2.3757f, 2.0847f, 1.8453f, 4.2655f, 3.7153f, 3.1365f),
			f0(0.15943f, 0.14512f, 0.13547f, 3.9291f, 3.1900f, 2.3808f),
	};

	static {
		for (int i = 0; i < 256; i++) {
			float c = i / 255f;
			SRGB_TO_LINEAR[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
		}
	}

	/** Must run on the render thread while the atlas's resource reload is applied. */
	public static BlockAtlas build(List<TextureAtlasSprite> sprites, int width, int height) {
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		byte[] albedo = new byte[width * height * 4];
		Pbr pbr = null;
		int withMaps = 0;
		float[] spriteWidths = new float[sprites.size()];
		int spriteCount = 0;
		List<Animated> animated = new ArrayList<>();
		List<Float> water = new ArrayList<>();
		for (TextureAtlasSprite sprite : sprites) {
			SpriteContents contents = sprite.contents();
			if (contents.name().getPath().startsWith("block/water_")) {
				water.addAll(List.of(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()));
			}
			NativeImage image = ((SpriteContentsAccessor) contents).rtxremix$originalImage();
			int w = Math.min(contents.width(), image.getWidth());
			int h = Math.min(contents.height(), image.getHeight());
			spriteWidths[spriteCount++] = (float) w / width;
			int[] base = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					base[y * w + x] = image.getPixel(x, y);
				}
			}
			Region r = new Region(sprite, w, h, width, height);
			if (contents.isAnimated()) {
				Animated a = animate(sprite, image, r, w, h);
				if (a != null) {
					animated.add(a);
				}
			}

			// Maps from a lower-priority pack would not line up with this texture, so only the texture's own pack counts.
			Optional<Resource> texture = resources.getResource(texturePath(contents.name(), ""));
			if (texture.isEmpty()) {
				r.stamp(albedo, base);
				continue;
			}
			String pack = texture.get().sourcePackId();
			try (NativeImage normalMap = load(resources, contents.name(), "_n", pack);
					NativeImage specularMap = load(resources, contents.name(), "_s", pack)) {
				if (normalMap == null && specularMap == null) {
					r.stamp(albedo, base);
					continue;
				}
				if (pbr == null) {
					pbr = emptyPbr(width * height);
				}
				withMaps++;
				// AO and hardcoded metals change the albedo, but emission and subsurface color come from the original.
				int[] color = base.clone();
				if (specularMap != null) {
					pbr = applySpecular(pbr, r, specularMap, base, color, w, h, isMatte(contents.name()));
				}
				if (normalMap != null) {
					pbr = applyNormal(pbr, r, normalMap, color, w, h);
				}
				r.stamp(albedo, color);
			}
		}
		float spriteUv = 0f;
		if (spriteCount > 0) {
			Arrays.sort(spriteWidths, 0, spriteCount);
			spriteUv = spriteWidths[spriteCount / 2];
		}
		if (pbr != null) {
			LOG.info("Built LabPBR block atlases: {} of {} sprites have maps (height: {}, subsurface: {})",
					withMaps, sprites.size(), pbr.height() != null, pbr.subsurface() != null);
		}
		float[] waterRects = new float[water.size()];
		for (int i = 0; i < waterRects.length; i++) {
			waterRects[i] = water.get(i);
		}
		return new BlockAtlas(width, height, albedo, pbr, spriteUv, List.copyOf(animated), waterRects);
	}

	/** Lays out an animated sprite's frame sequence as a sprite sheet, or {@code null} if it does not fit one. */
	private static Animated animate(TextureAtlasSprite sprite, NativeImage image, Region r, int w, int h) {
		int[][] frames = AnimationFrames.of(sprite.contents());
		if (frames == null) {
			return null;
		}
		int rowSize = Math.max(1, image.getWidth() / w);
		// The longest cell that divides every frame time and a second, so the sheet keeps the timing exactly.
		int ticksPerCell = 1;
		for (int t : TICKS_PER_CELL) {
			boolean divides = true;
			for (int[] f : frames) {
				divides &= f[1] % t == 0;
			}
			if (divides) {
				ticksPerCell = t;
				break;
			}
		}
		List<Integer> cells = new ArrayList<>();
		for (int[] f : frames) {
			for (int i = 0; i < f[1] / ticksPerCell; i++) {
				cells.add(f[0]);
			}
		}
		int fps = 20 / ticksPerCell;
		int[] grid = grid(cells.size(), w, h);
		if (grid == null) {
			// Too long to keep every tick: one cell per frame at the average frame time.
			int ticks = 0;
			cells.clear();
			for (int[] f : frames) {
				cells.add(f[0]);
				ticks += f[1];
			}
			fps = Math.clamp(Math.round(20f * frames.length / ticks), 1, 20);
			grid = grid(cells.size(), w, h);
			if (grid == null) {
				return null;
			}
		}
		int cols = grid[0], rows = grid[1];
		int sheetWidth = cols * w;
		byte[] sheet = new byte[sheetWidth * rows * h * 4];
		for (int cell = 0; cell < cells.size(); cell++) {
			int index = cells.get(cell);
			int fx = (index % rowSize) * w, fy = (index / rowSize) * h;
			int cx = (cell % cols) * w, cy = (cell / cols) * h;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int p = fx + x < image.getWidth() && fy + y < image.getHeight() ? image.getPixel(fx + x, fy + y) : 0;
					int o = ((cy + y) * sheetWidth + cx + x) * 4;
					sheet[o] = (byte) (p >> 16);
					sheet[o + 1] = (byte) (p >> 8);
					sheet[o + 2] = (byte) p;
					sheet[o + 3] = (byte) (p >>> 24);
				}
			}
		}
		return new Animated(r.x0(), r.y0(), w, h, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), cols,
				rows, fps, sheet);
	}

	/** The most square {cols, rows} that holds exactly {@code cells} cells, or {@code null} if none fits. */
	private static int[] grid(int cells, int w, int h) {
		if (cells < 2 || cells > MAX_SHEET_CELLS) {
			return null;
		}
		for (int rows = (int) Math.sqrt(cells); rows >= 1; rows--) {
			if (cells % rows == 0) {
				int cols = cells / rows;
				if (cols * w <= MAX_SHEET_SIZE && rows * h <= MAX_SHEET_SIZE) {
					return new int[] {cols, rows};
				}
			}
		}
		return null;
	}

	/**
	 * LabPBR {@code _s}: R = perceptual smoothness, G = F0 (0-229 linear reflectance, 230-237 hardcoded metals,
	 * 238-255 albedo metal), B = porosity (0-64) or subsurface scattering (65-255), A = emission (255 = none).
	 */
	/**
	 * Grass keeps its pack's smoothness out: even LabPBR's rough grass (smoothness ~50) leaves a grazing-angle sheen
	 * in Remix that washes the biome tint out to a pale grey, so it is drawn fully rough instead.
	 */
	private static boolean isMatte(Identifier sprite) {
		String path = sprite.getPath();
		return path.contains("grass") && !path.contains("snow");
	}

	/** @param matte ignore the map's smoothness and keep the sprite fully rough */
	private static Pbr applySpecular(Pbr pbr, Region r, NativeImage map, int[] base, int[] color, int w, int h,
			boolean matte) {
		int n = w * h;
		int[] roughness = new int[n];
		int[] metallic = new int[n];
		int[] emissive = new int[n];
		int[] subsurface = null;
		// Hardcoded metals replace the albedo with the metal's F0; the albedo's brightness keeps the texture's detail.
		float metalLuma = 0f;
		int metalPixels = 0;
		int[] spec = new int[n];
		for (int i = 0; i < n; i++) {
			spec[i] = sample(map, i % w, i / w, w, h);
			int f0 = (spec[i] >> 8) & 0xFF;
			if (f0 >= 230 && f0 <= 237) {
				metalLuma += luma(base[i]);
				metalPixels++;
			}
		}
		metalLuma = metalPixels > 0 ? Math.max(metalLuma / metalPixels, 1e-3f) : 1f;
		for (int i = 0; i < n; i++) {
			int s = spec[i];
			roughness[i] = matte ? DEFAULT_ROUGHNESS : gray(255 - ((s >> 16) & 0xFF));

			int f0 = (s >> 8) & 0xFF;
			if (f0 >= 230 && f0 <= 237) {
				float[] metal = METAL_F0[f0 - 230];
				float detail = luma(base[i]) / metalLuma;
				color[i] = (base[i] & 0xFF000000) | srgb(metal[0] * detail) << 16 | srgb(metal[1] * detail) << 8
						| srgb(metal[2] * detail);
				metallic[i] = WHITE;
			} else if (f0 >= 238) {
				metallic[i] = WHITE;
			} else {
				// Remix has no F0 input: its specular color is mix(0.04, albedo, metallic), so solve for the
				// metalness that brings the reflectance up to the map's F0.
				float reflectance = f0 / 255f;
				float m = 0f;
				if (reflectance > DIELECTRIC_F0) {
					float albedoLuma = luma(base[i]);
					m = albedoLuma > reflectance ? (reflectance - DIELECTRIC_F0) / (albedoLuma - DIELECTRIC_F0) : 1f;
				}
				metallic[i] = gray(unorm(m));
			}

			int sss = s & 0xFF;
			// Biome-tinted sprites (grass, leaves, vines) are grey and get their color from the vertex tint, which
			// Remix does not apply to the transmittance texture: their light would pass through grey and wash them out.
			if (sss >= 65 && !isGrey(base[i])) {
				if (subsurface == null) {
					subsurface = new int[n];
					Arrays.fill(subsurface, BLACK);
				}
				subsurface[i] = scale(base[i], (sss - 65) / 190f);
			}

			int e = s >>> 24;
			emissive[i] = e < 255 ? scale(base[i], e / 254f) : BLACK;
		}
		r.stamp(pbr.roughness(), roughness);
		r.stamp(pbr.metallic(), metallic);
		r.stamp(pbr.emissive(), emissive);
		if (subsurface != null) {
			if (pbr.subsurface() == null) {
				byte[] atlas = new byte[pbr.normal().length];
				fill(atlas, BLACK);
				pbr = new Pbr(pbr.normal(), pbr.roughness(), pbr.metallic(), pbr.emissive(), pbr.height(), atlas);
			}
			r.stamp(pbr.subsurface(), subsurface);
		}
		return pbr;
	}

	/** LabPBR {@code _n}: RG = tangent-space normal XY, B = ambient occlusion (255 = none), A = height (255 = top). */
	private static Pbr applyNormal(Pbr pbr, Region r, NativeImage map, int[] color, int w, int h) {
		float aoStrength = Math.clamp(RtxRemixMod.settings().mod().pbrAoStrength, 0f, 1f);
		int n = w * h;
		int[] normal = new int[n];
		int[] height = null;
		for (int i = 0; i < n; i++) {
			int px = sample(map, i % w, i / w, w, h);
			normal[i] = octahedralNormal(px);
			// Remix has no AO texture, so occlusion darkens the albedo. That also darkens direct light, which
			// shaders leave alone, so only part of it is applied.
			int ao = px & 0xFF;
			if (ao < 255 && aoStrength > 0f) {
				float k = 1f - aoStrength * (1f - ao / 255f);
				color[i] = (color[i] & 0xFF000000) | (scale(color[i], k) & 0xFFFFFF);
			}
			int a = px >>> 24;
			if (a < 255) {
				if (height == null) {
					height = new int[n];
					Arrays.fill(height, WHITE);
				}
				height[i] = gray(a);
			}
		}
		r.stamp(pbr.normal(), normal);
		if (height != null) {
			if (pbr.height() == null) {
				byte[] atlas = new byte[pbr.normal().length];
				fill(atlas, WHITE);
				pbr = new Pbr(pbr.normal(), pbr.roughness(), pbr.metallic(), pbr.emissive(), atlas, pbr.subsurface());
			}
			r.stamp(pbr.height(), height);
		}
		return pbr;
	}

	private static Identifier texturePath(Identifier sprite, String suffix) {
		return sprite.withPath(p -> "textures/" + p + suffix + ".png");
	}

	private static NativeImage load(ResourceManager resources, Identifier sprite, String suffix, String pack) {
		Optional<Resource> res = resources.getResource(texturePath(sprite, suffix));
		if (res.isEmpty() || !res.get().sourcePackId().equals(pack)) {
			return null;
		}
		try (InputStream in = res.get().open()) {
			return NativeImage.read(in);
		} catch (IOException e) {
			LOG.warn("Could not read PBR map {}{} from {}", sprite, suffix, pack, e);
			return null;
		}
	}

	private static Pbr emptyPbr(int pixels) {
		Pbr pbr = new Pbr(new byte[pixels * 4], new byte[pixels * 4], new byte[pixels * 4], new byte[pixels * 4],
				null, null);
		fill(pbr.normal(), FLAT_NORMAL);
		fill(pbr.roughness(), DEFAULT_ROUGHNESS);
		fill(pbr.metallic(), BLACK);
		fill(pbr.emissive(), BLACK);
		return pbr;
	}

	/**
	 * Reads a map's first frame at a w*h sprite-frame pixel. Maps may have a different resolution than the texture,
	 * and animated maps may have their own frame count, so the frame height comes from the map's width.
	 */
	private static int sample(NativeImage map, int x, int y, int w, int h) {
		int frameHeight = Math.max(1, Math.min(map.getHeight(), map.getWidth() * h / w));
		return map.getPixel(x * map.getWidth() / w, y * frameHeight / h);
	}

	/**
	 * LabPBR stores tangent-space XY in DirectX convention (+Y down); Remix takes octahedral normals with +Y up.
	 */
	private static int octahedralNormal(int argb) {
		float nx = ((argb >> 16) & 0xFF) / 127.5f - 1f;
		float ny = 1f - ((argb >> 8) & 0xFF) / 127.5f;
		float nz = (float) Math.sqrt(Math.max(0f, 1f - nx * nx - ny * ny));
		float l1 = Math.abs(nx) + Math.abs(ny) + nz;
		// The upper hemisphere needs no octahedral fold.
		int ox = unorm(nx / l1 * 0.5f + 0.5f);
		int oy = unorm(ny / l1 * 0.5f + 0.5f);
		return BLACK | ox << 16 | oy << 8;
	}

	/** Fresnel reflectance at normal incidence for a conductor with refractive index n + ik, per channel. */
	private static float[] f0(float nr, float ng, float nb, float kr, float kg, float kb) {
		return new float[] {conductorF0(nr, kr), conductorF0(ng, kg), conductorF0(nb, kb)};
	}

	private static float conductorF0(float n, float k) {
		return ((n - 1) * (n - 1) + k * k) / ((n + 1) * (n + 1) + k * k);
	}

	/** Linear luminance of an sRGB pixel. */
	private static float luma(int argb) {
		return 0.2126f * SRGB_TO_LINEAR[(argb >> 16) & 0xFF] + 0.7152f * SRGB_TO_LINEAR[(argb >> 8) & 0xFF]
				+ 0.0722f * SRGB_TO_LINEAR[argb & 0xFF];
	}

	/** Whether an sRGB pixel is close to neutral grey, as the sprites Minecraft tints by biome are. */
	private static boolean isGrey(int argb) {
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 16;
	}

	/** Scales an sRGB pixel's color in linear space; the result is opaque. */
	private static int scale(int argb, float k) {
		return BLACK | srgb(SRGB_TO_LINEAR[(argb >> 16) & 0xFF] * k) << 16
				| srgb(SRGB_TO_LINEAR[(argb >> 8) & 0xFF] * k) << 8 | srgb(SRGB_TO_LINEAR[argb & 0xFF] * k);
	}

	private static int srgb(float linear) {
		float c = Math.clamp(linear, 0f, 1f);
		return unorm(c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1 / 2.4f) - 0.055f);
	}

	private static int unorm(float v) {
		return Math.clamp(Math.round(v * 255f), 0, 255);
	}

	private static int gray(int v) {
		return BLACK | v << 16 | v << 8 | v;
	}

	private static void fill(byte[] rgba, int argb) {
		byte[] px = {(byte) (argb >> 16), (byte) (argb >> 8), (byte) argb, (byte) (argb >>> 24)};
		for (int o = 0; o < rgba.length; o += 4) {
			System.arraycopy(px, 0, rgba, o, 4);
		}
	}

	/** Where a sprite sits in the atlas. */
	private record Region(int x0, int y0, int pad, int w, int h, int atlasWidth, int atlasHeight) {
		Region(TextureAtlasSprite sprite, int w, int h, int atlasWidth, int atlasHeight) {
			this(sprite.getX() + ((TextureAtlasSpriteAccessor) sprite).rtxremix$padding(),
					sprite.getY() + ((TextureAtlasSpriteAccessor) sprite).rtxremix$padding(),
					((TextureAtlasSpriteAccessor) sprite).rtxremix$padding(), w, h, atlasWidth, atlasHeight);
		}

		/**
		 * Writes w*h ARGB pixels. Sprite pixels start after a padding border; the border repeats the edge pixels
		 * so filtering and mips do not pull in neighbouring sprites or empty space.
		 */
		void stamp(byte[] rgba, int[] argb) {
			for (int y = -pad; y < h + pad; y++) {
				int ay = y0 + y;
				if (ay < 0 || ay >= atlasHeight) {
					continue;
				}
				int sy = Math.clamp(y, 0, h - 1);
				for (int x = -pad; x < w + pad; x++) {
					int ax = x0 + x;
					if (ax < 0 || ax >= atlasWidth) {
						continue;
					}
					int p = argb[sy * w + Math.clamp(x, 0, w - 1)];
					int o = (ay * atlasWidth + ax) * 4;
					rgba[o] = (byte) (p >> 16);
					rgba[o + 1] = (byte) (p >> 8);
					rgba[o + 2] = (byte) p;
					rgba[o + 3] = (byte) (p >>> 24);
				}
			}
		}
	}
}
