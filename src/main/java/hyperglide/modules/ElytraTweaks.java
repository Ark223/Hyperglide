package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.Baritone;
import hyperglide.utilities.Client;
import hyperglide.utilities.Elytra;
import hyperglide.utilities.Flight;
import hyperglide.utilities.Hotbar;
import hyperglide.utilities.Inventory;
import hyperglide.utilities.Packets;
import hyperglide.utilities.Player;
import hyperglide.utilities.Takeoff;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class ElytraTweaks extends Module {
    private static final double epsilon = 1.0E-6;
    private static final double drag = 0.99;

    private static final int settle = 4;
    private static final int chest = 6;

    private static final int xaxis = 1;
    private static final int zaxis = 2;
    private static final int yaxis = 4;

    private static final int axes = xaxis | zaxis;

    private final SettingGroup equipment = this.settings.createGroup("Equipment");
    private final SettingGroup recovery = this.settings.createGroup("Recovery");
    private final SettingGroup safety = this.settings.createGroup("Safety");
    private final SettingGroup takeoff = this.settings.createGroup("Takeoff");
    private final SettingGroup advanced = this.settings.createGroup("Advanced");

    private final Setting<Boolean> swap = this.equipment.add(new BoolSetting.Builder()
        .name("auto-swap")
        .description("Equips an elytra on double jump and a chestplate after landing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> window = this.equipment.add(new IntSetting.Builder()
        .name("jump-window")
        .description("Maximum ticks allowed between double-jump presses.")
        .defaultValue(5)
        .min(3)
        .sliderMax(10)
        .visible(this.swap::get)
        .build()
    );

    private final Setting<Boolean> replace = this.equipment.add(new BoolSetting.Builder()
        .name("swap-broken")
        .description("Replaces a worn elytra with a healthier one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimum = this.equipment.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Remaining durability before replacing the elytra.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(this.replace::get)
        .build()
    );

    private final Setting<Boolean> deploy = this.recovery.add(new BoolSetting.Builder()
        .name("auto-deploy")
        .description("Deploys the elytra when Baritone flight is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> escape = this.recovery.add(new BoolSetting.Builder()
        .name("liquid-escape")
        .description("Moves upward when Baritone cannot deploy inside liquid.")
        .defaultValue(true)
        .visible(this.deploy::get)
        .build()
    );

    private final Setting<Integer> timeout = this.recovery.add(new IntSetting.Builder()
        .name("retry-timeout")
        .description("Ticks to wait before retrying Baritone elytra flight.")
        .defaultValue(5)
        .min(3)
        .sliderMax(10)
        .visible(this.deploy::get)
        .build()
    );

    private final Setting<Boolean> repair = this.recovery.add(new BoolSetting.Builder()
        .name("repair-mode")
        .description("Automatically equips damaged elytras for repair.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> avoid = this.safety.add(new BoolSetting.Builder()
        .name("avoid-collisions")
        .description("Stops flight before damaging predicted collisions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> distance = this.safety.add(new DoubleSetting.Builder()
        .name("scan-distance")
        .description("Distance ahead in blocks to scan for collisions.")
        .defaultValue(4.0)
        .min(2.0)
        .sliderMax(8.0)
        .decimalPlaces(1)
        .visible(this.avoid::get)
        .build()
    );

    private final Setting<Double> expand = this.safety.add(new DoubleSetting.Builder()
        .name("hitbox-expand")
        .description("Expands the hitbox used for collision prediction.")
        .defaultValue(0.05)
        .min(0.0)
        .sliderMax(0.2)
        .decimalPlaces(2)
        .visible(this.avoid::get)
        .build()
    );

    private final Setting<Integer> release = this.safety.add(new IntSetting.Builder()
        .name("release-delay")
        .description("Ticks to wait before release when the path is safe.")
        .defaultValue(3)
        .min(0)
        .sliderMax(5)
        .visible(this.avoid::get)
        .build()
    );

    private final Setting<Boolean> starter = this.takeoff.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Starts gliding after holding jump while airborne.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> timer = this.takeoff.add(new IntSetting.Builder()
        .name("takeoff-timer")
        .description("Jump hold ticks required before starting flight.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .visible(this.starter::get)
        .build()
    );

    private final Setting<Boolean> spoof = this.advanced.add(new BoolSetting.Builder()
        .name("spoof-mode")
        .description("Keeps a chestplate equipped during elytra flight.")
        .defaultValue(false)
        .onChanged(this::spoof)
        .build()
    );

    private final Takeoff input = new Takeoff();
    private final Flight flight = Flight.get();

    private int tap;
    private boolean pressed;

    private int phase;
    private int slot;
    private boolean opened;

    private int jump;
    private int sync;
    private int pulse;
    private int resume;
    private boolean boost;

    private int retry;
    private boolean escaping;

    private int stable;
    private boolean halt;
    private boolean sneak;

    private double speed;
    private FireworkRocketEntity rocket;

    public ElytraTweaks() {
        super(Hyperglide.CATEGORY, "elytra-tweaks",
            "Provides useful tweaks and automation for elytra flight."
        );

        this.takeoff.add(new KeybindSetting.Builder()
            .name("toggle-key")
            .description("Starts flight or uses a rocket while gliding.")
            .defaultValue(Keybind.none())
            .action(this::key)
            .build()
        );

        this.advanced.add(new KeybindSetting.Builder()
            .name("toggle-key")
            .description("Toggles spoof without stopping your flight.")
            .defaultValue(Keybind.none())
            .action(this::spoof)
            .build()
        );
    }

    /**
     * Clears all runtime state.
     */
    @Override
    public void onActivate() {
        this.reset();
        this.flight.enabled(this.spoofing());
    }

    /**
     * Stops active inventory swapping and clears runtime state.
     */
    @Override
    public void onDeactivate() {
        this.flight.enabled(false);
        this.abort();
        this.reset();
    }

    //region Event handlers

    /**
     * Handles flight, equipment and takeoff each tick.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!Client.ready()) return;

        if (this.halt && !this.active()) {
            this.speed *= drag;
        }

        if (this.pulse > 0 && --this.pulse <= 0) {
            Packets.sync(Packets.forward());
        }

        this.flight();
        this.controls();

        if (this.phase > 0) {
            this.strict();
            return;
        }

        if (this.mend()) return;

        this.doublejump();
        this.ground();

        if (this.replacement()) return;

        this.cycle();
    }

    /**
     * Finishes any temporary spoof swap.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void tick(TickEvent.Post event) {
        this.flight.finish();
    }

    /**
     * Handles collision avoidance while flying.
     *
     * @param event player movement event
     */
    @EventHandler
    private void move(PlayerMoveEvent event) {
        if (!this.avoiding(event)) {
            this.clear();
            return;
        }

        if (this.halt) {
            this.maintain(event);
            return;
        }

        if (event.movement.horizontalLength() > epsilon) {
            this.speed = Math.max(event.movement.length(),
                this.mc.player.getVelocity().length()
            );
        }

        if (this.danger(event.movement)) {
            this.stop(event);
        }
    }

    //endregion

    //region Core management

    /**
     * Keeps spoof mode synchronized while flying.
     */
    private void flight() {
        this.flight.enabled(this.spoofing());

        if (this.flight.standby() &&
            this.mc.player.isGliding()) {
            this.flight.activate();
        }

        this.flight.tick();
    }

    /**
     * Keeps required inputs active and uses a queued rocket.
     */
    private void controls() {
        if (this.sneak) {
            this.mc.options.sneakKey.setPressed(true);
        }

        if (this.escaping) {
            this.mc.options.jumpKey.setPressed(true);
        }

        this.handoff();
        this.input.pulse();

        if (!this.boost) return;

        if (this.mc.player.isGliding()) {
            this.boost = false;
            this.rocket();
        }
    }

    /**
     * Handles takeoff and flight recovery.
     */
    private void cycle() {
        if (this.bounce()) {
            this.jump = 0;
            this.sync = 0;
            this.input.reset();
        } else {
            this.takeoff();
            this.deploy();
        }
        this.recover();
    }

    //endregion

    //region State management

    /**
     * Changes automatic elytra deployment for Baritone.
     *
     * @param active requested auto deploy state
     */
    public void deploy(boolean active) {
        if (this.deploy.get() != active) {
            this.deploy.set(active);
        }
    }

    /**
     * Checks whether collision avoidance has stopped movement.
     *
     * @return true while movement is halted for collision avoidance
     */
    public boolean halted() {
        return this.isActive() && this.halt && !this.pilot();
    }

    /**
     * Checks whether automatic takeoff is active.
     *
     * @return true while takeoff input is active
     */
    public boolean deploying() {
        return this.isActive() && this.input.active();
    }

    /**
     * Returns whether takeoff is holding jump.
     *
     * @return forced jump state
     */
    public boolean jumping() {
        return this.input.pressed();
    }

    /**
     * Checks whether elytra flight is available.
     *
     * @return true when any flight mode can be started
     */
    private boolean ready() {
        return this.flight.available();
    }

    /**
     * Checks whether normal elytra equipment is required.
     *
     * @return true when spoofing must stay disabled
     */
    private boolean normal() {
        return this.pilot() || this.bounce() || Baritone.elytra();
    }

    /**
     * Checks whether spoof mode may currently run.
     *
     * @return true when spoofing is enabled
     */
    private boolean spoofing() {
        return this.spoof.get() && !this.normal();
    }

    /**
     * Checks whether Auto Pilot is currently active.
     *
     * @return true while Auto Pilot controls movement
     */
    private boolean pilot() {
        AutoPilot module = Modules.get().get(AutoPilot.class);
        return module != null && module.isActive();
    }

    /**
     * Checks whether Bounce Fly is currently active.
     *
     * @return true while Bounce Fly is active
     */
    private boolean bounce() {
        BounceFly module = Modules.get().get(BounceFly.class);
        return module != null && module.isActive();
    }

    /**
     * Clears all states and resets the movement control.
     */
    private void reset() {
        this.keys();
        this.gear();
        this.glide();
        this.safety();
    }

    /**
     * Releases any keys held by the module.
     */
    private void keys() {
        if (this.sneak) {
            this.mc.options.sneakKey.setPressed(false);
        }

        if (this.escaping) {
            this.mc.options.jumpKey.setPressed(false);
        }

        this.sneak = false;
        this.escaping = false;
    }

    /**
     * Clears equipment handling.
     */
    private void gear() {
        this.tap = 0;
        this.pressed = false;

        this.phase = 0;
        this.slot = -1;
        this.opened = false;
    }

    /**
     * Clears takeoff and recovery state.
     */
    private void glide() {
        this.jump = 0;
        this.sync = 0;
        this.pulse = 0;
        this.resume = 0;
        this.retry = 0;
        this.boost = false;

        this.input.reset();
    }

    /**
     * Clears collision and boost tracking.
     */
    private void safety() {
        this.stable = 0;
        this.halt = false;

        this.speed = 0.0;
        this.rocket = null;
    }

    //endregion

    //region Spoof transition

    /**
     * Turns spoof mode on or off.
     */
    private void spoof() {
        boolean enabled = !this.spoof.get();
        this.spoof.set(enabled);

        this.info("Spoof mode has been " +
            (enabled ? "enabled." : "disabled.")
        );
    }

    /**
     * Applies spoof mode or returns to normal elytra flight.
     *
     * @param enabled whether spoof mode should be enabled
     */
    private void spoof(boolean enabled) {
        if (!this.isActive()) return;

        if (this.normal()) {
            this.flight.enabled(false);
            this.resume = 0;
            return;
        }

        boolean flying = this.flight.active() ||
            Client.ready() && this.mc.player.isGliding();

        if (!enabled && flying && !this.flight.normal()) {
            this.spoof.set(true);
            return;
        }

        this.flight.enabled(enabled);
        this.resume = 0;

        if (!enabled) {
            if (flying) this.resume = settle;
            return;
        }

        if (flying && this.flight.standby()) {
            this.flight.activate();
        }
    }

    //endregion

    //region Equipment control

    /**
     * Detects a double jump and equips an elytra from the hotbar.
     */
    private void doublejump() {
        if (this.input.active()) return;

        boolean current = !this.escaping &&
            this.mc.options.jumpKey.isPressed();

        if (current && !this.pressed) {
            if (this.tap > 0 && !this.mc.player.isOnGround()) {
                this.tap = 0;
                if (this.swap.get()) this.launch();
            } else {
                this.tap = this.window.get();
            }
        }

        this.pressed = current;
        if (this.tap > 0) this.tap--;
    }

    /**
     * Equips an elytra from the hotbar and prepares for takeoff.
     */
    private void launch() {
        if (this.flight.spoof()) {
            if (this.mc.player.isGliding()) {
                this.flight.activate();
            } else if (this.flight.ready()) {
                this.flight.start();
            }
            return;
        }

        if (Elytra.equipped()) {
            this.sync = 1;
            return;
        }

        int slot = this.hotbar(true);
        if (slot < 0 || !this.wear(slot)) {
            return;
        }

        this.sync = 1;
    }

    /**
     * Handles automatic elytra and chestplate swapping.
     */
    private void ground() {
        if (this.normal()) {
            if (!Elytra.equipped()) this.flight.normal();
            return;
        }

        if (!this.swap.get() || this.repair.get()) {
            return;
        }

        if (this.boost || this.input.active() ||
            this.sync > 0 || !Elytra.equipped() ||
            this.mc.options.jumpKey.isPressed() ||
            !this.mc.player.isOnGround()) {
            return;
        }

        int slot = this.hotbar(false);
        if (slot >= 0) this.wear(slot);
    }

    /**
     * Equips an armor item from the hotbar.
     *
     * @param slot hotbar slot containing the armor item
     * @return true when the equip interaction was sent
     */
    private boolean wear(int slot) {
        if (!Inventory.ready() ||
            this.mc.interactionManager == null) {
            return false;
        }

        int selected = Hotbar.selected();
        if (selected != slot) Hotbar.select(slot);

        this.mc.interactionManager.interactItem(
            this.mc.player, Hand.MAIN_HAND
        );

        if (selected != slot) Hotbar.select(selected);
        return true;
    }

    /**
     * Finds an elytra or chestplate in the hotbar.
     *
     * @param elytra whether an elytra or chestplate is requested
     * @return matching hotbar slot, or -1 when unavailable
     */
    private int hotbar(boolean elytra) {
        return elytra ? Elytra.hotbar() : Hotbar.find(Elytra::chestplate);
    }

    //endregion

    //region Durability replacement

    /**
     * Equips the most damaged elytra for repair.
     *
     * @return true when a repair swap starts
     */
    private boolean mend() {
        if (!this.repair.get() || this.flight.active()) return false;

        ItemStack stack = this.mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (stack.isOf(Items.ELYTRA) && stack.getDamage() > 0) return false;

        int slot = this.damaged();
        if (slot < 0 || !Inventory.ready()) {
            return false;
        }

        this.slot = slot;
        this.opened = false;
        this.phase = 1;
        return true;
    }

    /**
     * Replaces an elytra when its durability gets too low.
     *
     * @return true when replacement is active
     */
    private boolean replacement() {
        if (this.repair.get() || !this.replace.get() || !Elytra.equipped()) {
            return false;
        }

        ItemStack stack = this.mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (Elytra.remaining(stack) > this.minimum.get()) return false;

        int slot = this.spare();
        if (slot < 0 || !Inventory.ready()) {
            return false;
        }

        this.slot = slot;
        this.opened = false;
        this.phase = 1;
        return true;
    }

    /**
     * Continues the current elytra replacement.
     */
    private void strict() {
        if (!Inventory.ready()) return;

        switch (this.phase) {
            case 1 -> {
                this.click(Inventory.slot(this.slot));
                this.phase = 2;
            }

            case 2 -> {
                this.click(chest);
                this.phase = 3;
            }

            case 3 -> {
                this.click(Inventory.slot(this.slot));
                this.phase = 4;
            }

            case 4 -> this.finish();
        }
    }

    /**
     * Finds the most damaged elytra in the player inventory.
     *
     * @return player inventory slot, or -1 when every elytra is repaired
     */
    private int damaged() {
        return Inventory.best(
            stack -> stack.isOf(Items.ELYTRA) &&
            stack.getDamage() > 0, ItemStack::getDamage
        );
    }

    /**
     * Finds the healthiest replacement elytra in the inventory.
     *
     * @return player inventory slot, or -1 when no safe spare exists
     */
    private int spare() {
        int minimum = this.minimum.get();

        return Inventory.best(
            stack -> stack.isOf(Items.ELYTRA) &&
                Elytra.remaining(stack) > minimum,
            Elytra::remaining
        );
    }

    /**
     * Performs a normal click in the player inventory.
     *
     * @param slot player screen slot to click
     */
    private void click(int slot) {
        if (this.mc.interactionManager != null) {
            Inventory.pick(slot);
        }
    }

    /**
     * Finishes the elytra replacement and closes the inventory.
     */
    private void finish() {
        if (this.opened &&
            this.mc.currentScreen instanceof InventoryScreen) {
            this.mc.setScreen(null);
        }

        this.phase = 0;
        this.slot = -1;
        this.opened = false;
    }

    /**
     * Cancels the current elytra replacement.
     */
    private void abort() {
        if (!Client.ready() || this.phase <= 0) {
            return;
        }

        if (!Inventory.ready()) {
            this.opened = true;
            this.mc.setScreen(new InventoryScreen(this.mc.player));
        }

        if (this.phase == 2 || this.phase == 3) {
            this.click(Inventory.slot(this.slot));
        }

        this.finish();
    }

    //endregion

    //region Takeoff control

    /**
     * Handles the takeoff toggle key.
     */
    private void key() {
        if (this.mc.currentScreen == null) {
            this.trigger();
        }
    }

    /**
     * Uses a rocket or starts a boosted takeoff.
     */
    private void trigger() {
        if (!Client.ready() || !Client.interaction() ||
            this.phase > 0) {
            return;
        }

        if (this.mc.player.isGliding()) {
            if (this.flight.standby() &&
                !this.flight.activate()) {
                return;
            }

            this.rocket();
            return;
        }

        this.depart();
    }

    /**
     * Starts a normal takeoff and prepares a rocket boost.
     */
    private void depart() {
        this.jump = 0;
        this.retry = 0;
        this.sync = 0;

        this.boost = true;

        if (this.flight.blocked()) {
            this.boost = false;
            return;
        }

        if (!this.ready()) {
            if (this.flight.spoof()) {
                this.boost = false;
                return;
            }

            int slot = this.hotbar(true);
            if (slot < 0 || !this.wear(slot)) {
                this.boost = false;
                return;
            }

            this.sync = 1;
            return;
        }

        this.input.start(this.mc.player.isOnGround());
    }

    /**
     * Starts automatic takeoff after jump is held long enough.
     */
    private void takeoff() {
        if (this.escaping || !this.starter.get() ||
            this.mc.player.isGliding()) {
            this.jump = 0;
            return;
        }

        if (this.input.active()) return;

        if (!this.mc.options.jumpKey.isPressed() ||
            this.mc.player.isOnGround()) {
            this.jump = 0;
            return;
        }

        if (++this.jump < this.timer.get() || this.sync > 0) {
            return;
        }

        if (!this.ready()) {
            if (this.flight.spoof()) return;

            int slot = this.hotbar(true);
            if (slot < 0 || !this.wear(slot)) {
                return;
            }

            this.sync = 1;
            return;
        }

        this.start();
    }

    /**
     * Starts takeoff after a newly equipped elytra is ready.
     */
    private void deploy() {
        if (this.sync <= 0) return;

        if (this.mc.player.isGliding()) {
            this.sync = 0;
            return;
        }

        if (!this.boost && this.mc.player.isOnGround()) {
            this.sync = 0;
            return;
        }

        if (!Elytra.equipped() || ++this.sync < 3) {
            return;
        }

        this.sync = 0;

        if (this.boost) {
            this.input.start(this.mc.player.isOnGround());
        } else {
            this.start();
        }
    }

    /**
     * Starts elytra flight using jump input.
     */
    private void start() {
        if (!this.input.active() && this.ready() &&
            !this.mc.player.isGliding() &&
            !this.mc.player.isOnGround()) {
            this.input.start();
        }
    }

    /**
     * Waits for the elytra before restarting flight.
     */
    private void handoff() {
        if (this.resume <= 0 || !Elytra.equipped()) {
            return;
        }

        if (--this.resume <= 0) {
            this.mc.player.stopGliding();
            this.start();
        }
    }

    //endregion

    //region Boost management

    /**
     * Tracks the firework currently boosting the player.
     *
     * @param rocket player-owned firework rocket
     */
    public void track(FireworkRocketEntity rocket) {
        if (!this.isActive()) return;

        if (this.sneak && this.rocket != rocket) {
            if (!this.halt) this.sneak(false);
        }

        this.rocket = rocket;
    }

    /**
     * Checks whether the tracked rocket is still active.
     * 
     * @return true while the rocket is alive
     */
    private boolean active() {
        return this.rocket != null && this.rocket.isAlive();
    }

    /**
     * Uses an available firework rocket.
     *
     * @return true when the rocket was used
     */
    private boolean rocket() {
        return this.flight.request(this::firework);
    }

    /**
     * Uses an available firework rocket immediately.
     *
     * @return true when the rocket interaction was accepted
     */
    private boolean firework() {
        if (!Elytra.firework()) return false;

        this.pulse = 2;
        return true;
    }

    //endregion

    //region Baritone recovery

    /**
     * Starts or restores Baritone elytra flight.
     */
    private void recover() {
        if (!this.recoverable()) {
            this.retry = 0;
            this.swim(false);

            if (!this.boost && this.input.ground()) {
                this.input.reset();
            }
            return;
        }

        if (this.mc.player.isGliding()) {
            this.retry = 0;
            this.swim(false);
            return;
        }

        if (Player.liquid()) {
            this.liquid();
            return;
        }

        if (this.escaping) {
            this.retry = 0;
            this.swim(false);
        }

        if (!this.input.active()) {
            this.redeploy(this.timeout.get());
        }
    }

    /**
     * Handles Baritone recovery while the player is in liquid.
     */
    private void liquid() {
        this.retry = 0;

        if (!this.boost && this.input.ground()) {
            this.input.reset();
        }

        this.swim(this.escape.get());
    }

    /**
     * Retries elytra deployment after the requested delay.
     *
     * @param timeout ticks to wait before retrying
     */
    private void redeploy(int timeout) {
        if (++this.retry < timeout) return;

        this.retry = 0;
        this.input.start(true);
    }

    /**
     * Controls jump input while escaping liquid.
     *
     * @param pressed whether jump should remain held
     */
    private void swim(boolean pressed) {
        if (!pressed && !this.escaping) return;

        this.escaping = pressed;
        this.mc.options.jumpKey.setPressed(pressed);
    }

    /**
     * Checks whether Baritone flight recovery can run.
     *
     * @return true when recovery is available
     */
    private boolean recoverable() {
        return this.deploy.get() && Elytra.equipped()
            && Baritone.elytra() && !this.bounce();
    }

    //endregion

    //region Collision detection

    /**
     * Predicts whether a collision would cause damage.
     *
     * @param motion current movement vector
     * @return true when predicted speed loss causes damage
     */
    private boolean danger(Vec3d motion) {
        if (motion.lengthSquared() < epsilon) {
            return false;
        }

        int axis = this.collision(motion);
        double damage = this.damage(motion, axis);

        return (axis & yaxis) != 0 && motion.y > 0.0
            || (axis & axes) != 0 && damage > 0.0;
    }

    /**
     * Checks projected hitbox rays for collisions.
     *
     * @param motion movement vector to project
     * @return combined collision axes
     */
    private int collision(Vec3d motion) {
        Vec3d flat = new Vec3d(motion.x, 0.0, motion.z);
        if (flat.lengthSquared() < epsilon) return 0;

        Vec3d front = flat.normalize();
        Vec3d offset = motion.normalize();

        Vec3d side = new Vec3d(-front.z, 0.0, front.x);
        offset = offset.multiply(this.distance.get());

        double expand = this.expand.get();
        Box box = this.mc.player.getBoundingBox();
        box = box.expand(expand, 0.0, expand);

        double dx = (box.minX + box.maxX) / 2.0;
        double dz = (box.minZ + box.maxZ) / 2.0;

        double halfx = box.getLengthX() / 2.0;
        double halfz = box.getLengthZ() / 2.0;

        double forward = Math.abs(front.x) *
            halfx + Math.abs(front.z) * halfz;

        double width = Math.abs(side.x) *
            halfx + Math.abs(side.z) * halfz;

        double bx = dx + front.x * forward;
        double bz = dz + front.z * forward;

        double low = box.minY + 0.05;
        double high = box.maxY - 0.05;

        double level = (low + high) / 2.0;
        Vec3d center = new Vec3d(bx, level, bz);

        int axis = this.hit(center, center.add(offset));

        for (int idx = -1; idx <= 1; idx += 2) {
            Vec3d edge = side.multiply(width * idx);
            double ex = bx + edge.x, ez = bz + edge.z;

            Vec3d bot = new Vec3d(ex, low, ez);
            Vec3d top = new Vec3d(ex, high, ez);

            if (motion.y >= 0.0) {
                axis |= this.hit(bot, bot.add(offset));
            }

            axis |= this.hit(top, top.add(offset));
            if ((axis & axes) == axes) return axis;
        }

        return axis;
    }

    /**
     * Returns the collision axis hit by a ray.
     *
     * @param start ray origin
     * @param end ray destination
     * @return collision axis, or zero
     */
    private int hit(Vec3d start, Vec3d end) {
        BlockHitResult hit = this.mc.world.raycast(
            new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                this.mc.player
            )
        );

        if (hit.getType() != HitResult.Type.BLOCK) {
            return 0;
        }

        Direction.Axis axis = hit.getSide().getAxis();
        if (axis == Direction.Axis.X) return xaxis;
        if (axis == Direction.Axis.Y) return yaxis;
        if (axis == Direction.Axis.Z) return zaxis;

        return 0;
    }

    /**
     * Calculates collision damage from horizontal speed loss.
     *
     * @param motion current movement vector
     * @param axis predicted collision axes
     * @return predicted raw collision damage
     */
    private double damage(Vec3d motion, int axis) {
        double old = motion.horizontalLength();

        double px = (axis & xaxis) != 0 ? 0.0 : motion.x;
        double pz = (axis & zaxis) != 0 ? 0.0 : motion.z;

        double speed = Math.hypot(px, pz);
        return Math.max(0.0, (old - speed) * 10.0 - 3.0);
    }

    //endregion

    //region Collision recovery

    /**
     * Waits until elytra flight can safely continue.
     *
     * @param event player movement event
     */
    private void maintain(PlayerMoveEvent event) {
        Vec3d safe = this.safe();

        double length = safe.lengthSquared();
        if (length <= epsilon || this.danger(safe)) {
            this.stable = 0;
            this.freeze(event);
            return;
        }

        if (++this.stable < this.release.get()) {
            this.freeze(event);
            return;
        }

        this.resume(event, safe);
    }

    /**
     * Stops the player before a dangerous collision.
     *
     * @param event player movement event
     */
    private void stop(PlayerMoveEvent event) {
        this.stable = 0;
        this.halt = true;
        this.sneak(true);
        this.freeze(event);
    }

    /**
     * Resumes elytra flight in a safe direction.
     *
     * @param event player movement event
     * @param safe safe direction to continue flying
     */
    private void resume(PlayerMoveEvent event, Vec3d safe) {
        this.stable = 0;
        this.halt = false;
        this.speed = 0.0;

        this.sneak(false);
        Player.velocity(event, safe);
    }

    /**
     * Resets collision recovery when it is no longer needed.
     */
    private void clear() {
        this.stable = 0;
        this.halt = false;
        this.speed = 0.0;
        if (this.sneak) this.sneak(false);
    }

    /**
     * Finds a safe direction to continue flying.
     *
     * @return safe movement for continuing flight
     */
    private Vec3d safe() {
        if (this.speed <= epsilon) return Vec3d.ZERO;

        Vec3d motion = this.mc.player.getRotationVec(1.0F);
        motion = this.redirect(motion);

        return motion.normalize().multiply(this.speed);
    }

    /**
     * Redirects recovery toward Control Fly input.
     *
     * @param motion current recovery direction
     * @return redirected recovery direction
     */
    private Vec3d redirect(Vec3d motion) {
        ControlFly control = Modules.get().get(ControlFly.class);
        if (control == null || !control.isActive()) return motion;

        Vec3d dir = control.direction();

        double target = dir.horizontalLength();
        if (target <= epsilon) return motion;

        double scale = motion.horizontalLength() / target;
        return new Vec3d(dir.x * scale, motion.y, dir.z * scale);
    }

    /**
     * Stops the player during collision recovery.
     *
     * @param event player movement event
     */
    private void freeze(PlayerMoveEvent event) {
        Player.velocity(event, Vec3d.ZERO);
    }

    /**
     * Keeps sneak pressed during collision recovery.
     *
     * @param pressed whether sneak should stay pressed
     */
    private void sneak(boolean pressed) {
        this.sneak = pressed;
        this.mc.options.sneakKey.setPressed(pressed);
    }

    /**
     * Checks whether collision avoidance should run.
     *
     * @param event player movement event
     * @return true when collision avoidance is active
     */
    private boolean avoiding(PlayerMoveEvent event) {
        return Client.ready()
            && this.mc.player.isGliding()
            && event.type == MovementType.SELF
            && this.avoid.get() && !this.normal();
    }

    //endregion
}
