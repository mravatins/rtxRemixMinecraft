package rtxremix.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rtxremix.RtxRemixMod;

/** Remix takes over the window from Minecraft's Vulkan swapchain, so Vulkan is tried first whatever the option says. */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
	private void rtxremix$preferVulkan(CallbackInfoReturnable<GpuBackend[]> cir) {
		if (RtxRemixMod.settings().mod().enabled) {
			cir.setReturnValue(new GpuBackend[] {new VulkanBackend(), new GlBackend()});
		}
	}
}
