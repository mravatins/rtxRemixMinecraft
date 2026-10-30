package rtxremix.remix;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts compiled chunk-section meshes into Remix vertex data. Runs on Minecraft's section
 * compile workers; the render thread turns the result into Remix meshes.
 */
public final class SectionCapture {
	/** Minecraft's BLOCK vertex: position 3f, color 4ub, uv0 2f, uv2 2s. */
	private static final int MC_STRIDE = 28;
	private static final int MAX_LIGHTS_PER_SECTION = 48;
	/**
	 * How far each repeated coplanar quad (an overlay such as the grass side tint) is pushed out along its
	 * normal, on top of the offset Remix gives decals.
	 */
	private static final float OVERLAY_OFFSET = 1f / 512f;
	/** Distinct stacks are few (one per block model face that has an overlay); this only guards against packs. */
	private static final int MAX_CACHED_SPLITS = 16384;
	/** Blocks covered by one repeat of the water normal map. */
	private static final float WATER_TILE = 4f;
	/** World coordinates are wrapped to this many blocks (a multiple of the tile) to keep water UVs small. */
	private static final int WATER_PERIOD = 256;

	public record Layer(byte[] vertices, int vertexCount, int[] indices) {
	}

	/** Quads of one animated sprite, with UVs relative to the sprite (0-1) so they address a sprite-sheet cell. */
	public record AnimatedLayer(ChunkSectionLayer layer, int sprite, Layer data) {
	}

	public record Light(long hash, float x, float y, float z, float radius, float r, float g, float b) {
	}

	/**
	 * @param index the animated sprites {@link AnimatedLayer#sprite} refers to
	 * @param water   water quads with world-space UVs that tile the water normal map, or {@code null}
	 * @param overlay quads stacked on a coplanar base quad that could not be split along their cutout (animated
	 *                or water sprites), drawn as Remix decals so they blend onto the base; or {@code null}
	 */
	public record Section(long key, Map<ChunkSectionLayer, Layer> layers, List<AnimatedLayer> animated,
			AnimatedIndex index, Layer water, Layer overlay, List<Light> lights) {
		public boolean isEmpty() {
			return layers.isEmpty() && animated.isEmpty() && water == null && overlay == null && lights.isEmpty();
		}
	}

	/**
	 * Atlas UV rectangles (u0, v0, u1, v1) of the animated block sprites, in {@link BlockAtlas#animated} order, and
	 * of the water sprites.
	 */
	public record AnimatedIndex(float[] rects, float[] water) {
		/** @return the sprite whose rectangle holds (u, v), or -1 */
		int find(float u, float v) {
			return find(rects, u, v);
		}

		boolean isWater(float u, float v) {
			return find(water, u, v) >= 0;
		}

		private static int find(float[] rects, float u, float v) {
			for (int i = 0; i < rects.length; i += 4) {
				if (u >= rects[i] && v >= rects[i + 1] && u <= rects[i + 2] && v <= rects[i + 3]) {
					return i / 4;
				}
			}
			return -1;
		}
	}

	/** Keeps sprite-local UVs off the cell edges, where Remix would wrap into the neighbouring cell. */
	private static final float CELL_EPSILON = 1f / 1024f;
	private static volatile AnimatedIndex animatedIndex = new AnimatedIndex(new float[0], new float[0]);
	private static volatile AtlasAlpha atlasAlpha;

	/**
	 * The block atlas's alpha channel, to split overlays along their cutout.
	 *
	 * @param alpha  one byte per texel, top row first
	 * @param cutoff texels with alpha above it are opaque, as for the cutout materials' alpha test
	 * @param splits cuts of stacked quads already worked out against this atlas, see {@link #splitStack}
	 */
	public record AtlasAlpha(int width, int height, byte[] alpha, int cutoff, Map<StackKey, float[]> splits) {
		public static AtlasAlpha of(BlockAtlas atlas, int cutoff) {
			byte[] rgba = atlas.albedo();
			byte[] alpha = new byte[atlas.width() * atlas.height()];
			for (int i = 0; i < alpha.length; i++) {
				alpha[i] = rgba[i * 4 + 3];
			}
			return new AtlasAlpha(atlas.width(), atlas.height(), alpha, cutoff, new ConcurrentHashMap<>());
		}

