package rtxremix;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import rtxremix.screen.RemixMenuScreen;
import rtxremix.screen.RtxSettingsScreen;
import rtxremix.settings.RtxOptionCatalog;
import rtxremix.settings.RtxSettingsStore;

import java.nio.file.Path;

public final class RtxRemixMod implements ClientModInitializer {
	private static RtxSettingsStore settings;
	private static RtxOptionCatalog catalog;

	/**
	 * The settings are read lazily: the graphics-backend mixin needs them before client entrypoints run.
	 */
	public static synchronized RtxSettingsStore settings() {
		if (settings == null) {
			settings = new RtxSettingsStore(FabricLoader.getInstance().getConfigDir().resolve("rtxremix.json"));
			settings.load();
		}
		return settings;
	}

	public static synchronized RtxOptionCatalog catalog() {
		if (catalog == null) {
			Path gameDir = FabricLoader.getInstance().getGameDir();
			catalog = RtxOptionCatalog.load(gameDir.resolve(settings().mod().runtimePath));
		}
		return catalog;
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rtxremix", "rtxremix"));
		KeyMapping openSettings = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.rtxremix.settings", InputConstants.KEY_F8, category));
		KeyMapping openRemixMenu = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.rtxremix.remixMenu", InputConstants.KEY_F9, category));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openSettings.consumeClick()) {
				mc.gui.setScreen(new RtxSettingsScreen(mc.gui.screen()));
			}
			while (openRemixMenu.consumeClick()) {
				mc.gui.setScreen(new RemixMenuScreen(mc.gui.screen()));
			}
		});
		AutoTest.install();
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (screen instanceof VideoSettingsScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("rtxremix.settings.button"),
						b -> mc.gui.setScreen(new RtxSettingsScreen(screen))).bounds(width - 110, 6, 100, 20).build());
			}
		});
	}
}
