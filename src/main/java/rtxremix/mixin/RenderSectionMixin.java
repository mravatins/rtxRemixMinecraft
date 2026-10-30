package rtxremix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rtxremix.remix.RemixRenderer;

/** A section slot is reset when it is unloaded or reassigned to another position. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
abstract class RenderSectionMixin {
	@Shadow
	private volatile long sectionNode;

	@Inject(method = "reset", at = @At("HEAD"))
	private void rtxremix$removed(CallbackInfo ci) {
		RemixRenderer.sectionRemoved(sectionNode);
	}
}