		boolean opaque(float u, float v) {
			int x = Math.clamp((int) Math.floor(u * width), 0, width - 1);
			int y = Math.clamp((int) Math.floor(v * height), 0, height - 1);
			return (alpha[y * width + x] & 0xFF) > cutoff;
		}
	}

	private SectionCapture() {
	}

	/** Called whenever the block atlas is rebuilt; sections compiled from then on refer to these sprites. */
	public static AnimatedIndex setAnimated(List<BlockAtlas.Animated> sprites, float[] water) {
		float[] rects = new float[sprites.size() * 4];
		for (int i = 0; i < sprites.size(); i++) {
			BlockAtlas.Animated a = sprites.get(i);
			rects[i * 4] = a.u0();
			rects[i * 4 + 1] = a.v0();
			rects[i * 4 + 2] = a.u1();
			rects[i * 4 + 3] = a.v1();
		}
		AnimatedIndex index = new AnimatedIndex(rects, water);
		animatedIndex = index;
		return index;
	}

	/** Called whenever the block atlas is rebuilt, like {@link #setAnimated}. */
	public static void setAtlasAlpha(AtlasAlpha alpha) {
		atlasAlpha = alpha;
	}

	public static Section capture(SectionPos pos, Map<ChunkSectionLayer, MeshData> meshes, RenderSectionRegion region) {
		AnimatedIndex index = animatedIndex;
		AtlasAlpha alpha = atlasAlpha;
		// Layers in enum order (solid first), each in draw order, so a stack's base comes before its overlays.
		List<Quad> quads = new ArrayList<>();
		for (Map.Entry<ChunkSectionLayer, MeshData> e : meshes.entrySet()) {
			MeshData.DrawState state = e.getValue().drawState();
			if (state.format().getVertexSize() != MC_STRIDE) {
				continue; // only the BLOCK format is understood
			}
			ByteBuffer src = e.getValue().vertexBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
			int base = src.position();
			for (int q = 0; q < state.vertexCount() / 4; q++) {
				quads.add(new Quad(e.getKey(), src, base + q * 4 * MC_STRIDE));
			}
		}
		Outputs out = new Outputs(new float[] {Math.floorMod(pos.minBlockX(), WATER_PERIOD),
				Math.floorMod(pos.minBlockY(), WATER_PERIOD), Math.floorMod(pos.minBlockZ(), WATER_PERIOD)});
		convert(quads, index, alpha, out);
		List<AnimatedLayer> animated = new ArrayList<>();
		ChunkSectionLayer[] layerValues = ChunkSectionLayer.values();
		out.perSprite.forEach((k, b) -> animated.add(new AnimatedLayer(layerValues[k & 3], k >> 2, b.build())));
		Map<ChunkSectionLayer, Layer> layers = new EnumMap<>(ChunkSectionLayer.class);
		out.stills.forEach((layer, b) -> {
			if (!b.isEmpty()) {
				layers.put(layer, b.build());
			}
		});
		return new Section(pos.asLong(), layers, animated, index, out.water.isEmpty() ? null : out.water.build(),
				out.overlay.isEmpty() ? null : out.overlay.build(), findLights(pos, region));
	}

	/** A quad of a compiled layer: the buffer holding it and its first vertex's offset. */
	private record Quad(ChunkSectionLayer layer, ByteBuffer src, int base) {
	}

