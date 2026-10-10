package hyperglide.utilities;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.consume.UseAction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * Provides common utilities for player state.
 */
public final class Player {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private Player() {}

    /**
     * Returns the player's horizontal position.
     *
     * @return player X/Z position
     */
    public static Vec2f position() {
        return new Vec2f(
            (float) client.player.getX(),
            (float) client.player.getZ()
        );
    }

    /**
     * Returns the block directly below the player.
     *
     * @return block below the player's feet
     */
    public static BlockPos floor() {
        boolean ground = client.player.isOnGround();
        double offset = ground ? 0.01 : 1.0;

        return BlockPos.ofFloored(
            client.player.getX(),
            client.player.getY() - offset,
            client.player.getZ()
        );
    }

    /**
     * Applies the requested velocity to the player.
     *
     * @param event player movement event
     * @param velocity requested movement vector
     */
    public static void velocity(PlayerMoveEvent event, Vec3d velocity) {
        client.player.setVelocity(velocity);
        ((IVec3d) event.movement).meteor$set(
            velocity.x, velocity.y, velocity.z
        );
    }

    /**
     * Rotates the player to follow direction.
     *
     * @param yaw requested yaw
     */
    public static void rotate(float yaw) {
        client.player.setYaw(yaw);
        client.player.setHeadYaw(yaw);
        client.player.setBodyYaw(yaw);
    }

    /**
     * Checks whether the player is in water or lava.
     *
     * @return true while inside liquid
     */
    public static boolean liquid() {
        return client.player.isTouchingWater()
            || client.player.isInLava();
    }

    /**
     * Checks whether the player is eating or drinking.
     *
     * @return true while consuming an item
     */
    public static boolean consuming() {
        if (!client.player.isUsingItem()) return false;

        UseAction action = client.player.getActiveItem().getUseAction();
        return action == UseAction.DRINK || action == UseAction.EAT;
    }
}
