package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.math.Vec2f;
import org.lwjgl.glfw.GLFW;

/**
 * Handles common GUI and mouse checks.
 */
public final class Gui {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private Gui() {}

    /**
     * Checks whether chat or a Meteor GUI screen can receive mouse input.
     *
     * @return true when supported mouse interaction is available
     */
    public static boolean interactive() {
        if (client.currentScreen == null) return false;
        if (client.currentScreen instanceof ChatScreen) return true;

        return client.currentScreen.getClass().getName().startsWith(
            "meteordevelopment.meteorclient.gui."
        );
    }

    /**
     * Checks whether a vanilla screen other than chat is open.
     *
     * @return true when another vanilla screen is open
     */
    public static boolean vanilla() {
        if (client.currentScreen == null ||
            client.currentScreen instanceof ChatScreen) return false;

        return client.currentScreen.getClass().getName().startsWith(
            "net.minecraft.client.gui.screen."
        );
    }

    /**
     * Checks whether a mouse button is held.
     *
     * @param button mouse button
     * @return true when the button is held
     */
    public static boolean pressed(int button) {
        return GLFW.glfwGetMouseButton(
            client.getWindow().getHandle(), button
        ) == GLFW.GLFW_PRESS;
    }

    /**
     * Returns the mouse position in scaled screen coordinates.
     *
     * @return scaled mouse position
     */
    public static Vec2f mouse() {
        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();

        int swidth = client.getWindow().getScaledWidth();
        int sheight = client.getWindow().getScaledHeight();

        return new Vec2f(
            (float) (client.mouse.getX() * swidth / width),
            (float) (client.mouse.getY() * sheight / height)
        );
    }
}