	/** Where {@link #convert} sorts quads to. */
	private static final class Outputs {
		final Map<ChunkSectionLayer, MeshBuilder> stills = new EnumMap<>(ChunkSectionLayer.class);
		/** Keyed by sprite * 4 + layer ordinal. */
		final Map<Integer, MeshBuilder> perSprite = new HashMap<>();
		final MeshBuilder water = new MeshBuilder();
		final MeshBuilder overlay = new MeshBuilder();
		/** The section's origin, wrapped, for world-space water UVs. */
		final float[] waterOrigin;

		Outputs(float[] waterOrigin) {
			this.waterOrigin = waterOrigin;
		}

		MeshBuilder still(ChunkSectionLayer layer) {
			return stills.computeIfAbsent(layer, k -> new MeshBuilder());
		}
	}

	/**
	 * Sorts the section's quads: non-animated sprites go to {@code stills}, animated sprites to {@code perSprite} and
	 * water to {@code water}, with UVs projected from the world position.
	 *
	 * <p>Quads stacked on the same face (the grass side and its tinted overlay, redstone wire) are the hard case.
	 * Minecraft 26 files each quad under the layer its own sprite needs, so a grass side's base is in the solid
	 * layer and its overlay in the cutout layer: stacks are looked for across the solid and cutout layers. Remix
	 * continues a ray that passes an alpha-tested texel from a point offset past it, which skips a coplanar base
	 * and leaves a hole, so a stack is cut into non-overlapping opaque pieces instead ({@link #splitStack}) and
	 * nothing is layered. Stacks with animated or water sprites fall back to decals in {@code overlay}.
	 */
	private static void convert(List<Quad> quads, AnimatedIndex index, AtlasAlpha alpha, Outputs out) {
		int count = quads.size();
		float[] p = new float[12];
		float[] n = new float[3];
		float[] uv = new float[2];
		// next[q] links each quad to the next one drawn on the same face; stackedOn marks all but a stack's first.
		Map<QuadKey, Integer> last = new HashMap<>();
		int[] next = new int[count];
		boolean[] stackedOn = new boolean[count];
		boolean[] plain = new boolean[count];
		Arrays.fill(next, -1);
		for (int q = 0; q < count; q++) {
			Quad quad = quads.get(q);
			uvCenter(quad, uv);
			plain[q] = !index.isWater(uv[0], uv[1]) && index.find(uv[0], uv[1]) < 0;
			if (quad.layer() == ChunkSectionLayer.TRANSLUCENT) {
				continue;
			}
			readQuad(quad.src(), quad.base(), p, n);
			QuadKey qk = QuadKey.of(p, n[0], n[1], n[2]);
			// The back of a zero-thickness cutout card (plant crosses, faces between fancy leaves) is a second quad
			// in the same place facing the other way, with mirrored UVs. Sections are drawn double-sided, so rays
			// pick either one from frame to frame and the alpha mask flickers: only the first is kept.
			if (quad.layer() == ChunkSectionLayer.CUTOUT) {
				Integer front = last.get(QuadKey.of(p, -n[0], -n[1], -n[2]));
				if (front != null && quads.get(front).layer() == ChunkSectionLayer.CUTOUT) {
					stackedOn[q] = true;
					continue;
				}
			}
			Integer prev = last.put(qk, q);
			if (prev != null) {
				next[prev] = q;
				stackedOn[q] = true;
			}
		}
		List<Quad> stack = new ArrayList<>();
		for (int q = 0; q < count; q++) {
			if (stackedOn[q]) {
				continue;
			}
			if (next[q] < 0) {
				emit(quads.get(q), 0, index, out, p, n);
				continue;
			}
			stack.clear();
			boolean allPlain = true;
			for (int s = q; s >= 0; s = next[s]) {
				stack.add(quads.get(s));
				allPlain &= plain[s];
			}
			if (allPlain && alpha != null && splitStack(stack, alpha, out.still(ChunkSectionLayer.SOLID))) {
				continue;
			}
			// The first quad as it is, the rest pushed out along the normal and drawn as decals.
			for (int i = 0; i < stack.size(); i++) {
				emit(stack.get(i), i, index, out, p, n);
			}
		}
	}

