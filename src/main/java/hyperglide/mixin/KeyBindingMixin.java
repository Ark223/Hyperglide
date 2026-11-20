package hyperglide.mixin;

import hyperglide.modules.BounceFly;
import hyperglide.modules.ControlFly;
import hyperglide.modules.ElytraTweaks;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    /**
     * Provides movement input for active elytra modules.
     *
     * @param info key state callback
     */
    @Inject(method = "isPressed", at = @At("RETURN"), cancellable = true)
    private void hyperglide$pressed(CallbackInfoReturnable<Boolean> info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;

        Modules modules = Modules.get();
        if (modules == null) return;

        BounceFly bounce = modules.get(BounceFly.class);
        if (bounce != null && bounce.enabled()) {
            if ((Object) this == client.options.forwardKey) {
                info.setReturnValue(true);
            } else if ((Object) this == client.options.jumpKey) {
                info.setReturnValue(bounce.jumping());
            }
            return;
        }

        if ((Object) this != client.options.jumpKey) return;

        ControlFly control = modules.get(ControlFly.class);
        if (control != null && control.deploying()) {
            info.setReturnValue(control.jumping());
            return;
        }

        ElytraTweaks tweaks = modules.get(ElytraTweaks.class);
        if (tweaks != null && tweaks.deploying()) {
            info.setReturnValue(tweaks.jumping());
        }
    }
}
