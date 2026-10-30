package rtxremix.remix;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reads a sprite's animation (frame index and time in ticks, in play order). SpriteContents keeps it in a private
 * inner class with a private record per frame, which mixin accessors cannot name, so this uses reflection.
 */
final class AnimationFrames {
	private static final Logger LOG = LoggerFactory.getLogger("rtxremix");
	private static Field animatedTexture;
	private static Field frames;
	private static Method index;
	private static Method time;
	private static boolean broken;

	private AnimationFrames() {
	}

	/** @return {index, ticks} per frame, or {@code null} when the sprite does not animate */
	static synchronized int[][] of(SpriteContents contents) {
		if (broken) {
			return null;
		}
		try {
			if (animatedTexture == null) {
				animatedTexture = SpriteContents.class.getDeclaredField("animatedTexture");
				animatedTexture.setAccessible(true);
			}
			Object animation = animatedTexture.get(contents);
			if (animation == null) {
				return null;
			}
			if (frames == null) {
				frames = animation.getClass().getDeclaredField("frames");
				frames.setAccessible(true);
			}
			List<?> list = (List<?>) frames.get(animation);
			if (list.isEmpty()) {
				return null;
			}
			if (index == null) {
				Class<?> frameInfo = list.getFirst().getClass();
				index = frameInfo.getDeclaredMethod("index");
				time = frameInfo.getDeclaredMethod("time");
				index.setAccessible(true);
				time.setAccessible(true);
			}
			int[][] out = new int[list.size()][];
			for (int i = 0; i < out.length; i++) {
				Object f = list.get(i);
				out[i] = new int[] {(int) index.invoke(f), Math.max(1, (int) time.invoke(f))};
			}
			return out;
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			LOG.warn("Could not read sprite animations; animated textures will show their first frame", e);
			return null;
		}
	}
}
