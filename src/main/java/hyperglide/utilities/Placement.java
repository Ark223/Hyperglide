package hyperglide.utilities;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.function.Consumer;

/**
 * Handles block placement using vanilla and air-place interactions.
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
     * Places a block from the requested hotbar slot.
     *
     * @param pos destination block position
     * @param slot hotbar slot containing the block
     * @return true when the placement interaction was sent
     */
    public static boolean place(BlockPos pos, int slot) {
        return place(air(pos), slot);
    }

    /**
     * Places a block against the supplied hit from a hotbar slot.
     *
     * @param hit block face used for placement
     * @param slot hotbar slot containing the block
     * @return true when the placement interaction was sent
     */
    public static boolean place(BlockHitResult hit, int slot) {
        if (!(Hotbar.stack(slot).getItem() instanceof BlockItem)) {
            return false;
        }

        if (!Client.interaction() || !Hotbar.swap(slot)) {
            return false;
        }

        try {
            place(hit);
            return true;
        } finally {
            Hotbar.restore();
        }
    }

    /**
     * Places a block and reports when its interaction packet is sent.
     *
     * @param pos destination block position
     * @param guard receives true only while the interaction is sent
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
     * @param guard receives true only while the interaction is sent
     */
    public static void place(BlockHitResult hit, Consumer<Boolean> guard) {
        if (guard != null) guard.accept(true);

        try {
            client.player.swingHand(Hand.MAIN_HAND);
            client.interactionManager.interactBlock(
                client.player, Hand.MAIN_HAND, hit
            );
        } finally {
            if (guard != null) guard.accept(false);
        }
    }

    /**
     * Creates a hit result for placing a block in the air.
     *
     * @param pos destination block position
     * @return direct block hit result for the destination
     */
    private static BlockHitResult air(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        return new BlockHitResult(center, Direction.UP, pos, false);
    }

    /**
     * Checks whether a block position can be replaced.
     *
     * @param pos block position to check
     * @return true when the block can be replaced
     */
    public static boolean open(BlockPos pos) {
        if (client.world == null) return false;
        return client.world.getBlockState(pos).isReplaceable();
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
        API.sound(client.world, block, pos);
    }
}
