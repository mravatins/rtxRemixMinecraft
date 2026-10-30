package rtxremix.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The player's RTX Remix option overrides plus the mod's own settings, saved to
 * {@code config/rtxremix.json}. Overrides are pushed to the runtime with SetConfigVariable,
 * which sits above rtx.conf, so a value set here wins over the runtime's config files.
 */
public final class RtxSettingsStore {
	private static final Logger LOG = LoggerFactory.getLogger("rtxremix");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The mod's own settings (not RTX Remix options). */
	public static final class ModSettings {
		public boolean enabled = true;
		public String runtimePath = "rtx-remix-runtime";
		public boolean vsync = true;
		public float sunIntensity = 6f;
		/**
		 * Turn of the sun and moon path about the vertical axis, in degrees: where they rise and set moves off due east
		 * and west, so shadows do not run along block edges. Noon stays overhead.
		 */
		public float sunPathRotation = 30f;
		/** The sky's fill light; higher values tint everything with the pale horizon color. */
		public float skyIntensity = 0.5f;
		/** Sends Minecraft's clouds to Remix as geometry, which shades and casts shadows like terrain. */
		public boolean clouds = true;
		/** How much light a cloud stops, looking straight through it. */
		public float cloudOpacity = 0.7f;
		public float blockLightIntensity = 1f;
		public float emissiveIntensity = 4f;
		/** Parallax depth of LabPBR height maps, in blocks; 0 turns parallax occlusion mapping off. */
		public float pomDepth = 0.25f;
		public boolean subsurfaceScattering = true;
		/** How much of a LabPBR pack's ambient occlusion darkens the albedo; 0 turns it off. */
		public float pbrAoStrength = 0.35f;
		public int maxBlockLights = 512;
		public boolean drawHudThroughRemix = true;
	}

	private final Path file;
	private final Map<String, String> overrides = new TreeMap<>();
	private ModSettings mod = new ModSettings();
	private BiConsumer<String, String> sink = (k, v) -> { };

	public RtxSettingsStore(Path file) {
		this.file = file;
	}

	public void load() {
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("mod")) {
				mod = GSON.fromJson(root.get("mod"), ModSettings.class);
			}
			if (root.has("rtxOptions")) {
				root.getAsJsonObject("rtxOptions").entrySet()
						.forEach(e -> overrides.put(e.getKey(), e.getValue().getAsString()));
			}
		} catch (Exception e) {
			LOG.error("Could not read {}; using defaults", file, e);
		}
	}

	public void save() {
		JsonObject root = new JsonObject();
		root.add("mod", GSON.toJsonTree(mod));
		JsonObject opts = new JsonObject();
		overrides.forEach(opts::addProperty);
		root.add("rtxOptions", opts);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save {}", file, e);
		}
	}

	public ModSettings mod() {
		return mod;
	}

	/** Where values go when they change: the live runtime, once it is up. */
	public void setSink(BiConsumer<String, String> sink) {
		this.sink = sink;
	}

	/** Pushes the integration defaults, then every saved override, to the sink (on renderer startup). */
	public void applyAll() {
		RtxOptionCatalog.INTEGRATION_DEFAULTS.forEach((k, v) -> {
			if (!overrides.containsKey(k)) {
				sink.accept(k, v);
			}
		});
		overrides.forEach(sink);
	}

	public String get(RtxOption option) {
		return overrides.getOrDefault(option.key(), option.defaultText());
	}

	public boolean isOverridden(RtxOption option) {
		return overrides.containsKey(option.key());
	}

	public void set(RtxOption option, String value) {
		if (value.equals(option.defaultText())) {
			overrides.remove(option.key());
		} else {
			overrides.put(option.key(), value);
		}
		sink.accept(option.key(), value);
		save();
	}

	public void reset(RtxOption option) {
		overrides.remove(option.key());
		sink.accept(option.key(), option.defaultText());
		save();
	}

	public void resetAll(RtxOptionCatalog catalog) {
		Map<String, String> old = Map.copyOf(overrides);
		overrides.clear();
		old.keySet().forEach(k -> {
			RtxOption o = catalog.get(k);
			if (o != null) {
				sink.accept(k, o.defaultText());
			}
		});
		save();
	}

	public Map<String, String> overrides() {
		return Collections.unmodifiableMap(overrides);
	}
}
