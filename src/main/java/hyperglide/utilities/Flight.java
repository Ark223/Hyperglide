package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.PlayerInput;
import java.util.function.BooleanSupplier;

/**
 * Manages normal and spoofed elytra flight state.
 */
public final class Flight {
    private final MinecraftClient client = MinecraftClient.getInstance();

    private static final Flight instance = new Flight();

    private static final int grace = 3;
    private static final int chest = 6;
    private static final int gliding = 7;

    private boolean active;
    private boolean enabled;
    private boolean liquid;
    private boolean restart;
    private boolean restore;

    private int dry;
    private int slot = -1;
    private BooleanSupplier request;

    private Flight() {}

    //region State management

    /**
     * Returns the shared flight controller.
     *
     * @return flight controller
     */
    public static Flight get() {
        return instance;
    }

    /**
     * Enables or disables spoofing.
     *
     * @param enabled requested spoofing state
     */
    public void enabled(boolean enabled) {
        if (this.enabled == enabled) return;

        this.enabled = enabled;
        if (!enabled) this.clear();
    }

    /**
     * Checks whether spoofing may be used.
     *
     * @return true when spoofing may be used
     */
    public boolean spoof() {
        return this.enabled && !Baritone.elytra()
            && !this.liquid && !Player.liquid();
    }

    /**
     * Checks whether spoofing is waiting to take control.
     *
     * @return true while spoofing is waiting to become active
     */
    public boolean standby() {
        return this.spoof() && !this.active;
    }

    /**
     * Reports when required equipment cannot be prepared.
     *
     * @return true when spoofing preparation cannot complete
     */
    public boolean blocked() {
        return this.spoof() && !this.prepare();
    }

    /**
     * Checks whether the current flight mode has usable equipment.
     *
     * @return true when normal or spoofed flight is available
     */
    public boolean available() {
        return this.spoof() ? this.ready() : Elytra.equipped();
    }

    /**
     * Checks whether spoofing has the equipment it needs.
     *
     * @return true when spoofing can start or continue
     */
    public boolean ready() {
        if (!this.spoof()) return false;

        if (!Client.interaction() || !Inventory.ready()) {
            return false;
        }

        if (this.active || this.prepared()) {
            return true;
        }

        return Elytra.equipped()
            && Hotbar.find(Elytra::chestplate) >= 0;
    }

    /**
     * Checks whether spoofing currently owns the glide.
     *
     * @return true while spoofed flight is active
     */
    public boolean active() {
        return this.spoof() && this.active;
    }

    /**
     * Checks whether spoof equipment sounds should be muted.
     *
     * @return true while spoof equipment is being managed
     */
    public boolean mute() {
        return this.spoof() && (this.active || this.prepared());
    }

    //endregion

    //region Flight control

    /**
     * Converts an existing elytra glide into spoofing.
     *
     * @return true when the chestplate transaction starts
     */
    public boolean activate() {
        if (!this.spoof()) return false;
        if (this.active) return true;

        if (!Client.interaction() || !Inventory.ready() ||
            !this.client.player.isGliding()) {
            return false;
        }

        if (!this.prepare()) return false;

        this.slot = Elytra.hotbar();
        if (this.slot < 0) return false;

        this.active = true;
        this.restart = false;
        this.restore = false;

        return true;
    }

    /**
     * Starts spoofing while the player is airborne.
     *
     * @return true when the flight start was accepted
     */
    public boolean start() {
        if (!this.spoof()) return false;
        if (this.active) return true;

        if (!this.prepare() ||
            this.client.player.isOnGround()) {
            return false;
        }

        this.active = true;
        this.restart = true;
        this.restore = false;

        if (this.begin()) return true;

        this.clear();
        return false;
    }

    /**
     * Keeps spoofing synchronized with the current player state.
     */
    public void tick() {
        this.liquid();

        if (!this.active) return;

        if (!this.enabled || !Client.ready()) {
            this.clear();
            return;
        }

        if (!this.spoof()) {
            this.normal();
            return;
        }

        this.sprint();

        if (!this.continuing()) {
            this.clear();
            return;
        }

        if (this.restart && !this.restore) {
            this.begin();
        }
    }

    /**
     * Restores the chestplate after a temporary elytra restart.
     */
    public void finish() {
        if (!this.restore || !Client.ready() ||
            !this.client.player.isGliding()) {
            return;
        }

        this.flush();

        if (Elytra.equipped() && this.slot >= 0) {
            this.inventory();
            Inventory.swap(chest, this.slot);
        }

        this.restore = false;
        this.slot = -1;
    }

    /**
     * Restores normal elytra equipment for Baritone flight.
     *
     * @return true when an elytra is equipped
     */
    public boolean normal() {
        if (!Client.interaction() || !Inventory.ready()) {
            return Elytra.equipped();
        }

        if (Elytra.equipped()) {
            this.reset();
            return true;
        }

        int slot = Elytra.hotbar();
        if (slot < 0) return false;

        this.inventory();
        Inventory.swap(chest, slot);

        if (!Elytra.equipped()) return false;

        this.reset();
        return true;
    }

