package rtxremix.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import rtxremix.RtxRemixMod;
import rtxremix.settings.RtxSettingsStore;

import java.util.Locale;
import java.util.function.Consumer;

/** Settings that belong to the mod rather than to the Remix runtime. */
public final class RtxIntegrationScreen extends OptionsSubScreen {
	public RtxIntegrationScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("rtxremix.integration"));
	}

	@Override
	protected void addContents() {
		RtxSettingsStore store = RtxRemixMod.settings();
		RtxSettingsStore.ModSettings s = store.mod();
		LinearLayout col = layout.addToContents(LinearLayout.vertical().spacing(4));
		col.defaultCellSetting().alignHorizontallyCenter();

		CycleButton<Boolean> enabled = CycleButton.onOffBuilder(s.enabled).create(0, 0, 310, 20,
				Component.translatable("rtxremix.enabled"), (b, v) -> { s.enabled = v; store.save(); });
		enabled.setTooltip(Tooltip.create(Component.translatable("rtxremix.enabled.tooltip")));
		col.addChild(enabled);

		col.addChild(CycleButton.onOffBuilder(s.vsync).create(0, 0, 310, 20,
				Component.translatable("rtxremix.vsync"), (b, v) -> { s.vsync = v; store.save(); }));

		col.addChild(new FloatSlider("rtxremix.sunIntensity", s.sunIntensity, 0, 30, v -> { s.sunIntensity = v; store.save(); }));
		FloatSlider tilt = new FloatSlider("rtxremix.sunPathRotation", s.sunPathRotation, 0, 90,
				v -> { s.sunPathRotation = v; store.save(); });
		tilt.setTooltip(Tooltip.create(Component.translatable("rtxremix.sunPathRotation.tooltip")));
		col.addChild(tilt);
		col.addChild(new FloatSlider("rtxremix.skyIntensity", s.skyIntensity, 0, 5, v -> { s.skyIntensity = v; store.save(); }));
		CycleButton<Boolean> clouds = CycleButton.onOffBuilder(s.clouds).create(0, 0, 310, 20,
				Component.translatable("rtxremix.clouds"), (b, v) -> { s.clouds = v; store.save(); });
		clouds.setTooltip(Tooltip.create(Component.translatable("rtxremix.clouds.tooltip")));
		col.addChild(clouds);
		col.addChild(new FloatSlider("rtxremix.cloudOpacity", s.cloudOpacity, 0.05f, 1,
				v -> { s.cloudOpacity = v; store.save(); }));
		col.addChild(new FloatSlider("rtxremix.blockLightIntensity", s.blockLightIntensity, 0, 10,
				v -> { s.blockLightIntensity = v; store.save(); }));
		FloatSlider emissive = new FloatSlider("rtxremix.emissiveIntensity", s.emissiveIntensity, 0, 40,
				v -> { s.emissiveIntensity = v; store.save(); });
		emissive.setTooltip(Tooltip.create(Component.translatable("rtxremix.emissiveIntensity.tooltip")));
		col.addChild(emissive);
		FloatSlider pom = new FloatSlider("rtxremix.pomDepth", s.pomDepth, 0, 1, v -> { s.pomDepth = v; store.save(); });
		pom.setTooltip(Tooltip.create(Component.translatable("rtxremix.pomDepth.tooltip")));
		col.addChild(pom);
		FloatSlider ao = new FloatSlider("rtxremix.pbrAoStrength", s.pbrAoStrength, 0, 1,
				v -> { s.pbrAoStrength = v; store.save(); });
		ao.setTooltip(Tooltip.create(Component.translatable("rtxremix.pbrAoStrength.tooltip")));
		col.addChild(ao);
		CycleButton<Boolean> sss = CycleButton.onOffBuilder(s.subsurfaceScattering).create(0, 0, 310, 20,
				Component.translatable("rtxremix.subsurfaceScattering"),
				(b, v) -> { s.subsurfaceScattering = v; store.save(); });
		sss.setTooltip(Tooltip.create(Component.translatable("rtxremix.subsurfaceScattering.tooltip")));
		col.addChild(sss);
		col.addChild(new FloatSlider("rtxremix.maxBlockLights", s.maxBlockLights, 0, 4096,
				v -> { s.maxBlockLights = Math.round(v); store.save(); }));

		EditBox runtime = new EditBox(font, 0, 0, 310, 20, Component.translatable("rtxremix.runtimePath"));
		runtime.setMaxLength(1024);
		runtime.setValue(s.runtimePath);
		runtime.setResponder(v -> { s.runtimePath = v; store.save(); });
		runtime.setTooltip(Tooltip.create(Component.translatable("rtxremix.runtimePath.tooltip")));
		col.addChild(runtime);
	}

	@Override
	protected void addOptions() {
	}

	private static final class FloatSlider extends AbstractSliderButton {
		private final String key;
		private final double min;
		private final double max;
		private final Consumer<Float> onChange;

		FloatSlider(String key, double value, double min, double max, Consumer<Float> onChange) {
			super(0, 0, 310, 20, Component.empty(), (value - min) / (max - min));
			this.key = key;
			this.min = min;
			this.max = max;
			this.onChange = onChange;
			updateMessage();
		}

		private double actual() {
			return min + value * (max - min);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(key, String.format(Locale.ROOT, "%.2f", actual())));
		}

		@Override
		protected void applyValue() {
			onChange.accept((float) actual());
		}
	}
}
