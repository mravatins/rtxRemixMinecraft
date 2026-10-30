package rtxremix.mixin;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rtxremix.remix.RemixException;
import rtxremix.remix.RemixRenderer;

import java.util.Optional;

/**
 * Hands the window to Remix: once it starts, Minecraft's swapchain is gone, "acquire" is a no-op,
 * the blit becomes a read-back of the HUD layer and "present" presents through Remix's D3D9 device.
 */
@Mixin(FrontendGpuSurface.class)
abstract class FrontendGpuSurfaceMixin {
	@Shadow
	@Final
	private GpuSurfaceBackend backend;
	@Shadow
	private boolean hasImageAcquired;
	@Shadow
	private boolean hasBlittedTexture;
	@Shadow
	private Optional<GpuSurface.Configuration> currentConfiguration;

	@Inject(method = "configure", at = @At("HEAD"), cancellable = true)
	private void rtxremix$configure(GpuSurface.Configuration config, CallbackInfo ci) {
		if (RemixRenderer.active()) {
			currentConfiguration = Optional.of(config);
			ci.cancel();
		}
	}

	@Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
	private void rtxremix$acquire(CallbackInfo ci) {
		RemixRenderer.ensureStarted(() -> {
			if (!((Object) backend instanceof VulkanGpuSurfaceAccessor vk)) {
				throw new RemixException("Minecraft is not running on its Vulkan backend ("
						+ backend.getClass().getSimpleName() + ")");
			}
			vk.rtxremix$destroySwapchain();
		});
		if (RemixRenderer.active()) {
			hasImageAcquired = true;
			hasBlittedTexture = false;
			ci.cancel();
		}
	}

	@Inject(method = "isSuboptimal", at = @At("HEAD"), cancellable = true)
	private void rtxremix$isSuboptimal(CallbackInfoReturnable<Boolean> cir) {
		if (RemixRenderer.active()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void rtxremix$blit(CommandEncoder encoder, GpuTextureView view, CallbackInfo ci) {
		if (RemixRenderer.active() && hasImageAcquired) {
			RemixRenderer.get().captureUi(encoder, view);
			hasBlittedTexture = true;
			ci.cancel();
		}
	}

	@Inject(method = "present", at = @At("HEAD"), cancellable = true)
	private void rtxremix$present(CallbackInfo ci) {
		if (RemixRenderer.active() && hasImageAcquired) {
			RemixRenderer.get().presentFrame();
			hasImageAcquired = false;
			ci.cancel();
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void rtxremix$close(CallbackInfo ci) {
		RemixRenderer.shutdown();
	}
}
