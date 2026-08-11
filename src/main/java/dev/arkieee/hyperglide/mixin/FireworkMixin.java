package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.ControlFly;
import dev.arkieee.hyperglide.modules.ElytraTweaks;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks firework rockets owned by player.
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkMixin {
    @Shadow
    private LivingEntity shooter;

    @Inject(method = "tick", at = @At("HEAD"))
    private void hyperglide$track(CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || this.shooter != client.player) {
            return;
        }

        FireworkRocketEntity rocket =
            (FireworkRocketEntity) (Object) this;

        ControlFly control = Modules.get().get(ControlFly.class);
        if (control != null && control.isActive()) {
            control.track(rocket);
        }

        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        if (tweaks != null && tweaks.isActive()) {
            tweaks.track(rocket);
        }
    }
}