    /**
     * Keeps normal elytra active while leaving liquid.
     */
    private void liquid() {
        if (!Client.ready()) {
            this.dry = 0;
            return;
        }

        if (Player.liquid()) {
            this.liquid = true;
            this.dry = 0;
            return;
        }

        if (!this.liquid) {
            this.dry = 0;
            return;
        }

        if (this.client.player.isOnGround() &&
            !this.client.player.isGliding()) {
            this.liquid = false;
            this.dry = 0;
            return;
        }

        if (!this.client.player.isGliding()) {
            this.dry = 0;
            return;
        }

        if (++this.dry < grace) return;

        this.liquid = false;
        this.dry = 0;
    }

    //endregion

    //region Requests and synchronization

    /**
     * Runs an action now or remembers it for the next elytra window.
     *
     * @param action action to run
     * @return true when the action ran or was remembered
     */
    public boolean request(BooleanSupplier action) {
        if (action == null) return false;
        if (!this.active()) return action.getAsBoolean();

        if (this.request == null) {
            this.request = action;
        }

        return true;
    }

    /**
     * Remembers a firework request for the next spoofed elytra window.
     *
     * @return true when the request was accepted
     */
    public boolean request() {
        return this.request(Elytra::firework);
    }

    /**
     * Keeps the local gliding flag when the server clears it.
     *
     * @param flags incoming entity flags
     * @return adjusted entity flags
     */
    public byte sync(byte flags) {
        boolean flying = (flags & (1 << gliding)) != 0;
        if (!this.active() || flying) return flags;

        if (!this.continuing()) {
            this.clear();
            return flags;
        }

        if (!this.restore) this.restart = true;
        return (byte) (flags | (1 << gliding));
    }

    //endregion

    //region Armor equipment

    /**
     * Equips a chestplate before spoofing begins.
     *
     * @return true when the chestplate and elytra are ready
     */
    public boolean prepare() {
        if (!this.spoof()) return false;

        if (!Client.interaction() || !Inventory.ready()) {
            return false;
        }

        if (this.prepared()) return true;
        if (!Elytra.equipped()) return false;

        int slot = Hotbar.find(Elytra::chestplate);
        if (slot < 0) return false;

        this.inventory();
        Inventory.swap(chest, slot);

        return this.prepared();
    }

    /**
     * Checks whether the chestplate and elytra are in place.
     *
     * @return true when spoofing can perform a restart
     */
    private boolean prepared() {
        if (Elytra.equipped()) return false;

        if (!Client.interaction() || !Inventory.ready()) {
            return false;
        }

        return Elytra.chestplate() && Elytra.hotbar() >= 0;
    }

    /**
     * Performs one temporary elytra restart.
     *
     * @return true when the restart packet was sent
     */
    private boolean begin() {
        if (!this.prepared() || !this.continuing()) {
            return false;
        }

        int slot = Elytra.hotbar();
        if (slot < 0) return false;

        this.slot = slot;

        this.inventory();
        Inventory.swap(chest, slot);

        this.jump(false);
        Elytra.start();
        this.jump(true);

        Packets.pong(Integer.MIN_VALUE);

        this.restart = false;
        this.restore = true;

        this.flush();
        return true;
    }

    //endregion

    //region Packet ordering

    /**
     * Runs a pending action while the elytra is temporarily equipped.
     */
    private void flush() {
        if (this.request == null) return;

        if (this.request.getAsBoolean()) {
            this.request = null;
        }
    }

    /**
     * Sends the neutral input Grim expects before an inventory action.
     */
    private void inventory() {
        this.sprint();
        Packets.input(Packets.state(false, false, false));
    }

    /**
     * Keeps sprint disabled while spoofing controls flight.
     */
    private void sprint() {
        if (this.client.player.isSprinting()) {
            Packets.command(ClientCommandC2SPacket.Mode.STOP_SPRINTING);
            this.client.player.setSprinting(false);
        }

        PlayerInput input = Packets.state();
        if (!input.sprint()) return;

        this.client.player.input.playerInput =
            Packets.state(input.jump(), false);
    }

    /**
     * Sends the jump state used around the takeoff packet.
     *
     * @param pressed requested jump state
     */
    private void jump(boolean pressed) {
        PlayerInput input = Packets.state(pressed, false);
        Packets.input(input);

        this.client.player.input.playerInput = input;
    }

    //endregion

    //region Validation and cleanup

    /**
     * Checks whether flight conditions still allow gliding.
     *
     * @return true while flight may continue
     */
    private boolean continuing() {
        return Client.ready()
            && !Player.liquid()
            && !this.client.player.isOnGround()
            && !this.client.player.hasVehicle()
            && !this.client.player.getAbilities().flying
            && !this.client.player.hasStatusEffect(StatusEffects.LEVITATION);
    }

    /**
     * Ends spoofing and restores the chestplate when necessary.
     */
    private void clear() {
        if (this.restore && Client.ready() &&
            Elytra.equipped() && this.slot >= 0) {
            this.inventory();
            Inventory.swap(chest, this.slot);
        }
        this.reset();
    }

    /**
     * Clears the current state without changing equipment.
     */
    private void reset() {
        this.active = false;
        this.restart = false;
        this.restore = false;

        this.slot = -1;
        this.request = null;
    }

    //endregion
}
