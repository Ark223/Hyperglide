package hyperglide.mixin;

import hyperglide.modules.CriticalHits;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    /**
     * Forwards player input to attack modules.
     *
     * @param info injection callback
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hyperglide$tick(CallbackInfo info) {
        Input input = (Input) (Object) this;

        CriticalHits module = Modules.get().get(CriticalHits.class);
        if (module != null) module.input(input);
    }
}
