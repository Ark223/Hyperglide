package dev.arkieee.hyperglide.utilities;

import dev.arkieee.hyperglide.mixin.InteractionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Handles sequenced player actions and item interactions.
 */
public final class Packets {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private Packets() {}

    /**
     * Sends a player action with the current interaction sequence.
     *
     * @param action player action
     * @param pos block position
     * @param side block face
     */
    public static void action(Action action, BlockPos pos, Direction side) {
        ((InteractionAccessor) client.interactionManager)
            .hyperglide$sendSequencedPacket(client.world, sequence ->
                new PlayerActionC2SPacket(action, pos, side, sequence)
            );
    }

    /**
     * Sends an item interaction with the current interaction sequence.
     *
     * @param hand interaction hand
     * @param yaw interaction yaw
     * @param pitch interaction pitch
     */
    public static void item(Hand hand, float yaw, float pitch) {
        ((InteractionAccessor) client.interactionManager)
            .hyperglide$sendSequencedPacket(client.world, sequence ->
                new PlayerInteractItemC2SPacket(hand, sequence, yaw, pitch)
            );
    }
}
