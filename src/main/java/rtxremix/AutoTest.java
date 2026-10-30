package rtxremix;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rtxremix.remix.RemixRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Development aid: runs a script of commands and screenshot requests once a world has loaded, for checking the
 * rendering without a person at the controls. Off unless {@code -Drtxremix.autotest=<script>} is given.
 *
 * <p>Script lines: {@code waitload} (until every compiled section has reached Remix), {@code wait <ticks>},
 * {@code cmd <command>}, {@code rtx <option> <value>}, {@code view first|back|front}, {@code shot <name>} and
 * {@code quit}. A shot writes
 * {@code <name>.req} next to the script and waits for an outside tool to capture the window into {@code <name>.png}.
 */
public final class AutoTest {
	private static final Logger LOG = LoggerFactory.getLogger("rtxremix");
	private static final int SHOT_TIMEOUT_TICKS = 600;

	private final Path dir;
	private final Deque<String> lines;
	private int waitTicks;
	private int settledTicks;
	private String pendingShot;
	private int ticks;

	private AutoTest(Path script) throws IOException {
		dir = script.toAbsolutePath().getParent();
		lines = new ArrayDeque<>(Files.readAllLines(script).stream().map(String::strip)
				.filter(l -> !l.isEmpty() && !l.startsWith("#")).toList());
	}

	public static void install() {
		String script = System.getProperty("rtxremix.autotest");
		if (script == null) {
			return;
		}
		try {
			AutoTest test = new AutoTest(Path.of(script));
			// The HUD would cover the scene; the test only wants the path-traced image.
			RtxRemixMod.settings().mod().drawHudThroughRemix = false;
			ClientTickEvents.END_CLIENT_TICK.register(test::tick);
			LOG.info("[autotest] running {}", script);
		} catch (IOException e) {
			LOG.error("[autotest] could not read {}", script, e);
		}
	}

	private void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || !RemixRenderer.active()) {
			return;
		}
		if (++ticks % 100 == 0) {
			Runtime rt = Runtime.getRuntime();
			LOG.info("[autotest] stats fps={} pending={} heapMiB={}/{}", mc.getFps(), RemixRenderer.pendingUploads(),
					(rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
		}
		if (pendingShot != null) {
			if (Files.exists(dir.resolve(pendingShot + ".png")) || --waitTicks <= 0) {
				LOG.info("[autotest] shot {} {}", pendingShot, waitTicks > 0 ? "captured" : "timed out");
				pendingShot = null;
				waitTicks = 0;
			}
			return;
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		String line = lines.poll();
		if (line == null) {
			return;
		}
		String[] parts = line.split(" ", 2);
		String arg = parts.length > 1 ? parts[1] : "";
		switch (parts[0]) {
			case "waitload" -> {
				// Sections keep arriving while chunks load; wait for a quiet stretch. Random ticks (grass spreading,
				// crops) recompile the odd section forever, so a few pending uploads still count as quiet.
				settledTicks = RemixRenderer.pendingUploads() <= 4 ? settledTicks + 1 : 0;
				if (settledTicks < 100) {
					lines.push(line);
				}
			}
			case "wait" -> waitTicks = Integer.parseInt(arg);
			case "cmd" -> mc.player.connection.sendCommand(arg);
			case "rtx" -> {
				String[] kv = arg.split(" ", 2);
				RemixRenderer.setOption(kv[0], kv[1]);
			}
			case "view" -> mc.options.setCameraType(switch (arg) {
				case "back" -> CameraType.THIRD_PERSON_BACK;
				case "front" -> CameraType.THIRD_PERSON_FRONT;
				default -> CameraType.FIRST_PERSON;
			});
			case "shot" -> {
				try {
					Files.deleteIfExists(dir.resolve(arg + ".png"));
					Files.writeString(dir.resolve(arg + ".req"), arg);
				} catch (IOException e) {
					LOG.error("[autotest] could not request shot {}", arg, e);
				}
				pendingShot = arg;
				waitTicks = SHOT_TIMEOUT_TICKS;
			}
			case "quit" -> mc.stop();
			default -> LOG.warn("[autotest] unknown line: {}", line);
		}
		LOG.info("[autotest] {}", line);
	}
}