	private static void uvCenter(Quad quad, float[] uv) {
		float uc = 0f, vc = 0f;
		for (int v = 0; v < 4; v++) {
			uc += quad.src().getFloat(quad.base() + v * MC_STRIDE + 16);
			vc += quad.src().getFloat(quad.base() + v * MC_STRIDE + 20);
		}
		uv[0] = uc / 4f;
		uv[1] = vc / 4f;
	}

	/**
	 * Writes one quad to its output. A quad that is the {@code repeats}-th on its face is pushed out along its
	 * normal by that many {@link #OVERLAY_OFFSET}s and, unless animated or water, drawn as a decal.
	 */
	private static void emit(Quad quad, int repeats, AnimatedIndex index, Outputs out, float[] p, float[] n) {
		ByteBuffer src = quad.src();
		int qBase = quad.base();
		readQuad(src, qBase, p, n);
		float nx = n[0], ny = n[1], nz = n[2];
		if (repeats > 0) {
			float off = repeats * OVERLAY_OFFSET;
			for (int v = 0; v < 4; v++) {
				p[v * 3] += nx * off;
				p[v * 3 + 1] += ny * off;
				p[v * 3 + 2] += nz * off;
			}
		}
		float[] c = new float[2];
		uvCenter(quad, c);
		boolean isWater = index.isWater(c[0], c[1]);
		int sprite = isWater ? -1 : index.find(c[0], c[1]);
		MeshBuilder b;
		if (isWater) {
			b = out.water;
		} else if (sprite >= 0) {
			b = out.perSprite.computeIfAbsent(sprite * 4 + quad.layer().ordinal(), k -> new MeshBuilder());
		} else if (repeats > 0) {
			b = out.overlay;
		} else {
			b = out.still(quad.layer());
		}
		// Water UVs follow the face's dominant axis, so tops and sides alike tile seamlessly across blocks.
		int axis = Math.abs(ny) >= Math.abs(nx) && Math.abs(ny) >= Math.abs(nz) ? 1 : Math.abs(nx) >= Math.abs(nz) ? 0 : 2;
		if (quad.layer() == ChunkSectionLayer.CUTOUT && Math.abs(ny) < 0.1f && Math.abs(nx) > 0.3f && Math.abs(nz) > 0.3f) {
			// Plant cross models: shaded with the ground's normal they light like the grass around them instead of
			// as flat cards facing sideways. The vertex normal only feeds shading; the geometry normal is unchanged.
			nx = 0f;
			ny = 1f;
			nz = 0f;
		}
		float[] rects = index.rects();
		float[] origin = out.waterOrigin;
		for (int v = 0; v < 4; v++) {
			int o = qBase + v * MC_STRIDE;
			float u = src.getFloat(o + 16);
			float tv = src.getFloat(o + 20);
			if (isWater) {
				float wx = origin[0] + p[v * 3], wy = origin[1] + p[v * 3 + 1], wz = origin[2] + p[v * 3 + 2];
				u = (axis == 0 ? wz : wx) / WATER_TILE;
				tv = (axis == 1 ? wz : -wy) / WATER_TILE;
			} else if (sprite >= 0) {
				int r = sprite * 4;
				u = Math.clamp((u - rects[r]) / (rects[r + 2] - rects[r]), CELL_EPSILON, 1f - CELL_EPSILON);
				tv = Math.clamp((tv - rects[r + 1]) / (rects[r + 3] - rects[r + 1]), CELL_EPSILON, 1f - CELL_EPSILON);
			}
			b.vertex(p[v * 3], p[v * 3 + 1], p[v * 3 + 2], nx, ny, nz, u, tv, color(src, o));
		}
	}

