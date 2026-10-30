package rtxremix.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rtxremix.RtxRemixMod;
import rtxremix.remix.RemixRenderer;
import rtxremix.remix.SectionCapture;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
	/** Remix computes its own occlusion; baked smooth lighting would darken vertex colors twice. */
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static boolean rtxremix$noAmbientOcclusion(boolean ambientOcclusion) {
		return ambientOcclusion && !RtxRemixMod.settings().mod().enabled;
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void rtxremix$capture(SectionPos pos, RenderSectionRegion region, VertexSorting sorting,
			SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (RemixRenderer.active()) {
			try {
				RemixRenderer.sectionCompiled(SectionCapture.capture(pos, cir.getReturnValue().renderedLayers, region));
			} catch (RuntimeException e) {
				// Minecraft drops a compile that throws without a word; the section would vanish from both renderers.
				LoggerFactory.getLogger("rtxremix").error("Could not capture section {}", pos, e);
			}
		}
	}
}
