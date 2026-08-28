package dev.arkieee.hyperglide.utilities;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.function.Consumer;

/**
 * Handles block placement using a custom air-place interaction.
 */
public final class Placement {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private Placement() {}

    /**
     * Places a block at the requested position.
     *
     * @param pos destination block position
     */
    public static void place(BlockPos pos) {
        place(air(pos));
    }

    /**
     * Places a block and reports when its interaction packet is sent.
     *
     * @param pos destination block position
     * @param guard receives true only while the interaction packet is sent
     */
    public static void place(BlockPos pos, Consumer<Boolean> guard) {
        place(air(pos), guard);
    }

    /**
     * Places a block using the supplied hit result.
     *
     * @param hit target block hit result
     */
    public static void place(BlockHitResult hit) {
        place(hit, null);
    }

    /**
     * Places a block and reports when its interaction packet is sent.
     *
     * @param hit target block hit result
     * @param guard receives true only while the interaction packet is sent
     */
    public static void place(BlockHitResult hit, Consumer<Boolean> guard) {
        PlayerActionC2SPacket swap = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN, Direction.DOWN
        );

        client.player.networkHandler.sendPacket(swap);
        if (guard != null) guard.accept(true);

        try {
            client.player.networkHandler.sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, hit,
                    client.player.currentScreenHandler.getRevision() + 2
                )
            );
        } finally {
            if (guard != null) guard.accept(false);
            client.player.networkHandler.sendPacket(swap);
        }
    }

    /**
     * Creates the hit result used for AirPlace placement.
     *
     * @param pos destination block position
     * @return direct block hit result for the destination
     */
    private static BlockHitResult air(BlockPos pos) {
        return new BlockHitResult(
            Vec3d.ofCenter(pos), Direction.UP, pos, false
        );
    }

    /**
     * Plays the local placement sound for a block item.
     *
     * @param item placed block item
     * @param pos placement position
     */
    public static void sound(BlockItem item, BlockPos pos) {
        sound(item.getBlock(), pos);
    }

    /**
     * Plays the local placement sound for a block.
     *
     * @param block placed block
     * @param pos placement position
     */
    public static void sound(Block block, BlockPos pos) {
        BlockSoundGroup sound = block.getDefaultState().getSoundGroup();

        client.world.playSound(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            sound.getPlaceSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F,
            false
        );
    }
}
