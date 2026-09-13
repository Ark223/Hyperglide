package hyperglide.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import hyperglide.modules.BounceFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerMixin {
    /**
     * Prevents item use from slowing movement while bouncing.
     *
     * @param speed vanilla item use speed multiplier
     * @return movement speed multiplier used while bouncing
     */
    @ModifyReturnValue(
        method = "getActiveItemSpeedMultiplier", at = @At("RETURN")
    )
    private float hyperglide$slow(float speed) {
        BounceFly module = Modules.get().get(BounceFly.class);
        return module != null && module.enabled() ? 1.0F : speed;
    }
}
