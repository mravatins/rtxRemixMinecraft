package rtxremix.remix;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes uncompressed DDS files (DX10 header; R8G8B8A8_UNORM[_SRGB], or R8/R8G8_UNORM for linear data maps) with a
 * box-filtered mip chain, and R16G16B16A16_FLOAT for HDR radiance.
 * The 1.6.1 Remix API takes textures only as file paths, so the block atlas is handed over this way.
 */
public final class DdsWriter {
	private static final int DXGI_FORMAT_R16G16B16A16_FLOAT = 10;
	private static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;
	private static final int DXGI_FORMAT_R8G8B8A8_UNORM_SRGB = 29;
	private static final int DXGI_FORMAT_R8G8_UNORM = 49;
	private static final int DXGI_FORMAT_R8_UNORM = 61;

	/** Alpha cutoff that leaves alpha as it is. */
	private static final int KEEP_ALPHA = 256;

	private DdsWriter() {
	}

	/**
	 * @param rgba   width*height*4 bytes, top row first
	 * @param srgb   true for color data (albedo), false for linear data
	 * @param mips   maximum number of mip levels to write (at least 1)
	 */
	public static void write(Path file, byte[] rgba, int width, int height, boolean srgb, int mips) throws IOException {
		write(file, rgba, width, height, srgb, mips, false);
	}

	/**
	 * @param maxFilter true to build mips from the per-channel maximum instead of the average, as Remix's
	 *                  quadtree parallax occlusion mapping expects of height maps
	 */
	public static void write(Path file, byte[] rgba, int width, int height, boolean srgb, int mips, boolean maxFilter)
			throws IOException {
		write(file, rgba, width, height, srgb, mips, maxFilter, KEEP_ALPHA, 4);
	}

	/**
	 * Linear data texture keeping only the first {@code channels} (1: R8, 2: R8G8) of {@code rgba}: Remix reads
	 * roughness, metallic and height from red and octahedral normals from red and green, like the BC4 and BC5
	 * textures its own tools write. A quarter or half the memory of RGBA8.
	 */
	public static void writeLinear(Path file, byte[] rgba, int width, int height, int channels, int mips, boolean maxFilter)
			throws IOException {
		write(file, rgba, width, height, false, mips, maxFilter, KEEP_ALPHA, channels);
	}

	/** Color texture for materials without alpha: every mip level is fully opaque. */
	public static void writeOpaque(Path file, byte[] rgba, int width, int height, int mips) throws IOException {
		write(file, rgba, width, height, true, mips, false, -1, 4);
	}

	/**
	 * Color texture for alpha-tested materials: every mip level's alpha is snapped to 0 or 255 at {@code cutoff}.
	 * Remix treats a surface that passes the alpha test but has partial alpha as blended, which renders as
	 * stochastic, noisy see-through foliage.
	 */
	public static void writeCutout(Path file, byte[] rgba, int width, int height, int mips, int cutoff)
			throws IOException {
		write(file, rgba, width, height, true, mips, false, cutoff, 4);
	}

	private static void write(Path file, byte[] rgba, int width, int height, boolean srgb, int mips, boolean maxFilter,
			int cutoff, int channels) throws IOException {
		List<byte[]> levels = new ArrayList<>();
		levels.add(cutoff != KEEP_ALPHA ? snapAlpha(rgba.clone(), cutoff) : rgba);
		int w = width;
		int h = height;
		while (levels.size() < mips && (w > 1 || h > 1)) {
			byte[] prev = levels.getLast();
			int nw = Math.max(1, w / 2);
			int nh = Math.max(1, h / 2);
			byte[] next = maxFilter ? downsampleMax(prev, w, h, nw, nh) : downsample(prev, w, h, nw, nh);
			levels.add(cutoff != KEEP_ALPHA ? snapAlpha(next, cutoff) : next);
			w = nw;
			h = nh;
		}

		int dataSize = 0;
		for (byte[] l : levels) {
			dataSize += l.length / 4 * channels;
		}
		ByteBuffer buf = header(dataSize, width, height, width * channels, levels.size(), switch (channels) {
			case 1 -> DXGI_FORMAT_R8_UNORM;
			case 2 -> DXGI_FORMAT_R8G8_UNORM;
			default -> srgb ? DXGI_FORMAT_R8G8B8A8_UNORM_SRGB : DXGI_FORMAT_R8G8B8A8_UNORM;
		});
		for (byte[] l : levels) {
			if (channels == 4) {
				buf.put(l);
			} else {
				for (int i = 0; i < l.length; i += 4) {
					buf.put(l, i, channels);
				}
			}
		}
		save(file, buf);
	}

