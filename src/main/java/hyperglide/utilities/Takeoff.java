package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;

/**
 * Controls the jump input needed to start elytra flight.
 */
public final class Takeoff {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private boolean active;
    private boolean pressed;
    private boolean ground;

    /**
     * Starts the input sequence while airborne.
     */
    public void start() {
        this.start(false);
    }

    /**
     * Starts the input sequence with optional ground jumping.
     *
     * @param ground whether the sequence may start from the ground
     */
    public void start(boolean ground) {
        this.active = true;
        this.pressed = false;
        this.ground = ground;
    }

    /**
     * Updates the jump state while starting flight.
     */
    public void pulse() {
        if (!this.active) return;

        boolean ready = Client.loaded() && Elytra.equipped();
        if (!ready || client.player.isGliding()) {
            this.reset();
            return;
        }

        if (client.player.isOnGround()) {
            if (!this.ground) {
                this.reset();
                return;
            }

            this.pressed = true;
            return;
        }

        this.pressed = !this.pressed;
    }

    /**
     * Clears the current input sequence.
     */
    public void reset() {
        this.active = false;
        this.pressed = false;
        this.ground = false;
    }

    /**
     * Checks whether the input sequence is active.
     *
     * @return true while jump input is being controlled
     */
    public boolean active() {
        return this.active;
    }

    /**
     * Checks whether the sequence may begin on the ground.
     *
     * @return true when ground jumping is enabled
     */
    public boolean ground() {
        return this.ground;
    }

    /**
     * Returns the current jump state.
     *
     * @return forced jump state
     */
    public boolean pressed() {
        return this.pressed;
    }
}
