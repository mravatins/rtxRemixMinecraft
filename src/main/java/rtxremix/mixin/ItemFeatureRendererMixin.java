package rtxremix.mixin;

import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rtxremix.remix.DynamicCapture;

/** Items in the world (dropped, in item frames, held by mobs) are captured for Remix as their quads are prepared. */
@Mixin(ItemFeatureRenderer.class)
abstract class ItemFeatureRendererMixin {
	@Inject(method = "prepareMainSubmit", at = @At("HEAD"))
	private void rtxremix$capture(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
		DynamicCapture.item(submit);
	}
}