	/**
	 * Replaces a stack of quads on one face with non-overlapping pieces on a texel grid over the face: each texel
	 * shows the last-drawn quad that is opaque there (with that quad's UVs and tint), and texels no quad covers get
	 * no geometry. Solid-layer quads count as opaque everywhere. Pieces are opaque, so they go to the solid mesh.
	 *
	 * @return false, writing nothing, if the quads' corners do not line up
	 */
	private static boolean splitStack(List<Quad> stack, AtlasAlpha alpha, MeshBuilder out) {
		int m = stack.size();
		float[] p = new float[12];
		float[] n = new float[3];
		float[] other = new float[12];
		float[] ignored = new float[3];
		Quad first = stack.getFirst();
		readQuad(first.src(), first.base(), p, n);
		// Per quad and corner, in the first quad's corner order: UV and color.
		float[][] uvs = new float[m][8];
		int[][] colors = new int[m][4];
		boolean[] opaque = new boolean[m];
		int[] key = new int[m * 9];
		for (int i = 0; i < m; i++) {
			Quad quad = stack.get(i);
			readQuad(quad.src(), quad.base(), other, ignored);
			for (int v = 0; v < 4; v++) {
				int match = -1;
				for (int w = 0; w < 4 && match < 0; w++) {
					if (Math.abs(other[w * 3] - p[v * 3]) < 1e-3f && Math.abs(other[w * 3 + 1] - p[v * 3 + 1]) < 1e-3f
							&& Math.abs(other[w * 3 + 2] - p[v * 3 + 2]) < 1e-3f) {
						match = w;
					}
				}
				if (match < 0) {
					return false;
				}
				int o = quad.base() + match * MC_STRIDE;
				uvs[i][v * 2] = quad.src().getFloat(o + 16);
				uvs[i][v * 2 + 1] = quad.src().getFloat(o + 20);
				colors[i][v] = color(quad.src(), o);
				key[i * 9 + v * 2] = Float.floatToIntBits(uvs[i][v * 2]);
				key[i * 9 + v * 2 + 1] = Float.floatToIntBits(uvs[i][v * 2 + 1]);
			}
			opaque[i] = quad.layer() == ChunkSectionLayer.SOLID;
			key[i * 9 + 8] = quad.layer().ordinal();
		}
		// Every grass side in the world is the same stack, so the cut is worked out once per atlas.
		Map<StackKey, float[]> cache = alpha.splits();
		if (cache.size() > MAX_CACHED_SPLITS) {
			cache.clear();
		}
		float[] pieces = cache.computeIfAbsent(new StackKey(key), k -> pieces(uvs, opaque, alpha));
		for (int i = 0; i < pieces.length; i += 5) {
			float s0 = pieces[i], t0 = pieces[i + 1], s1 = pieces[i + 2], t1 = pieces[i + 3];
			int owner = (int) pieces[i + 4];
			piece(out, p, n, uvs[owner], colors[owner], s0, t0);
			piece(out, p, n, uvs[owner], colors[owner], s1, t0);
			piece(out, p, n, uvs[owner], colors[owner], s1, t1);
			piece(out, p, n, uvs[owner], colors[owner], s0, t1);
		}
		return true;
	}

