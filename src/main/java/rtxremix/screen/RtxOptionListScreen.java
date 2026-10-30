package rtxremix.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import rtxremix.settings.RtxOption;
import rtxremix.settings.RtxSettingsStore;

import java.util.List;
import java.util.Locale;

/** A scrollable page of RTX Remix options: one row per option with a typed control and a reset button. */
public final class RtxOptionListScreen extends OptionsSubScreen {
	private static final int CONTROL_WIDTH = 150;
	private static final int RESET_WIDTH = 20;

	private final List<RtxOption> options;
	private final RtxSettingsStore store;
	private OptionList list;
	private EditBox filter;

	public RtxOptionListScreen(Screen parent, Component title, List<RtxOption> options, RtxSettingsStore store) {
		super(parent, Minecraft.getInstance().options, title);
		this.options = options;
		this.store = store;
	}

	@Override
	protected void addTitle() {
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new net.minecraft.client.gui.components.StringWidget(title, font));
		filter = header.addChild(new EditBox(font, 200, 18, Component.translatable("rtxremix.search")));
		filter.setHint(Component.translatable("rtxremix.search"));
		filter.setResponder(q -> list.refill(q));
		layout.setHeaderHeight(48);
	}

	@Override
	protected void addContents() {
		list = layout.addToContents(new OptionList(minecraft));
		list.refill("");
	}

	@Override
	protected void addOptions() {
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("rtxremix.resetPage"), b -> {
			options.forEach(store::reset);
			list.refill(filter.getValue());
		}).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (list != null) {
			list.updateSize(width, layout);
		}
	}

	final class OptionList extends ContainerObjectSelectionList<Row> {
		OptionList(Minecraft mc) {
			super(mc, RtxOptionListScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), 24);
		}

		void refill(String query) {
			clearEntries();
			String q = query.trim().toLowerCase(Locale.ROOT);
			for (RtxOption o : options) {
				if (q.isEmpty() || o.matches(q)) {
					addEntry(new Row(o));
				}
			}
			setScrollAmount(0);
		}

		@Override
		public int getRowWidth() {
			return Math.min(width - 40, 480);
		}
	}

	final class Row extends ContainerObjectSelectionList.Entry<Row> {
		private final RtxOption option;
		private AbstractWidget control;
		private final Button reset;

		Row(RtxOption option) {
			this.option = option;
			this.reset = Button.builder(Component.literal("↺"), b -> {
				store.reset(option);
				rebuildControl();
			}).size(RESET_WIDTH, 20).build();
			reset.setTooltip(Tooltip.create(Component.translatable("rtxremix.reset", option.defaultText())));
			rebuildControl();
		}

		private void rebuildControl() {
			String value = store.get(option);
			control = switch (option.kind()) {
				case BOOL -> CycleButton.onOffBuilder("true".equalsIgnoreCase(value) || "1".equals(value))
						.displayOnlyValue()
						.create(0, 0, CONTROL_WIDTH, 20, Component.literal(option.shortName()),
								(btn, v) -> commit(v ? "True" : "False"));
				case INT, FLOAT -> option.bounded() && option.max() - option.min() <= 1e6 ? new Slider(value) : textBox(value);
				case TEXT -> textBox(value);
			};
			control.setTooltip(tooltip());
			reset.active = store.isOverridden(option);
		}

		private EditBox textBox(String value) {
			EditBox box = new EditBox(font, 0, 0, CONTROL_WIDTH, 20, Component.literal(option.key()));
			box.setMaxLength(4096);
			box.setValue(value);
			box.setResponder(text -> {
				String normalized = option.normalize(text);
				box.setTextColor(normalized == null ? 0xFFFF5555 : 0xFFE0E0E0);
				if (normalized != null && !normalized.equals(store.get(option))) {
					commit(normalized);
				}
			});
			return box;
		}

		private void commit(String value) {
			store.set(option, value);
			reset.active = store.isOverridden(option);
		}

		private Tooltip tooltip() {
			StringBuilder sb = new StringBuilder(option.key()).append("\n§7").append(option.type())
					.append(", default ").append(option.defaultText().isEmpty() ? "(empty)" : option.defaultText());
			if (option.bounded()) {
				sb.append(", range ").append(fmt(option.min())).append(" to ").append(fmt(option.max()));
			}
			if (!option.description().isEmpty()) {
				sb.append("\n§r").append(option.description());
			}
			return Tooltip.create(Component.literal(sb.toString()));
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			int right = getContentX() + getContentWidth();
			int y = getContentY() - 2;
			reset.setPosition(right - RESET_WIDTH, y);
			control.setPosition(right - RESET_WIDTH - 4 - CONTROL_WIDTH, y);
			int nameWidth = control.getX() - getContentX() - 6;
			String name = font.plainSubstrByWidth(option.shortName(), nameWidth);
			int color = store.isOverridden(option) ? 0xFFFFFF55 : 0xFFFFFFFF;
			graphics.text(font, name, getContentX(), getContentYMiddle() - 4, color);
			control.extractRenderState(graphics, mouseX, mouseY, a);
			reset.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(control, reset);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(control, reset);
		}

		/** Slider over [min, max]; ints snap to whole numbers. */
		private final class Slider extends AbstractSliderButton {
			Slider(String value) {
				super(0, 0, CONTROL_WIDTH, 20, Component.empty(), sliderPosition(option, value));
				updateMessage();
			}

			private double actual() {
				double v = option.min() + value * (option.max() - option.min());
				return option.kind() == RtxOption.Kind.INT ? Math.round(v) : v;
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.literal(fmt(actual())));
			}

			@Override
			protected void applyValue() {
				String normalized = option.normalize(option.kind() == RtxOption.Kind.INT
						? Long.toString((long) actual()) : String.format(Locale.ROOT, "%.4f", actual()));
				if (normalized != null && !normalized.equals(store.get(option))) {
					commit(normalized);
				}
			}
		}
	}

	private static double sliderPosition(RtxOption option, String value) {
		double v;
		try {
			v = Double.parseDouble(value);
		} catch (NumberFormatException e) {
			v = option.min();
		}
		return Math.clamp((v - option.min()) / (option.max() - option.min()), 0, 1);
	}

	private static String fmt(double d) {
		if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		return String.format(Locale.ROOT, "%.3f", d);
	}
}
