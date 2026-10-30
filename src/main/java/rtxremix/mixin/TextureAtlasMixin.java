package rtxremix.mixin;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rtxremix.remix.BlockAtlas;
import rtxremix.remix.RemixRenderer;

import java.util.List;

/**
 * Rebuilds the block, item and particle atlases (and any LabPBR maps) on the CPU so they can be handed to Remix.
 * Items and particles use the item and particle atlases, and dropped blocks the block atlas.
 */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
	@Shadow
	private List<TextureAtlasSprite> sprites;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	@Final
	private Identifier location;

	@Inject(method = "upload", at = @At("TAIL"))
	private void rtxremix$captureAtlas(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(location) || TextureAtlas.LOCATION_ITEMS.equals(location)
				|| TextureAtlas.LOCATION_PARTICLES.equals(location)) {
			RemixRenderer.atlasChanged(location, BlockAtlas.build(sprites, width, height));
		}
	}
}
