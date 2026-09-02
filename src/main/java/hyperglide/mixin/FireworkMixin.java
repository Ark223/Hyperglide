package hyperglide.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import hyperglide.modules.ControlFly;
import hyperglide.modules.ElytraTweaks;
import hyperglide.modules.RocketBoost;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkMixin {
    @Shadow
    private LivingEntity shooter;

    /**
     * Forwards firework entities to active flight modules.
     *
     * @param info injection callback
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void hyperglide$track(CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || this.shooter != client.player) {
            return;
        }

        FireworkRocketEntity rocket = (FireworkRocketEntity) (Object) this;

        ControlFly control = Modules.get().get(ControlFly.class);
        if (control != null && control.isActive()) {
            control.track(rocket);
        }

        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        if (tweaks != null && tweaks.isActive()) {
            tweaks.track(rocket);
        }

        RocketBoost boost = Modules.get().get(RocketBoost.class);
        if (boost != null && boost.isActive()) {
            boost.track(rocket);
        }
    }

    /**
     * Replaces vanilla player rocket acceleration with Rocket Boost.
     *
     * @param shooter entity receiving rocket acceleration
     * @param velocity vanilla rocket velocity
     * @param original original velocity invocation
     */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/entity/LivingEntity;setVelocity(Lnet/minecraft/util/math/Vec3d;)V"
    ))
    private void hyperglide$velocity(LivingEntity shooter, Vec3d velocity,
        Operation<Void> original) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != shooter) {
            original.call(shooter, velocity);
            return;
        }

        RocketBoost boost = Modules.get().get(RocketBoost.class);
        if (boost == null || !boost.boost()) {
            original.call(shooter, velocity);
        }
    }
}
