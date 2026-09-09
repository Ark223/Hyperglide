package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.consume.UseAction;
import net.minecraft.util.math.Vec2f;

/**
 * Provides common player state helpers.
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
