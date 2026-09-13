package hyperglide.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import hyperglide.modules.BounceFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerMixin {
    /**
     * Prevents gliding from cancelling sprint while bouncing.
     *
     * @param gliding vanilla gliding state
     * @return state used by vanilla sprint cancellation
     */
    @ModifyExpressionValue(method = "shouldStopSprinting", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/network/ClientPlayerEntity;isGliding()Z"
    ))
    private boolean hyperglide$sprint(boolean gliding) {
        if (!gliding) return false;

        BounceFly module = Modules.get().get(BounceFly.class);
        return module == null || !module.enabled();
    }

    /**
     * Prevents item use from slowing movement while bouncing.
     *
     * @param using vanilla item use state
     * @return state used by vanilla item slowdown
     */
    @ModifyExpressionValue(method = "tickMovement", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"
    ))
    private boolean hyperglide$slow(boolean using) {
        if (!using) return false;

        BounceFly module = Modules.get().get(BounceFly.class);
        return module == null || !module.enabled();
    }

    /**
     * Prevents item use from cancelling sprint while bouncing.
     *
     * @param using vanilla item use state
     * @return state used by vanilla sprint cancellation
     */
    @ModifyExpressionValue(method = "shouldStopSprinting", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"
    ))
    private boolean hyperglide$use(boolean using) {
        if (!using) return false;

        BounceFly module = Modules.get().get(BounceFly.class);
        return module == null || !module.enabled();
    }
}
