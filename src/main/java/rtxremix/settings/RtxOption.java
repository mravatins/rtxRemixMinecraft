package rtxremix.settings;

import java.util.Locale;

/**
 * One RTX Remix option as documented in the runtime's RtxOptions.md.
 *
 * @param key         full option name, e.g. {@code rtx.tonemap.exposureBias}
 * @param type        type column from RtxOptions.md (bool, int, float, float3, hash set, ...)
 * @param defaultText default value as the runtime documents it
 * @param min         lower bound, or NaN when unbounded
 * @param max         upper bound, or NaN when unbounded
 */
public record RtxOption(String key, String type, String defaultText, double min, double max, String description,
		String category) {

	public enum Kind { BOOL, INT, FLOAT, TEXT }

	public Kind kind() {
		return switch (type) {
			case "bool" -> Kind.BOOL;
			case "int" -> Kind.INT;
			case "float" -> Kind.FLOAT;
			default -> Kind.TEXT;
		};
	}

	public boolean bounded() {
		return !Double.isNaN(min) && !Double.isNaN(max) && max > min;
	}

	/** Short name shown in the list: the key without its {@code rtx.<category>.} prefix. */
	public String shortName() {
		String prefix = category.equals(RtxOptionCatalog.GENERAL) ? "rtx." : "rtx." + category + ".";
		return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
	}

	public boolean matches(String query) {
		String q = query.toLowerCase(Locale.ROOT);
		return key.toLowerCase(Locale.ROOT).contains(q) || description.toLowerCase(Locale.ROOT).contains(q);
	}

	/** Normalizes user input to the text form rtx.conf uses; returns null when it is not valid for this type. */
	public String normalize(String input) {
		String v = input.trim();
		try {
			return switch (kind()) {
				case BOOL -> switch (v.toLowerCase(Locale.ROOT)) {
					case "true", "1", "on", "yes" -> "True";
					case "false", "0", "off", "no" -> "False";
					default -> null;
				};
				case INT -> Long.toString(Long.parseLong(v));
				case FLOAT -> {
					double d = Double.parseDouble(v);
					yield Double.isFinite(d) ? stripFloat(d) : null;
				}
				case TEXT -> v;
			};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static String stripFloat(double d) {
		String s = Float.toString((float) d);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}
}
