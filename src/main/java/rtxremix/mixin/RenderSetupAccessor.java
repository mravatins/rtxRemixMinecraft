package rtxremix.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
	/** Values are {@code RenderSetup.TextureBinding}, which is package-private: see {@link TextureBindingAccessor}. */
	@Accessor("textures")
	Map<String, ?> rtxremix$textures();
}
