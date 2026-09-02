package hyperglide.utilities;

import hyperglide.mixin.EntityAccessor;
import hyperglide.mixin.InputAccessor;
import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Provides compatibility helpers for shared code.
 */
public final class API {
    private API() {}

    /**
     * Updates the forward and sideways player movement.
     *
     * @param input processed player input
     * @param forward forward movement
     * @param sideways sideways movement
     */
    public static void move(Input input, float forward, float sideways) {
        InputAccessor access = (InputAccessor) input;
        access.hyperglide$setForward(forward);
        access.hyperglide$setSideways(sideways);
    }

    /**
     * Returns the current position of an entity.
     *
     * @param entity entity to read
     * @return entity position
     */
    public static Vec3d pos(Entity entity) {
        return entity.getPos();
    }

    /**
     * Returns the position of an entity from the previous tick.
     *
     * @param entity entity to read
     * @return previous entity position
     */
    public static Vec3d prev(Entity entity) {
        EntityAccessor access = (EntityAccessor) entity;
        return new Vec3d(
            access.hyperglide$getX(),
            access.hyperglide$getY(),
            access.hyperglide$getZ()
        );
    }

    /**
     * Sends the specified sneaking state to the server.
     *
     * @param player player whose state is sent
     * @param value requested sneaking state
     */
    public static void sneak(ClientPlayerEntity player, boolean value) {
        Mode mode = value ? Mode.PRESS_SHIFT_KEY : Mode.RELEASE_SHIFT_KEY;
        player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, mode));
    }

    /**
     * Returns the currently selected hotbar slot.
     *
     * @param inventory player inventory
     * @return selected hotbar slot
     */
    public static int slot(PlayerInventory inventory) {
        return inventory.selectedSlot;
    }

    /**
     * Plays the local placement sound for a block.
     *
     * @param world client world
     * @param block placed block
     * @param pos placement position
     */
    public static void sound(World world, Block block, BlockPos pos) {
        BlockSoundGroup sound = block.getDefaultState().getSoundGroup();

        world.playSound(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            sound.getPlaceSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F,
            false
        );
    }

    /**
     * Draws a rotated 2D line at the specified position and angle.
     *
     * @param context active draw context
     * @param px line X position
     * @param py line Y position
     * @param length line length
     * @param angle line angle
     * @param width line thickness
     * @param color line color
     */
    public static void line(DrawContext context, double px, double py,
        double length, double angle, int width, int color) {

        RotationAxis axis = RotationAxis.POSITIVE_Z;
        MatrixStack matrices = context.getMatrices();

        matrices.push();
        matrices.translate(px, py, 0.0);
        matrices.multiply(axis.rotation((float) angle));
        matrices.translate(0.0, -width * 0.5, 0.0);

        context.fill(0, 0, (int) Math.ceil(length), width, color);
        matrices.pop();
    }

    /**
     * Draws an item icon at the specified position and scale.
     *
     * @param context active draw context
     * @param stack item stack
     * @param px item X position
     * @param py item Y position
     * @param scale item scale
     */
    public static void item(DrawContext context,
        ItemStack stack, int px, int py, float scale) {

        MatrixStack matrices = context.getMatrices();
        matrices.push();

        try {
            matrices.translate(px, py, 100.0F);
            matrices.scale(scale, scale, 1.0F);
            context.drawItem(stack, 0, 0);
        } finally {
            matrices.pop();
        }
    }

    /**
     * Draws a one-pixel rectangular border.
     *
     * @param context active draw context
     * @param px border X position
     * @param py border Y position
     * @param width border width
     * @param height border height
     * @param color border color
     */
    public static void border(DrawContext context,
        int px, int py, int width, int height, int color) {
        context.drawBorder(px, py, width, height, color);
    }
}
