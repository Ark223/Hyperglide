package hyperglide.mixin;

import hyperglide.modules.BounceFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Shadow
    private int jumpingCooldown;

    /**
     * Removes the jump delay while Bounce Fly controls movement.
     *
     * @param info injection callback
     */
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void hyperglide$jump(CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if ((Object) this != client.player) return;

        BounceFly module = Modules.get().get(BounceFly.class);
        if (module != null && module.enabled()) {
            this.jumpingCooldown = 0;
        }
    }
}
