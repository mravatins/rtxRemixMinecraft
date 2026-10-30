package rtxremix.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import rtxremix.RtxRemixMod;
import rtxremix.remix.RemixRenderer;
import rtxremix.settings.RtxOption;
import rtxremix.settings.RtxOptionCatalog;
import rtxremix.settings.RtxSettingsStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Landing page: one button per RTX Remix option group, plus search and the mod's own settings. */
public final class RtxSettingsScreen extends OptionsSubScreen {
	private CategoryList list;

	public RtxSettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("rtxremix.settings.title"));
	}

	@Override
	protected void addTitle() {
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(title, font));
		header.addChild(new StringWidget(Component.literal(RemixRenderer.statusLine()), font));
		layout.setHeaderHeight(40);
	}

	@Override
	protected void addContents() {
		list = layout.addToContents(new CategoryList(minecraft));
	}

	@Override
	protected void addOptions() {
	}

	@Override
	protected void addFooter() {
		RtxSettingsStore store = RtxRemixMod.settings();
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(6));
		footer.addChild(Button.builder(Component.translatable("rtxremix.integration"),
				b -> minecraft.gui.setScreen(new RtxIntegrationScreen(this))).width(110).build());
		footer.addChild(Button.builder(Component.translatable("rtxremix.openRemixMenu"),
				b -> minecraft.gui.setScreen(new RemixMenuScreen(this))).width(110).build());
		footer.addChild(Button.builder(Component.translatable("rtxremix.resetAll"), b -> {
			store.resetAll(RtxRemixMod.catalog());
			rebuildWidgets();
		}).width(80).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(80).build());
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (list != null) {
			list.updateSize(width, layout);
		}
	}

	private static Component categoryLabel(String category, int count) {
		String pretty = category.equals(RtxOptionCatalog.GENERAL) ? "General"
				: Character.toUpperCase(category.charAt(0)) + category.substring(1);
		return Component.literal(pretty + " (" + count + ")");
	}

	final class CategoryList extends ContainerObjectSelectionList<Row> {
		CategoryList(Minecraft mc) {
			super(mc, RtxSettingsScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), 24);
			RtxOptionCatalog catalog = RtxRemixMod.catalog();
			List<Button> buttons = new ArrayList<>();
			buttons.add(Button.builder(Component.translatable("rtxremix.allOptions", catalog.size()), b -> {
				List<RtxOption> all = new ArrayList<>();
				catalog.categories().values().forEach(all::addAll);
				open(Component.translatable("rtxremix.allOptions", catalog.size()), all);
			}).width(150).build());
			for (Map.Entry<String, List<RtxOption>> e : catalog.categories().entrySet()) {
				Component label = categoryLabel(e.getKey(), e.getValue().size());
				buttons.add(Button.builder(label, b -> open(label, e.getValue())).width(150).build());
			}
			for (int i = 0; i < buttons.size(); i += 2) {
				addEntry(new Row(buttons.get(i), i + 1 < buttons.size() ? buttons.get(i + 1) : null));
			}
		}

		private void open(Component title, List<RtxOption> options) {
			minecraft.gui.setScreen(new RtxOptionListScreen(RtxSettingsScreen.this, title, options, RtxRemixMod.settings()));
		}

		@Override
		public int getRowWidth() {
			return 310;
		}
	}

	static final class Row extends ContainerObjectSelectionList.Entry<Row> {
		private final List<Button> buttons;

		Row(Button left, Button right) {
			this.buttons = right == null ? List.of(left) : List.of(left, right);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			int x = getContentX();
			for (Button b : buttons) {
				b.setPosition(x, getContentY() - 2);
				b.extractRenderState(graphics, mouseX, mouseY, a);
				x += 160;
			}
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return buttons;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return buttons;
		}
	}
}