	/**
	 * Cuts a stack's face (s along corner 0 to 1, t along corner 0 to 3) into rectangles, five floats each:
	 * s0, t0, s1, t1 and the index of the quad shown. Equal texels are merged greedily, first along s, then t.
	 */
	private static float[] pieces(float[][] uvs, boolean[] opaque, AtlasAlpha alpha) {
		int cols = 1, rows = 1;
		for (int i = 0; i < uvs.length; i++) {
			if (!opaque[i]) {
				cols = Math.max(cols, texels(uvs[i], 1, alpha));
				rows = Math.max(rows, texels(uvs[i], 3, alpha));
			}
		}
		int[] owner = new int[cols * rows];
		float[] uv = new float[2];
		for (int j = 0; j < rows; j++) {
			for (int c = 0; c < cols; c++) {
				float s = (c + 0.5f) / cols, t = (j + 0.5f) / rows;
				int o = -1;
				for (int i = uvs.length - 1; i >= 0 && o < 0; i--) {
					if (opaque[i]) {
						o = i;
					} else {
						lerpUv(uvs[i], s, t, uv);
						if (alpha.opaque(uv[0], uv[1])) {
							o = i;
						}
					}
				}
				owner[j * cols + c] = o;
			}
		}
		float[] out = new float[cols * rows * 5];
		int len = 0;
		boolean[] done = new boolean[cols * rows];
		for (int j = 0; j < rows; j++) {
			for (int c = 0; c < cols; c++) {
				int cell = j * cols + c;
				int o = owner[cell];
				if (done[cell] || o < 0) {
					continue;
				}
				int w = 1;
				while (c + w < cols && !done[cell + w] && owner[cell + w] == o) {
					w++;
				}
				int h = 1;
				grow:
				while (j + h < rows) {
					for (int k = 0; k < w; k++) {
						int below = (j + h) * cols + c + k;
						if (done[below] || owner[below] != o) {
							break grow;
						}
					}
					h++;
				}
				for (int y = j; y < j + h; y++) {
					Arrays.fill(done, y * cols + c, y * cols + c + w, true);
				}
				out[len++] = (float) c / cols;
				out[len++] = (float) j / rows;
				out[len++] = (float) (c + w) / cols;
				out[len++] = (float) (j + h) / rows;
				out[len++] = o;
			}
		}
		return Arrays.copyOf(out, len);
	}

	/** How many atlas texels a quad spans from corner 0 to {@code corner}, at least 1 and at most 128. */
	private static int texels(float[] uv, int corner, AtlasAlpha alpha) {
		float du = Math.abs(uv[corner * 2] - uv[0]) * alpha.width();
		float dv = Math.abs(uv[corner * 2 + 1] - uv[1]) * alpha.height();
		return Math.clamp(Math.round(Math.max(du, dv)), 1, 128);
	}

	/** Bilinear interpolation over the quad's corners 0-1-2-3 at (s, t). */
	private static float bilerp(float c0, float c1, float c2, float c3, float s, float t) {
		return (1 - s) * (1 - t) * c0 + s * (1 - t) * c1 + s * t * c2 + (1 - s) * t * c3;
	}

	private static void lerpUv(float[] uv, float s, float t, float[] out) {
		out[0] = bilerp(uv[0], uv[2], uv[4], uv[6], s, t);
		out[1] = bilerp(uv[1], uv[3], uv[5], uv[7], s, t);
	}

