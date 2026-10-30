package rtxremix.remix;

import java.util.Random;

/**
 * A tileable normal map of small wind-driven waves for water. Remix's animated water scrolls it and blends in a
 * second, blurrier sample moving the other way, which turns it into moving ripples.
 */
final class WaterNormals {
	static final int SIZE = 256;
	private static final int WAVES = 48;
	/** Root-mean-square slope of the surface; higher is choppier. */
	private static final double RMS_SLOPE = 0.16;

	private WaterNormals() {
	}

	/** @return RGBA8 octahedral normals (+Y up), {@link #SIZE} square, top row first */
	static byte[] generate() {
		Random random = new Random(0x77617465L);
		int[] kx = new int[WAVES], ky = new int[WAVES];
		double[] amp = new double[WAVES], phase = new double[WAVES];
		double slopeSq = 0;
		double wind = random.nextDouble() * Math.PI * 2;
		for (int i = 0; i < WAVES; i++) {
			// Whole cycles per tile in both axes keep the map seamless; most waves run roughly with the wind.
			double freq = 2 + Math.pow(random.nextDouble(), 1.5) * 22;
			double dir = wind + random.nextGaussian() * 0.8;
			kx[i] = (int) Math.round(Math.cos(dir) * freq);
			ky[i] = (int) Math.round(Math.sin(dir) * freq);
			if (kx[i] == 0 && ky[i] == 0) {
				kx[i] = 1;
			}
			double k = Math.hypot(kx[i], ky[i]);
			amp[i] = Math.pow(k, -1.6);
			phase[i] = random.nextDouble() * Math.PI * 2;
			double slope = amp[i] * 2 * Math.PI * k;
			slopeSq += slope * slope / 2;
		}
		double scale = RMS_SLOPE / Math.sqrt(slopeSq);

		byte[] out = new byte[SIZE * SIZE * 4];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				double u = (double) x / SIZE, v = (double) y / SIZE;
				double dx = 0, dy = 0;
				for (int i = 0; i < WAVES; i++) {
					double c = Math.cos(2 * Math.PI * (kx[i] * u + ky[i] * v) + phase[i]) * amp[i] * 2 * Math.PI;
					dx += c * kx[i];
					dy += c * ky[i];
				}
				// Normal of the height field; +Y up means against the image rows.
				double nx = -dx * scale, ny = dy * scale, nz = 1;
				double l1 = Math.abs(nx) + Math.abs(ny) + nz;
				int o = (y * SIZE + x) * 4;
				out[o] = (byte) unorm(nx / l1 * 0.5 + 0.5);
				out[o + 1] = (byte) unorm(ny / l1 * 0.5 + 0.5);
				out[o + 2] = 0;
				out[o + 3] = (byte) 0xFF;
			}
		}
		return out;
	}

	private static int unorm(double v) {
		return (int) Math.round(Math.clamp(v, 0.0, 1.0) * 255);
	}
}
