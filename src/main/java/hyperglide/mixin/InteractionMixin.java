package hyperglide.mixin;

import hyperglide.utilities.Flight;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class InteractionMixin {
    /**
     * Remembers firework use for the next elytra spoofing window.
     *
     * @param player local player
     * @param hand requested interaction hand
     * @param info interaction callback
     */
    @Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
    private void hyperglide$rocket(PlayerEntity player, Hand hand,
        CallbackInfoReturnable<ActionResult> info) {

        MinecraftClient client = MinecraftClient.getInstance();
        if (player != client.player || !Flight.get().active() ||
            !player.getStackInHand(hand).isOf(Items.FIREWORK_ROCKET)) {
            return;
        }

        if (!Flight.get().request()) return;
        info.setReturnValue(ActionResult.SUCCESS);
    }
}
