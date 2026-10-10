package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;

/**
 * Controls the jump input needed to start elytra flight.
 */
public final class Takeoff {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private final Flight flight = Flight.get();

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
     * Starts the input with optional ground jumping.
     *
     * @param ground whether the sequence covers ground
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

        if (!this.ready()) {
            this.reset();
            return;
        }

        if (client.player.isOnGround()) {
            this.jump();
            return;
        }

        if (this.flight.spoof()) {
            this.spoof();
            return;
        }

        this.pressed = !this.pressed;
    }

    /**
     * Checks whether the takeoff sequence may continue.
     *
     * @return true while takeoff can continue
     */
    private boolean ready() {
        return Client.loaded()
            && this.flight.available()
            && !client.player.isGliding();
    }

    /**
     * Keeps jump pressed while leaving the ground.
     */
    private void jump() {
        if (!this.ground) {
            this.reset();
        } else {
            this.pressed = true;
        }
    }

    /**
     * Starts or maintains spoofed takeoff.
     */
    private void spoof() {
        if (this.flight.active()) {
            this.pressed = Player.liquid();
            return;
        }

        if (!this.flight.start()) return;

        if (Player.liquid()) {
            this.pressed = true;
        } else {
            this.reset();
        }
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
