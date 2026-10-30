package rtxremix.remix;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Minecraft's clouds as geometry, so Remix can light them and they cast shadows: one 12x4x12 box (or a flat
 * 12x12 quad) per filled texel of {@code clouds.png}, scrolled like {@link net.minecraft.client.renderer.CloudRenderer}.
 * The mesh covers a square around a center cell and is only rebuilt once the clouds or the camera have moved
 * {@link #MARGIN} cells from it; the instance transform does the rest.
 */
final class CloudMesh {
	private static final Logger LOG = LoggerFactory.getLogger("rtxremix");
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/environment/clouds.png");
	static final float CELL = 12f;
	private static final float THICKNESS = 4f;
	static final int MARGIN = 4;

	/** ARGB per texel, 0 where the texel is empty. */
	private int[] cells;
	private int width;
	private int height;
	private boolean loaded;

	/** Resource packs changed: read the cloud texture again. */
	void reload() {
		loaded = false;
	}

	boolean available() {
		if (!loaded) {
			loaded = true;
			cells = null;
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(TEXTURE); NativeImage img = NativeImage.read(in)) {
				width = img.getWidth();
				height = img.getHeight();
				cells = new int[width * height];
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int c = img.getPixel(x, y);
						cells[x + y * width] = (c >>> 24) < 10 ? 0 : c;
					}
				}
			} catch (Exception e) {
				LOG.error("Could not read the cloud texture", e);
			}
		}
		return cells != null;
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	private int cell(int x, int z) {
		return cells[Math.floorMod(x, width) + Math.floorMod(z, height) * width];
	}

	/** Filled fraction of the four cells around a grid corner: 1 inside a cloud, 1/4 on its outer corners. */
	private float corner(int gx, int gz) {
		int n = (cell(gx - 1, gz - 1) != 0 ? 1 : 0) + (cell(gx, gz - 1) != 0 ? 1 : 0)
				+ (cell(gx - 1, gz) != 0 ? 1 : 0) + (cell(gx, gz) != 0 ? 1 : 0);
		return n / 4f;
	}

	/** Vertex alpha for a corner: full inside a cloud, fading toward its outline so edges look soft. */
	private static int alpha(float corner, float opacity) {
		float f = corner * corner;
		return Math.round(Math.clamp(opacity * (0.15f + 0.85f * f), 0f, 1f) * 255);
	}

	/**
	 * Cells within {@code radius} of the center cell, in blocks relative to the center cell's corner; the bottom of the
	 * clouds is at y = 0. Fancy clouds are semi-transparent boxes with a few horizontal slices through them, so they
	 * read as a hazy volume rather than solid blocks; the faces fade toward the cloud's outline.
	 */
	MeshBuilder build(int centerX, int centerZ, int radius, boolean fancy, float opacity) {
		MeshBuilder out = new MeshBuilder();
		// Each surface of a fancy cloud gets a share of the opacity, so looking through the whole box adds up to it.
		float layer = fancy ? 1f - (float) Math.pow(1f - opacity, 1f / 3f) : opacity;
		for (int rz = -radius; rz <= radius; rz++) {
			for (int rx = -radius; rx <= radius; rx++) {
				if (rx * rx + rz * rz > radius * radius) {
					continue;
				}
				int cx = centerX + rx, cz = centerZ + rz;
				int c = cell(cx, cz);
				if (c == 0) {
					continue;
				}
				int rgb = c & 0xFFFFFF;
				int a00 = alpha(corner(cx, cz), layer) << 24 | rgb;
				int a10 = alpha(corner(cx + 1, cz), layer) << 24 | rgb;
				int a11 = alpha(corner(cx + 1, cz + 1), layer) << 24 | rgb;
				int a01 = alpha(corner(cx, cz + 1), layer) << 24 | rgb;
				float x0 = rx * CELL, x1 = x0 + CELL, z0 = rz * CELL, z1 = z0 + CELL;
				if (!fancy) {
					quad(out, 0, -1, 0, x0, 0, z0, a00, x1, 0, z0, a10, x1, 0, z1, a11, x0, 0, z1, a01);
					continue;
				}
				float y1 = THICKNESS;
				quad(out, 0, 1, 0, x0, y1, z0, a00, x0, y1, z1, a01, x1, y1, z1, a11, x1, y1, z0, a10);
				quad(out, 0, 1, 0, x0, y1 / 2, z0, a00, x0, y1 / 2, z1, a01, x1, y1 / 2, z1, a11, x1, y1 / 2, z0, a10);
				quad(out, 0, -1, 0, x0, 0, z0, a00, x1, 0, z0, a10, x1, 0, z1, a11, x0, 0, z1, a01);
				// Sides only on the outline, at the corners' (low) alpha, fading out toward the top.
				if (cell(cx, cz - 1) == 0) {
					quad(out, 0, 0, -1, x0, 0, z0, a00, x0, y1, z0, fade(a00), x1, y1, z0, fade(a10), x1, 0, z0, a10);
				}
				if (cell(cx, cz + 1) == 0) {
					quad(out, 0, 0, 1, x0, 0, z1, a01, x1, 0, z1, a11, x1, y1, z1, fade(a11), x0, y1, z1, fade(a01));
				}
				if (cell(cx - 1, cz) == 0) {
					quad(out, -1, 0, 0, x0, 0, z0, a00, x0, 0, z1, a01, x0, y1, z1, fade(a01), x0, y1, z0, fade(a00));
				}
				if (cell(cx + 1, cz) == 0) {
					quad(out, 1, 0, 0, x1, 0, z0, a10, x1, y1, z0, fade(a10), x1, y1, z1, fade(a11), x1, 0, z1, a11);
				}
			}
		}
		return out;
	}

	private static int fade(int argb) {
		return ((argb >>> 24) / 2) << 24 | argb & 0xFFFFFF;
	}

	private static void quad(MeshBuilder out, float nx, float ny, float nz, float ax, float ay, float az, int ca,
			float bx, float by, float bz, int cb, float cx, float cy, float cz, int cc, float dx, float dy, float dz, int cd) {
		out.vertex(ax, ay, az, nx, ny, nz, 0, 0, ca);
		out.vertex(bx, by, bz, nx, ny, nz, 1, 0, cb);
		out.vertex(cx, cy, cz, nx, ny, nz, 1, 1, cc);
		out.vertex(dx, dy, dz, nx, ny, nz, 0, 1, cd);
	}
}
