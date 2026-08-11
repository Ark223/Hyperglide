package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.NoSprintFov;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Removes sprinting zoom effects while the module is active.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class FovMixin {
    @ModifyVariable(method = "getFovMultiplier",
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private float hyperglide$fov(float scale) {
        AbstractClientPlayerEntity player =
            (AbstractClientPlayerEntity) (Object) this;

        if (!player.isSprinting()) return scale;

        NoSprintFov module = Modules.get().get(NoSprintFov.class);
        return module != null && module.isActive() ? 0.0F : scale;
    }
}
