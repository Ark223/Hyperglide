package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.Navigation;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    /**
     * Renders the Navigation map.
     *
     * @param context draw context
     * @param counter render tick counter
     * @param info injection callback
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void hyperglide$render(DrawContext context,
        RenderTickCounter counter, CallbackInfo info) {

        Navigation module = Modules.get().get(Navigation.class);
        if (module != null) module.render(context);
    }
}