	private static void piece(MeshBuilder out, float[] p, float[] n, float[] uvs, int[] cs, float s, float t) {
		float[] uv = new float[2];
		lerpUv(uvs, s, t, uv);
		int argb = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int c = Math.round(bilerp(cs[0] >>> shift & 0xFF, cs[1] >>> shift & 0xFF, cs[2] >>> shift & 0xFF,
					cs[3] >>> shift & 0xFF, s, t));
			argb |= Math.clamp(c, 0, 255) << shift;
		}
		out.vertex(bilerp(p[0], p[3], p[6], p[9], s, t), bilerp(p[1], p[4], p[7], p[10], s, t),
				bilerp(p[2], p[5], p[8], p[11], s, t), n[0], n[1], n[2], uv[0], uv[1], argb);
	}

	/**
	 * Vertex color as 0xAARRGGBB: the biome tint alone, as RenderSectionRegionMixin turns off Minecraft's directional
	 * shade and SectionCompilerMixin its ambient occlusion.
	 */
	private static int color(ByteBuffer src, int o) {
		int r = src.get(o + 12) & 0xFF;
		int g = src.get(o + 13) & 0xFF;
		int b = src.get(o + 14) & 0xFF;
		int a = src.get(o + 15) & 0xFF;
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Reads a quad's corner positions into {@code p} and its face normal into {@code n}. */
	private static void readQuad(ByteBuffer src, int qBase, float[] p, float[] n) {
		for (int v = 0; v < 4; v++) {
			int o = qBase + v * MC_STRIDE;
			p[v * 3] = src.getFloat(o);
			p[v * 3 + 1] = src.getFloat(o + 4);
			p[v * 3 + 2] = src.getFloat(o + 8);
		}
		// Face normal: (v1 - v0) x (v2 - v0), matching Minecraft's quad winding.
		float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
		float bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
		float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 1e-8f) {
			n[0] = nx / len;
			n[1] = ny / len;
			n[2] = nz / len;
		} else {
			n[0] = 0f;
			n[1] = 1f;
			n[2] = 0f;
		}
	}

	/** A stack's UVs and layers, per quad: eight UV floats as bits, then the layer ordinal. */
	record StackKey(int[] data) {
		@Override
		public boolean equals(Object o) {
			return o instanceof StackKey k && Arrays.equals(data, k.data);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(data);
		}
	}

	/** Identifies a quad by its corner positions (in any order) and facing, to find stacked overlays. */
	private record QuadKey(long a, long b, long c, long d, int facing) {
		static QuadKey of(float[] p, float nx, float ny, float nz) {
			long[] v = new long[4];
			for (int i = 0; i < 4; i++) {
				// Section-relative coordinates in 1/4096 block steps fit in 21 bits each.
				long x = Math.round(p[i * 3] * 4096f) & 0x1FFFFF;
				long y = Math.round(p[i * 3 + 1] * 4096f) & 0x1FFFFF;
				long z = Math.round(p[i * 3 + 2] * 4096f) & 0x1FFFFF;
				v[i] = (x << 42) | (y << 21) | z;
			}
			Arrays.sort(v);
			int facing = (Math.round(nx * 8f) + 8) * 289 + (Math.round(ny * 8f) + 8) * 17 + (Math.round(nz * 8f) + 8);
			return new QuadKey(v[0], v[1], v[2], v[3], facing);
		}
	}

	private static List<Light> findLights(SectionPos pos, RenderSectionRegion region) {
		List<Light> lights = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int x0 = pos.minBlockX(), y0 = pos.minBlockY(), z0 = pos.minBlockZ();
		for (int y = 0; y < 16 && lights.size() < MAX_LIGHTS_PER_SECTION; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					m.set(x0 + x, y0 + y, z0 + z);
					BlockState state = region.getBlockState(m);
					int emission = state.getLightEmission();
					if (emission <= 0) {
						continue;
					}
					float[] color = lightColor(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
					float strength = emission / 15f;
					// A light inside a full block would be occluded by its own faces, so it pokes just past them.
					float radius = state.isSolidRender() ? 0.56f : 0.12f;
					long hash = BlockPos.asLong(m.getX(), m.getY(), m.getZ()) * 0x9E3779B97F4A7C15L;
					lights.add(new Light(hash, m.getX() + 0.5f, m.getY() + 0.5f, m.getZ() + 0.5f, radius,
							color[0] * strength, color[1] * strength, color[2] * strength));
					if (lights.size() >= MAX_LIGHTS_PER_SECTION) {
						return lights;
					}
				}
			}
		}
		return lights;
	}

	private static float[] lightColor(String id) {
		if (id.contains("soul")) return new float[] {0.35f, 0.75f, 1.0f};
		if (id.contains("redstone")) return new float[] {1.0f, 0.15f, 0.05f};
		if (id.contains("sea_lantern") || id.contains("beacon") || id.contains("end_rod") || id.contains("conduit"))
			return new float[] {0.8f, 0.9f, 1.0f};
		if (id.contains("copper")) return new float[] {0.75f, 1.0f, 0.75f};
		if (id.contains("amethyst") || id.contains("crying")) return new float[] {0.7f, 0.4f, 1.0f};
		if (id.contains("lava") || id.contains("magma")) return new float[] {1.0f, 0.45f, 0.1f};
		return new float[] {1.0f, 0.72f, 0.42f}; // torches, lanterns, glowstone, fire
	}
}
