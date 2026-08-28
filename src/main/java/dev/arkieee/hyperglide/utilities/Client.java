package dev.arkieee.hyperglide.utilities;

import net.minecraft.client.MinecraftClient;

/**
 * Checks whether the client is ready for common module actions.
 */
public final class Client {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private Client() {}

    /**
     * Checks whether the player and world are loaded.
     *
     * @return true when the player and world are available
     */
    public static boolean loaded() {
        return client.player != null && client.world != null;
    }

    /**
     * Checks whether the player, world and connection are available.
     *
     * @return true when the client is ready for network actions
     */
    public static boolean ready() {
        return loaded() && client.getNetworkHandler() != null;
    }

    /**
     * Checks whether normal player interactions are available.
     *
     * @return true when the player can interact with the world
     */
    public static boolean interaction() {
        return loaded() && client.interactionManager != null;
    }
}
