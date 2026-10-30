package rtxremix.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import rtxremix.remix.RemixRenderer;

/**
 * Holds the runtime's own Remix menu open. The screen has no widgets and draws no background, so the
 * mouse is free, the game is paused and clicks only reach the Remix menu. Esc closes both.
 */
public final class RemixMenuScreen extends Screen {
	private final @Nullable Screen parent;

	public RemixMenuScreen(@Nullable Screen parent) {
		super(Component.translatable("rtxremix.remixMenu.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		RemixRenderer.setRemixMenuOpen(true);
	}

	@Override
	public void removed() {
		RemixRenderer.setRemixMenuOpen(false);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}
}
