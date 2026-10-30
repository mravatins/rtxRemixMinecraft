package rtxremix.remix;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.MoonPhase;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Bakes the overworld sky the way Minecraft's sky renderer draws it into a lat-long texture for Remix's dome light:
 * the sky color fading to the fog color at the horizon, the sunrise/sunset glow, the sun, the moon phase and the
 * stars. Remix's distant lights only light the scene, so without this the sun and moon are not visible.
 *
 * <p>Texels hold linear radiance relative to the sky color, as half floats. Remix maps a light-space direction to {@code u = 0.5 + atan2(x, y) / 2pi, v = acos(z) / pi}: +Z is the top
 * row, and azimuth is measured from +Y toward +X. {@link #SKY_TO_WORLD} turns light +Z to world +Y.
 *
 * <p>The sun, moon and stars follow Minecraft's path through the zenith, turned about the vertical axis (see
 * {@link #celestialDir}) so they rise and set off the east-west line and shadows do not run along block edges.
 */
final class SkyDome {
	private static final Logger LOG = LoggerFactory.getLogger("rtxremix");
	/** About 0.18 degrees per texel: stars stay points and the sun and moon keep their pixel art. */
	static final int WIDTH = 2048;
	static final int HEIGHT = 1024;
	/** Dome light-to-world rotation (row-major 3x4): light +Z (the texture's top row) to world +Y. */
	static final float[] SKY_TO_WORLD = {1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0};
	/** Sky radiance below the horizon, relative to the sky above it: the ground beyond the loaded chunks. */
	private static final float GROUND_SKY = 0.08f;
	/** Elevation where the horizon's fog color has fully given way to the sky color. */
	private static final float HORIZON_FADE = (float) Math.toRadians(35);
	/** Half-widths of the sky renderer's quads at distance 100: their angular size. */
	private static final float SUN_HALF = 30f / 100f;
	private static final float MOON_HALF = 20f / 100f;
	private static final float SUN_RADIANCE = 4f;
	private static final float MOON_RADIANCE = 1.2f;
	private static final float STAR_RADIANCE = 0.6f;

	/** Everything the bake reads; {@link #differs} decides when the sky has changed enough to bake again. */
	record State(float tilt, float sunAngle, float moonAngle, float starAngle, float starBrightness, MoonPhase moonPhase,
			float rainBrightness, Vector3fc skyColor, Vector3fc fogColor, Vector4fc sunriseColor) {
		boolean differs(State o) {
			float angle = (float) Math.toRadians(0.5);
			return o == null || moonPhase != o.moonPhase || tilt != o.tilt
					|| Math.abs(Mth.wrapDegrees(Math.toDegrees(sunAngle - o.sunAngle))) > Math.toDegrees(angle)
					|| Math.abs(Mth.wrapDegrees(Math.toDegrees(moonAngle - o.moonAngle))) > Math.toDegrees(angle)
					|| Math.abs(Mth.wrapDegrees(Math.toDegrees(starAngle - o.starAngle))) > Math.toDegrees(angle)
					|| Math.abs(starBrightness - o.starBrightness) > 0.02f
					|| Math.abs(rainBrightness - o.rainBrightness) > 0.02f
					|| colorDiffers(skyColor, o.skyColor) || colorDiffers(fogColor, o.fogColor)
					|| colorDiffers(sunriseColor, o.sunriseColor);
		}

		private static boolean colorDiffers(Vector3fc a, Vector3fc b) {
			return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()) > 0.012f;
		}

		private static boolean colorDiffers(Vector4fc a, Vector4fc b) {
			return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z())
					+ Math.abs(a.w() - b.w()) > 0.012f;
		}
	}

	private record Image(int[] argb, int width, int height) {
	}

	private record Star(Vector3f dir) {
	}

	private Image sun;
	private final Image[] moon = new Image[MoonPhase.COUNT];
	private boolean loaded;
	private final List<Star> stars = buildStars();
	/** World direction of every texel, row by row. */
	private final float[] dirs = new float[WIDTH * HEIGHT * 3];

	SkyDome() {
		for (int y = 0; y < HEIGHT; y++) {
			double theta = (y + 0.5) * Math.PI / HEIGHT;
			for (int x = 0; x < WIDTH; x++) {
				double phi = ((x + 0.5) / WIDTH - 0.5) * 2 * Math.PI;
				float lx = (float) (Math.sin(theta) * Math.sin(phi));
				float ly = (float) (Math.sin(theta) * Math.cos(phi));
				float lz = (float) Math.cos(theta);
				int o = (y * WIDTH + x) * 3;
				// SKY_TO_WORLD: world = (lx, lz, -ly)
				dirs[o] = lx;
				dirs[o + 1] = lz;
				dirs[o + 2] = -ly;
			}
		}
	}

	/** Texel holding a world direction; the inverse of the mapping in the constructor. */
	private static int texel(float wx, float wy, float wz) {
		float lx = wx, ly = -wz, lz = wy;
		double phi = Math.atan2(lx, ly);
		double theta = Math.acos(Math.clamp(lz, -1f, 1f));
		int x = Math.floorMod((int) Math.floor((phi / (2 * Math.PI) + 0.5) * WIDTH), WIDTH);
		int y = Math.min(HEIGHT - 1, (int) Math.floor(theta / Math.PI * HEIGHT));
		return y * WIDTH + x;
	}

	/** Resource packs changed: read the sun and moon textures again on the next bake. */
	void reloadTextures() {
		loaded = false;
	}

	private void loadTextures() {
		loaded = true;
		sun = load("textures/environment/celestial/sun.png");
		for (MoonPhase phase : MoonPhase.values()) {
			moon[phase.index()] = load("textures/environment/celestial/moon/" + phase.getSerializedName() + ".png");
		}
	}

	private static Image load(String path) {
		Identifier id = Identifier.withDefaultNamespace(path);
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(id); NativeImage img = NativeImage.read(in)) {
			int w = img.getWidth(), h = img.getHeight();
			int[] px = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					px[y * w + x] = img.getPixel(x, y);
				}
			}
			return new Image(px, w, h);
		} catch (Exception e) {
			LOG.warn("Could not read {} for the sky", id, e);
			return null;
		}
	}

	/** The same stars as Minecraft's sky renderer (same seed and distribution), as directions before rotation. */
	private static List<Star> buildStars() {
		RandomSource random = RandomSource.create(10842L);
		List<Star> out = new ArrayList<>();
		for (int i = 0; i < 1500; i++) {
			float x = random.nextFloat() * 2 - 1;
			float y = random.nextFloat() * 2 - 1;
			float z = random.nextFloat() * 2 - 1;
			random.nextFloat(); // size
			float lengthSq = x * x + y * y + z * z;
			if (lengthSq > 0.010000001f && lengthSq < 1f) {
				out.add(new Star(new Vector3f(x, y, z).normalize()));
				random.nextDouble(); // rotation
			}
		}
		return out;
	}

	/**
	 * Sun or moon direction: the sky renderer's rotation (-90 degrees about Y, then the angle about X, from +Y),
	 * which runs east (+X) through the zenith to west, then turned by {@code tilt} radians about the vertical axis,
	 * moving sunrise from east toward north and sunset from west toward south. Noon stays straight overhead.
	 */
	static Vector3f celestialDir(float angle, float tilt) {
		return tilt(new Vector3f((float) -Math.sin(angle), (float) Math.cos(angle), 0), tilt);
	}

	/** Rotates a direction about the vertical (Y) axis, taking east (+X) toward north (-Z). */
	static Vector3f tilt(Vector3f v, float tilt) {
		float c = (float) Math.cos(tilt), sn = (float) Math.sin(tilt);
		float x = v.x * c + v.z * sn, z = -v.x * sn + v.z * c;
		return v.set(x, v.y, z);
	}

	/** Reads the sun and moon textures if needed. Render thread; call before {@link #bake}. */
	void prepare() {
		if (!loaded) {
			loadTextures();
		}
	}

	/** Linear RGB radiance, top row first. Safe off the render thread once {@link #prepare} has run. */
	float[] bake(State s) {
		float[] rad = new float[WIDTH * HEIGHT * 3];
		Vector3fc sky = s.skyColor(), fog = s.fogColor();
		Vector4fc glow = s.sunriseColor();
		float glowAlpha = glow.w();
		// The sunrise fan sits on the horizon below the sun, on whichever side the sun is.
		Vector3f glowDir = tilt(new Vector3f(Math.sin(s.sunAngle()) < 0 ? 1f : -1f, 0, 0), s.tilt());
		float glowHeight = Math.max((float) Math.atan2(40 * glowAlpha, 120), 0.01f);

		float tiltAngle = s.tilt();
		Vector3f sunDir = celestialDir(s.sunAngle(), tiltAngle);
		Vector3f moonDir = celestialDir(s.moonAngle(), tiltAngle);
		// Quad axes: local X turns to world +Z, local Z to (-cos, -sin, 0); both tilted with the path.
		Vector3f sunU = tilt(new Vector3f(0, 0, 1), tiltAngle);
		Vector3f sunV = tilt(new Vector3f((float) -Math.cos(s.sunAngle()), (float) -Math.sin(s.sunAngle()), 0), tiltAngle);
		Vector3f moonV = tilt(new Vector3f((float) -Math.cos(s.moonAngle()), (float) -Math.sin(s.moonAngle()), 0), tiltAngle);
		Image moonImg = moon[s.moonPhase().index()];

		for (int i = 0; i < WIDTH * HEIGHT; i++) {
			float dx = dirs[i * 3], dy = dirs[i * 3 + 1], dz = dirs[i * 3 + 2];
			float elevation = (float) Math.asin(Math.clamp(dy, -1f, 1f));
			// Sky disc fading into the fog color at the horizon, mixed in sRGB as Minecraft blends it.
			float t = smooth(elevation, 0f, HORIZON_FADE);
			float r = Mth.lerp(t, fog.x(), sky.x()), g = Mth.lerp(t, fog.y(), sky.y()), b = Mth.lerp(t, fog.z(), sky.z());
			if (glowAlpha > 0.001f && elevation > -0.05f) {
				float azimuth = (float) Math.acos(Math.clamp((glowDir.x * dx + glowDir.z * dz)
						/ Math.max(1e-4f, (float) Math.hypot(dx, dz)), -1f, 1f));
				float e = Math.max(0f, elevation);
				float d = (float) Math.sqrt(sq(azimuth / (float) (Math.PI / 2)) + sq(e / glowHeight));
				float w = glowAlpha * Math.max(0f, 1f - d);
				r = Mth.lerp(w, r, glow.x());
				g = Mth.lerp(w, g, glow.y());
				b = Mth.lerp(w, b, glow.z());
			}
			r = srgbToLinear(r);
			g = srgbToLinear(g);
			b = srgbToLinear(b);
			if (elevation < 0) {
				// Fades to the ground over the first 10 degrees so the horizon has no hard edge.
				float k = Math.min(1f, -elevation / (float) Math.toRadians(10));
				float v = 1f + (GROUND_SKY - 1f) * k * k * (3 - 2 * k);
				r *= v;
				g *= v;
				b *= v;
			}
			rad[i * 3] = r;
			rad[i * 3 + 1] = g;
			rad[i * 3 + 2] = b;
			if (elevation > -0.2f) {
				addCelestial(rad, i, dx, dy, dz, sunDir, sunU, sunV, SUN_HALF, sun, false,
						SUN_RADIANCE * s.rainBrightness());
				addCelestial(rad, i, dx, dy, dz, moonDir, sunU, moonV, MOON_HALF, moonImg, true,
						MOON_RADIANCE * s.rainBrightness());
			}
		}

		if (s.starBrightness() > 0.001f) {
			float k = STAR_RADIANCE * s.starBrightness();
			float c = (float) Math.cos(s.starAngle()), sn = (float) Math.sin(s.starAngle());
			for (Star star : stars) {
				// Same rotation as the sun: about X by the star angle, then -90 degrees about Y.
				Vector3f p = star.dir();
				float ry = p.y * c - p.z * sn, rz = p.y * sn + p.z * c;
				Vector3f w = tilt(new Vector3f(-rz, ry, p.x), tiltAngle);
				if (w.y > 0) {
					int i = texel(w.x, w.y, w.z);
					rad[i * 3] += k;
					rad[i * 3 + 1] += k;
					rad[i * 3 + 2] += k;
				}
			}
		}
		return rad;
	}

	/** Adds a sun or moon quad drawn with additive blending, as the sky renderer does. */
	private static void addCelestial(float[] rad, int i, float dx, float dy, float dz, Vector3f n, Vector3f u, Vector3f v,
			float half, Image img, boolean mirrored, float radiance) {
		if (img == null || radiance <= 0) {
			return;
		}
		float t = dx * n.x + dy * n.y + dz * n.z;
		if (t <= 0.5f) {
			return;
		}
		float lu = (dx * u.x + dy * u.y + dz * u.z) / t / half;
		float lv = (dx * v.x + dy * v.y + dz * v.z) / t / half;
		if (lu < -1 || lu > 1 || lv < -1 || lv > 1) {
			return;
		}
		float s = (lu + 1) / 2, q = (lv + 1) / 2;
		if (mirrored) {
			s = 1 - s;
			q = 1 - q;
		}
		int x = Math.min(img.width() - 1, (int) (s * img.width()));
		int y = Math.min(img.height() - 1, (int) (q * img.height()));
		int argb = img.argb()[y * img.width() + x];
		float a = (argb >>> 24) / 255f * radiance;
		rad[i * 3] += srgbToLinear((argb >> 16 & 0xFF) / 255f) * a;
		rad[i * 3 + 1] += srgbToLinear((argb >> 8 & 0xFF) / 255f) * a;
		rad[i * 3 + 2] += srgbToLinear((argb & 0xFF) / 255f) * a;
	}

	static float srgbToLinear(float c) {
		return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
	}

	private static float smooth(float v, float lo, float hi) {
		float t = Math.clamp((v - lo) / (hi - lo), 0f, 1f);
		return t * t * (3 - 2 * t);
	}

	private static float sq(float v) {
		return v * v;
	}
}
