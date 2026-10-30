package rtxremix.mixin;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rtxremix.remix.DynamicCapture;

/** Entity models (mobs, players, their armor and capes) are captured for Remix as Minecraft prepares them. */
@Mixin(ModelFeatureRenderer.class)
abstract class ModelFeatureRendererMixin {
	@Inject(method = "prepareModel", at = @At("HEAD"))
	private void rtxremix$capture(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		DynamicCapture.model(submit);
	}
}
