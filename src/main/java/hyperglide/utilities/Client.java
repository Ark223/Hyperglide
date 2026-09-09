package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

/**
 * Provides common checks for the current client state.
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

    /**
     * Checks whether the current world is the overworld.
     *
     * @return true when the overworld is loaded
     */
    public static boolean overworld() {
        return client.world != null && World.OVERWORLD.equals(
            client.world.getRegistryKey()
        );
    }

    /**
     * Checks whether the current world is the nether.
     *
     * @return true when the nether is loaded
     */
    public static boolean nether() {
        return client.world != null && World.NETHER.equals(
            client.world.getRegistryKey()
        );
    }
}