	/**
	 * Linear HDR color as R16G16B16A16_FLOAT, one mip level: radiance goes in as it is, with no range scaling or sRGB
	 * encoding, so dark gradients (a night sky) do not band.
	 *
	 * @param rgb width*height*3 floats, top row first
	 */
	public static void writeHdr(Path file, float[] rgb, int width, int height) throws IOException {
		int pixels = width * height;
		ByteBuffer buf = header(pixels * 8, width, height, width * 8, 1, DXGI_FORMAT_R16G16B16A16_FLOAT);
		short one = Float.floatToFloat16(1f);
		for (int i = 0; i < pixels; i++) {
			buf.putShort(Float.floatToFloat16(rgb[i * 3]));
			buf.putShort(Float.floatToFloat16(rgb[i * 3 + 1]));
			buf.putShort(Float.floatToFloat16(rgb[i * 3 + 2]));
			buf.putShort(one);
		}
		save(file, buf);
	}

	/** A buffer holding the DDS and DX10 headers, with room for {@code dataSize} bytes of texels after them. */
	private static ByteBuffer header(int dataSize, int width, int height, int pitch, int mips, int dxgiFormat) {
		ByteBuffer buf = ByteBuffer.allocate(4 + 124 + 20 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(0x20534444); // "DDS "
		// DDS_HEADER
		buf.putInt(124);
		buf.putInt(0x1 | 0x2 | 0x4 | 0x1000 | 0x8 | 0x20000); // CAPS|HEIGHT|WIDTH|PIXELFORMAT|PITCH|MIPMAPCOUNT
		buf.putInt(height);
		buf.putInt(width);
		buf.putInt(pitch);
		buf.putInt(0); // depth
		buf.putInt(mips);
		for (int i = 0; i < 11; i++) {
			buf.putInt(0);
		}
		// DDS_PIXELFORMAT: FourCC "DX10"
		buf.putInt(32);
		buf.putInt(0x4);
		buf.putInt(0x30315844);
		for (int i = 0; i < 5; i++) {
			buf.putInt(0);
		}
		buf.putInt(0x1000 | 0x400000 | 0x8); // TEXTURE|MIPMAP|COMPLEX
		buf.putInt(0);
		buf.putInt(0);
		buf.putInt(0);
		buf.putInt(0);
		// DDS_HEADER_DXT10
		buf.putInt(dxgiFormat);
		buf.putInt(3); // D3D10_RESOURCE_DIMENSION_TEXTURE2D
		buf.putInt(0);
		buf.putInt(1); // array size
		buf.putInt(0);
		return buf;
	}

	private static void save(Path file, ByteBuffer buf) throws IOException {
		buf.flip();
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			while (buf.hasRemaining()) {
				ch.write(buf);
			}
		}
	}

	/** Alpha-weighted 2x2 box filter, so cutout edges do not bleed dark fringes. */
	private static byte[] downsample(byte[] src, int w, int h, int nw, int nh) {
		byte[] dst = new byte[nw * nh * 4];
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				int r = 0, g = 0, b = 0, a = 0, n = 0;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int sx = Math.min(w - 1, x * 2 + dx);
						int sy = Math.min(h - 1, y * 2 + dy);
						int i = (sy * w + sx) * 4;
						int pa = src[i + 3] & 0xFF;
						r += (src[i] & 0xFF) * pa;
						g += (src[i + 1] & 0xFF) * pa;
						b += (src[i + 2] & 0xFF) * pa;
						a += pa;
						n++;
					}
				}
				int o = (y * nw + x) * 4;
				if (a > 0) {
					dst[o] = (byte) (r / a);
					dst[o + 1] = (byte) (g / a);
					dst[o + 2] = (byte) (b / a);
				}
				dst[o + 3] = (byte) (a / n);
			}
		}
		return dst;
	}

	private static byte[] snapAlpha(byte[] rgba, int cutoff) {
		for (int i = 3; i < rgba.length; i += 4) {
			rgba[i] = (rgba[i] & 0xFF) > cutoff ? (byte) 0xFF : 0;
		}
		return rgba;
	}

	/** Per-channel maximum of each 2x2 block. */
	private static byte[] downsampleMax(byte[] src, int w, int h, int nw, int nh) {
		byte[] dst = new byte[nw * nh * 4];
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				int o = (y * nw + x) * 4;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int i = (Math.min(h - 1, y * 2 + dy) * w + Math.min(w - 1, x * 2 + dx)) * 4;
						for (int c = 0; c < 4; c++) {
							dst[o + c] = (byte) Math.max(dst[o + c] & 0xFF, src[i + c] & 0xFF);
						}
					}
				}
			}
		}
		return dst;
	}
}
