package rtxremix.remix;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Holds the last stitched atlases until Remix starts (atlases load before the first frame). */
final class AtlasSnapshot {
	private static final Map<Identifier, BlockAtlas> last = new LinkedHashMap<>();

	private AtlasSnapshot() {
	}

	static synchronized void store(Identifier location, BlockAtlas atlas) {
		last.put(location, atlas);
	}

	static synchronized Map<Identifier, BlockAtlas> take() {
		Map<Identifier, BlockAtlas> s = Map.copyOf(last);
		last.clear();
		return s;
	}
}
