package hyperglide.mixin;

import hyperglide.modules.MiningTweaks;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class MiningTweaksMixin {
    /**
     * Redirects block attacks to Mining Tweaks.
     *
     * @param pos block position
     * @param side block side
     * @param info injection callback
     */
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void onAttack(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        this.hyperglide$mine(pos, side, info);
    }

    /**
     * Redirects block breaking updates to Mining Tweaks.
     *
     * @param pos block position
     * @param side block side
     * @param info injection callback
     */
    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void onUpdate(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> info) {
        this.hyperglide$mine(pos, side, info);
    }

    /**
     * Handles redirected block breaking.
     *
     * @param pos block position
     * @param side block side
     * @param info injection callback
     */
    @Unique
    private void hyperglide$mine(BlockPos pos, Direction side,
        CallbackInfoReturnable<Boolean> info) {
        MinecraftClient client = MinecraftClient.getInstance();
        MiningTweaks module = Modules.get().get(MiningTweaks.class);

        if (module == null || !module.isActive() ||
            client.player == null || client.player.isCreative()) {
            return;
        }

        if (module.bypass(pos)) return;

        module.mine(pos, side);
        info.setReturnValue(true);
    }
}
