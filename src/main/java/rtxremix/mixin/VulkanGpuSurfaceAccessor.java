package rtxremix.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanGpuSurface.class)
public interface VulkanGpuSurfaceAccessor {
	@Invoker("destroySwapchain")
	void rtxremix$destroySwapchain();
}
