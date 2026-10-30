package rtxremix.settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every RTX Remix option, parsed from RtxOptions.md.
 *
 * <p>The file bundled in the mod comes from the dxvk-remix remix-1.6.1 tag. A newer runtime documents
 * its own options: put its RtxOptions.md next to d3d9.dll and that copy is used instead.
 */
public final class RtxOptionCatalog {
	public static final String GENERAL = "general";
	private static final String BUNDLED = "/assets/rtxremix/RtxOptions.md";

	/**
	 * Options whose runtime default does not suit Minecraft; they replace the documented default, so the
	 * settings screen and "reset" use them too. Grass, leaves, vines and water are greyscale textures
	 * colored only by the biome tint in the vertex color, which Remix by default applies at 60% and
	 * rescales as if it were baked lighting.
	 */
	public static final Map<String, String> INTEGRATION_DEFAULTS = Map.of(
			"rtx.vertexColorStrength", "1",
			"rtx.vertexColorIsBakedLighting", "False");

	private final Map<String, RtxOption> byKey;
	private final Map<String, List<RtxOption>> byCategory;

	private RtxOptionCatalog(List<RtxOption> options) {
		Map<String, RtxOption> keys = new LinkedHashMap<>();
		Map<String, List<RtxOption>> categories = new TreeMap<>((a, b) -> {
			if (a.equals(b)) return 0;
			if (a.equals(GENERAL)) return -1;
			if (b.equals(GENERAL)) return 1;
			return a.compareToIgnoreCase(b);
		});
		for (RtxOption o : options) {
			keys.put(o.key(), o);
			categories.computeIfAbsent(o.category(), c -> new ArrayList<>()).add(o);
		}
		this.byKey = Collections.unmodifiableMap(keys);
		this.byCategory = Collections.unmodifiableMap(categories);
	}

	public static RtxOptionCatalog load(Path runtimeDir) {
		Path local = runtimeDir == null ? null : runtimeDir.resolve("RtxOptions.md");
		try {
			if (local != null && Files.isRegularFile(local)) {
				try (InputStream in = Files.newInputStream(local)) {
					return parse(in);
				}
			}
			try (InputStream in = RtxOptionCatalog.class.getResourceAsStream(BUNDLED)) {
				if (in == null) {
					throw new IOException("missing " + BUNDLED);
				}
				return parse(in);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read RtxOptions.md", e);
		}
	}

	static RtxOptionCatalog parse(InputStream in) throws IOException {
		List<RtxOption> out = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (!line.startsWith("|") || line.startsWith("| :") || line.startsWith("| RTX Option")) {
					continue;
				}
				List<String> cells = splitRow(line);
				if (cells.size() < 6 || !cells.get(0).contains(".")) {
					continue;
				}
				String key = cells.get(0);
				String defaultText = INTEGRATION_DEFAULTS.getOrDefault(key, unescape(cells.get(2)));
				out.add(new RtxOption(key, cells.get(1), defaultText, number(cells.get(3)), number(cells.get(4)),
						unescape(cells.get(5)).replace("<br>", "\n").trim(), categoryOf(key)));
			}
		}
		return new RtxOptionCatalog(out);
	}

	/** Splits a markdown table row on unescaped pipes. */
	private static List<String> splitRow(String line) {
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		for (int i = 1; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\\' && i + 1 < line.length()) {
				cell.append(c).append(line.charAt(++i));
			} else if (c == '|') {
				cells.add(cell.toString().trim());
				cell.setLength(0);
			} else {
				cell.append(c);
			}
		}
		return cells;
	}

	private static String unescape(String s) {
		return s.replaceAll("\\\\(.)", "$1");
	}

	private static double number(String s) {
		if (s.isBlank()) {
			return Double.NaN;
		}
		try {
			return Double.parseDouble(unescape(s));
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	/** {@code rtx.tonemap.exposureBias} belongs to "tonemap"; {@code rtx.enableRaytracing} to "general". */
	private static String categoryOf(String key) {
		String[] parts = key.split("\\.");
		if (parts.length >= 3 && parts[0].equals("rtx")) {
			return parts[1];
		}
		if (parts.length >= 2 && !parts[0].equals("rtx")) {
			return parts[0];
		}
		return GENERAL;
	}

	public RtxOption get(String key) {
		return byKey.get(key);
	}

	public Map<String, List<RtxOption>> categories() {
		return byCategory;
	}

	public int size() {
		return byKey.size();
	}

	public List<RtxOption> search(String query) {
		List<RtxOption> hits = new ArrayList<>();
		for (RtxOption o : byKey.values()) {
			if (o.matches(query)) {
				hits.add(o);
			}
		}
		return hits;
	}
}
