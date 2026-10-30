package rtxremix.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rtxremix.remix.DynamicCapture;
import rtxremix.remix.RemixRenderer;

/**
 * While Remix is active the world's frame graph (sky, terrain, entities, weather) is not executed:
 * the scene goes to Remix instead and the main target is left transparent for the HUD layer.
 * Section compilation, uploads and occlusion updates after it still run.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private void rtxremix$beginCapture(CallbackInfo ci) {
		DynamicCapture.begin();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private void rtxremix$endCapture(CallbackInfo ci) {
		DynamicCapture.end();
	}

	@WrapWithCondition(method = "render", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
	private boolean rtxremix$renderWorld(FrameGraphBuilder frame, GraphicsResourceAllocator allocator,
			FrameGraphBuilder.Inspector inspector, @Local(argsOnly = true) CameraRenderState camera) {
		RemixRenderer remix = RemixRenderer.get();
		if (remix == null) {
			return true;
		}
		remix.renderWorld(camera);
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		RenderSystem.getDevice().createCommandEncoder()
				.clearColorAndDepthTextures(main.getColorTexture(), new Vector4f(0, 0, 0, 0), main.getDepthTexture(), 0.0);
		return false;
	}
}
