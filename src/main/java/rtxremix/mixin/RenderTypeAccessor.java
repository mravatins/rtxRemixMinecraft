package rtxremix.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.class)
public interface RenderTypeAccessor {
	@Accessor("state")
	RenderSetup rtxremix$state();

	@Accessor("name")
	String rtxremix$name();
}
