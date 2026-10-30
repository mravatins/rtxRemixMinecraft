package rtxremix.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rtxremix.remix.RemixRenderer;

/**
 * Minecraft darkens block and fluid faces by direction (sides 0.6-0.8, bottoms 0.5, or the Nether's values) and
 * bakes that into the vertex color. Remix lights faces from the sun and sky itself, so sections compiled for it
 * get no directional shade.
 */
@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionMixin {
	private static final CardinalLighting RTXREMIX$UNSHADED = new CardinalLighting(1f, 1f, 1f, 1f, 1f, 1f);

	@Inject(method = "cardinalLighting", at = @At("HEAD"), cancellable = true)
	private void rtxremix$unshaded(CallbackInfoReturnable<CardinalLighting> cir) {
		if (RemixRenderer.active()) {
			cir.setReturnValue(RTXREMIX$UNSHADED);
		}
	}
}
