package rtxremix.mixin;

import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rtxremix.remix.DynamicCapture;

import java.util.List;

/** Particles are captured for Remix as Minecraft prepares them. */
@Mixin(QuadParticleFeatureRenderer.class)
abstract class QuadParticleFeatureRendererMixin {
	@Inject(method = "prepareGroup", at = @At("HEAD"))
	private void rtxremix$capture(FeatureFrameContext context, List<QuadParticleFeatureRenderer.Submit> submits,
			boolean strictlyOrdered, CallbackInfo ci) {
		for (QuadParticleFeatureRenderer.Submit submit : submits) {
			DynamicCapture.particles(submit);
		}
	}
}
