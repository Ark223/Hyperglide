package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.Client;
import hyperglide.utilities.Elytra;
import hyperglide.utilities.Hotbar;
import hyperglide.utilities.Packets;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ControlFly extends Module {
    private static final double epsilon = 1.0E-6;
    private static final double ceiling = 34.0;
    private static final double ticks = 20.0;

    private static final float bound = 60.0F;
    private static final int priority = 100;
    private static final int timeout = 4;

    private static final double correction = 0.025;
    private static final double gain = 0.30;
    private static final double lift = 0.004;

    private static final double sharp = Math.cos(Math.toRadians(45.0));

    private final SettingGroup movement = this.settings.createGroup("Movement");
    private final SettingGroup auto = this.settings.createGroup("Automation");

    private final Setting<Double> maximum = this.movement.add(new DoubleSetting.Builder()
        .name("maximum-speed")
        .description("Maximum controlled speed in blocks per second.")
        .defaultValue(34.0)
        .min(20.0)
        .sliderMax(34.0)
        .build()
    );

    private final Setting<Double> minimum = this.movement.add(new DoubleSetting.Builder()
        .name("minimum-speed")
        .description("Uses a rocket when speed drops below this value.")
        .defaultValue(30.0)
        .min(20.0)
        .sliderMax(34.0)
        .build()
    );

    private final Setting<Double> penalty = this.movement.add(new DoubleSetting.Builder()
        .name("ascent-penalty")
        .description("Speed removed from the limit while flying upward.")
        .defaultValue(2.0)
        .min(0.0)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Boolean> gravity = this.movement.add(new BoolSetting.Builder()
        .name("no-gravity")
        .description("Disables gravity during horizontal movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> forward = this.auto.add(new BoolSetting.Builder()
        .name("keep-forward")
        .description("Moves forward when no movement key is held.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> starter = this.auto.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Starts gliding after holding jump while airborne.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> timer = this.auto.add(new IntSetting.Builder()
        .name("takeoff-timer")
        .description("Jump hold ticks required before starting flight.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .visible(this.starter::get)
        .build()
    );

    private final Boost boost = new Boost();
    private final Flight flight = new Flight();
    private final Turn turn = new Turn();
    private final View view = new View();

    private int jump;

    public ControlFly() {
        super(Hyperglide.CATEGORY, "control-fly",
            "Provides controlled flight with automatic rocket boosting."
        );
    }

    /**
     * Resets runtime state and captures the view and flight orientation.
     */
    @Override
    public void onActivate() {
        this.reset();
        if (this.mc.player == null) return;

        this.view.yaw = this.mc.player.getYaw();
        this.view.pitch = this.mc.player.getPitch();

        this.flight.yaw = this.view.yaw;
        this.flight.pitch = this.view.pitch;
        this.flight.altitude = this.mc.player.getY();
    }

    /**
     * Restores the player view and clears all runtime state.
     */
    @Override
    public void onDeactivate() {
        this.restore();
        this.reset();
    }

    //region Event handlers

    /**
     * Updates boost state, takeoff automation and active flight control.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!Client.ready()) return;

        this.update();

        if (!this.mc.player.isGliding()) {
            this.restore();
            this.clear();
            this.takeoff();
            return;
        }

        if (this.halted()) return;

        this.view();
        this.control();
    }

    /**
     * Stops idle flight movement or limits the speed.
     *
     * @param event player movement event
     */
    @EventHandler
    private void move(PlayerMoveEvent event) {
        if (!Client.ready() ||
            event.type != MovementType.SELF ||
            !this.mc.player.isGliding() ||
            this.halted()) return;

        Vec3d input = this.direction();
        if (input.lengthSquared() < epsilon) {
            this.stop(event);
            return;
        }

        if (this.flight.brake) {
            this.flight.brake = false;
            this.stop(event);
            return;
        }

        this.limit(event, input.normalize());
    }

    /**
     * Tracks when a firework is used.
     *
     * @param event outgoing packet event
     */
    @EventHandler
    private void packet(PacketEvent.Send event) {
        if (!Client.ready() || this.boost.automatic || !this.mc.player.isGliding()
            || !(event.packet instanceof PlayerInteractItemC2SPacket packet)) {
            return;
        }

        ItemStack stack = this.mc.player.getStackInHand(packet.getHand());
        if (!stack.isOf(Items.FIREWORK_ROCKET)) return;

        this.await();
        this.count(stack);
    }

    //endregion

    //region State management

    /**
     * Resets all runtime flight, camera, boost and rotation state.
     */
    private void reset() {
        this.clear();
        this.jump = 0;

        this.flight.yaw = 0.0F;
        this.flight.pitch = 0.0F;
        this.flight.altitude = 0.0;

        this.view.active = false;
        this.view.yaw = 0.0F;
        this.view.pitch = 0.0F;

        this.turn.yaw = 0.0F;
        this.turn.pitch = 0.0F;
    }

    /**
     * Clears transient boost, flight and rotation state.
     */
    private void clear() {
        this.boost.expiry = 0;
        this.boost.end = 0;

        this.boost.pending = false;
        this.boost.launching = false;
        this.boost.automatic = false;
        this.boost.rocket = null;

        this.idle();

        this.flight.input = 0;
        this.flight.dir = Vec3d.ZERO;
        this.flight.brake = false;

        this.flight.altitude =
            this.mc.player == null ?
            0.0 : this.mc.player.getY();

        this.turn.active = false;
    }

    /**
     * Clears manual, leveling and steering flight states.
     */
    private void idle() {
        this.flight.manual = false;
        this.flight.leveling = false;
        this.flight.steering = false;
    }

    //endregion

    //region Flight control

    /**
     * Processes movement input, steering, pitch control and rocket launching.
     */
    private void control() {
        Vec3d input = this.direction();
        if (input.lengthSquared() < epsilon) {
            this.rest();
            return;
        }

        int state = this.state();
        boolean redirect =
            this.flight.input != 0 &&
            this.flight.input != state;

        this.flight.input = state;
        this.flight.manual =
            this.mc.options.jumpKey.isPressed() ||
            this.mc.options.sneakKey.isPressed();

        Vec3d dir = this.steer(input.normalize());

        if (this.flight.dir.lengthSquared() >= epsilon &&
            this.sharp(this.flight.dir, dir)) {
            this.flight.brake = true;
        }

        this.flight.dir = dir;

        boolean boosted = this.active();
        this.aim(dir, boosted);

        boolean launch = this.launch(dir, boosted, redirect);
        if (launch) this.prepare();

        this.mc.player.setYaw(this.flight.yaw);
        this.mc.player.setPitch(this.flight.pitch);

        this.rotate(launch);
    }

    /**
     * Keeps normal player look and renews an expiring boost while stopped.
     */
    private void rest() {
        this.idle();

        this.flight.yaw = this.mc.player.getYaw();
        this.flight.pitch = this.mc.player.getPitch();

        if (!this.renew()) return;

        this.await();
        this.rotate(true);
    }

    /**
     * Stops movement after idle input is confirmed.
     *
     * @param event player movement event
     */
    private void stop(PlayerMoveEvent event) {
        ((IVec3d) event.movement).meteor$set(0.0, 0.0, 0.0);
        this.mc.player.setVelocity(Vec3d.ZERO);
    }

    /**
     * Limits horizontal movement and applies boost correction.
     *
     * @param event player movement event
     * @param dir normalized flight direction
     */
    private void limit(PlayerMoveEvent event, Vec3d dir) {
        double horizontal = event.movement.horizontalLength();
        double maximum = this.maximum(dir);
        double amount = horizontal;

        if (horizontal > maximum) {
            amount = maximum;
        } else if (this.active()) {
            amount = Math.min(maximum, horizontal + correction);
        }

        if (Math.abs(amount - horizontal) <= epsilon) return;

        Vec3d adjusted = this.flat(event.movement, amount, horizontal);

        ((IVec3d) event.movement).meteor$set(
            adjusted.x, event.movement.y, adjusted.z
        );

        this.mc.player.setVelocity(
            new Vec3d(adjusted.x, event.movement.y, adjusted.z)
        );
    }

    /**
     * Scales horizontal movement without changing its direction.
     *
     * @param movement current movement vector
     * @param amount requested horizontal magnitude
     * @param horizontal current horizontal magnitude
     * @return adjusted horizontal movement vector
     */
    private Vec3d flat(Vec3d movement, double amount, double horizontal) {
        if (horizontal > epsilon) {
            Vec3d flat = new Vec3d(movement.x, 0.0, movement.z);
            return flat.multiply(amount / horizontal);
        }

        return Vec3d.fromPolar(0.0F, this.flight.yaw).multiply(amount);
    }

    /**
     * Checks whether Elytra Tweaks currently stops movement.
     *
     * @return true while collision avoidance is stopping movement
     */
    private boolean halted() {
        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        return tweaks != null && tweaks.halted();
    }

    //endregion

    //region Boost state

    /**
     * Expires pending launches and removes inactive tracked rockets.
     */
    private void update() {
        if (this.boost.pending && this.mc.player.age > this.boost.expiry) {
            this.boost.pending = false;
            this.boost.launching = false;
        }

        if (this.boost.rocket != null && !this.boost.rocket.isAlive()) {
            this.boost.rocket = null;
        }
    }

    /**
     * Marks a rocket as pending until its entity is detected or times out.
     */
    private void await() {
        this.boost.pending = true;
        this.boost.expiry = this.mc.player.age + timeout;
    }

    /**
     * Stores how long the firework can boost the player.
     *
     * @param stack used firework stack
     */
    private void count(ItemStack stack) {
        FireworksComponent fireworks = stack.get(DataComponentTypes.FIREWORKS);
        int duration = fireworks == null ? 1 : fireworks.flightDuration();

        this.boost.end = this.mc.player.age + Math.max(1, (duration + 1) * 10);
    }

    /**
     * Checks whether the tracked rocket is currently active.
     *
     * @return true when the tracked rocket is alive
     */
    private boolean active() {
        return this.boost.rocket != null && this.boost.rocket.isAlive();
    }

    /**
     * Checks whether a rocket is pending, launching or active.
     *
     * @return true when another rocket should not be launched
     */
    private boolean busy() {
        return this.boost.pending || this.boost.launching || this.active();
    }

    /**
     * Checks whether a stopped flight should renew its active boost.
     *
     * @return true one tick before the tracked boost window ends
     */
    private boolean renew() {
        return this.active() && this.boost.end > 0
            && this.mc.player.age >= this.boost.end - 1
            && !this.boost.pending && !this.boost.launching
            && this.stocked();
    }

    //endregion

    //region Takeoff and steering

    /**
     * Starts elytra flight after jump is held for the configured duration.
     */
    private void takeoff() {
        if (!this.starter.get()) {
            this.jump = 0;
            return;
        }

        if (!this.mc.options.jumpKey.isPressed()) {
            this.jump = 0;
            return;
        }

        this.jump++;

        if (this.jump < this.timer.get() ||
            this.mc.player.isOnGround() ||
            !Elytra.equipped()) return;

        this.jump = 0;

        Elytra.start();
    }

    /**
     * Calculates movement input relative to the active camera direction.
     *
     * @return combined movement direction
     */
    private Vec3d direction() {
        Vec3d dir = Vec3d.ZERO;

        float angle = this.view.active ? this.view.yaw : this.mc.player.getYaw();

        Vec3d front = Vec3d.fromPolar(0.0F, angle);
        Vec3d right = Vec3d.fromPolar(0.0F, angle + 90.0F);

        if (this.mc.options.forwardKey.isPressed()) dir = dir.add(front);
        if (this.mc.options.backKey.isPressed()) dir = dir.subtract(front);
        if (this.mc.options.rightKey.isPressed()) dir = dir.add(right);
        if (this.mc.options.leftKey.isPressed()) dir = dir.subtract(right);
        if (this.mc.options.jumpKey.isPressed()) dir = dir.add(0.0, 1.0, 0.0);
        if (this.mc.options.sneakKey.isPressed()) dir = dir.add(0.0, -1.0, 0.0);

        return dir.lengthSquared() < epsilon && this.forward.get() ? front : dir;
    }

    /**
     * Returns the current movement key state.
     *
     * @return movement key state
     */
    private int state() {
        int state = 0;

        if (this.mc.options.forwardKey.isPressed()) state |= 1;
        if (this.mc.options.backKey.isPressed()) state |= 2;
        if (this.mc.options.rightKey.isPressed()) state |= 4;
        if (this.mc.options.leftKey.isPressed()) state |= 8;
        if (this.mc.options.jumpKey.isPressed()) state |= 16;
        if (this.mc.options.sneakKey.isPressed()) state |= 32;

        if (state == 0 && this.forward.get()) state = 1;
        return state;
    }

    /**
     * Updates flight yaw from input while preserving vertical input.
     *
     * @param input normalized movement input
     * @return normalized steering direction
     */
    private Vec3d steer(Vec3d input) {
        double length = input.horizontalLength();
        if (length < epsilon) {
            if (!this.flight.steering) {
                this.flight.yaw = this.mc.player.getYaw();
                this.flight.steering = true;
            }
            return input;
        }

        this.flight.steering = true;
        this.flight.yaw = (float) (Math.toDegrees(
            Math.atan2(input.z, input.x)) - 90.0
        );

        Vec3d flat = Vec3d.fromPolar(0.0F, this.flight.yaw);
        flat = flat.multiply(length);

        return flat.add(0.0, input.y, 0.0).normalize();
    }

    /**
     * Checks whether the direction changes sharply.
     *
     * @param first first direction
     * @param second second direction
     * @return true when the turn is sharp
     */
    private boolean sharp(Vec3d first, Vec3d second) {
        return first.dotProduct(second) < sharp - epsilon;
    }

    /**
     * Converts a direction vector into a clamped flight pitch.
     *
     * @param dir normalized flight direction
     * @return pitch angle in degrees
     */
    private float angle(Vec3d dir) {
        return MathHelper.clamp((float) -Math.toDegrees(
            Math.atan2(dir.y, dir.horizontalLength())
        ), -90.0F, 90.0F);
    }

    /**
     * Checks whether flight input currently requests movement.
     *
     * @return true when controlled movement is active
     */
    private boolean moving() {
        return this.direction().lengthSquared() >= epsilon;
    }

    //endregion

    //region Altitude control

    /**
     * Selects manual pitch control or automatic altitude leveling.
     *
     * @param dir normalized movement direction
     * @param boosted whether an active rocket is boosting the player
     */
    private void aim(Vec3d dir, boolean boosted) {
        if (this.flight.manual) {
            this.flight.leveling = false;
            this.flight.altitude = this.mc.player.getY();
            this.flight.pitch = this.angle(dir);
            return;
        }

        if (!this.flight.leveling) {
            this.flight.altitude = this.mc.player.getY();
            this.flight.leveling = true;
        }

        this.flight.pitch = !this.gravity.get() ? 0.0F :
            this.level(this.mc.player.getVelocity(), boosted);
    }

    /**
     * Finds the pitch that most closely maintains the target altitude.
     *
     * @param velocity current player velocity
     * @param boosted whether rocket acceleration should be predicted
     * @return selected leveling pitch
     */
    private float level(Vec3d velocity, boolean boosted) {
        double error = this.flight.altitude - this.mc.player.getY();
        double desired = MathHelper.clamp(error * gain + lift, -0.20, 0.20);

        Choice choice = new Choice(
            this.flight.pitch, Double.POSITIVE_INFINITY
        );

        choice = this.search(velocity, boosted, desired,
            -bound, 1.0F, 80, choice
        );

        choice = this.search(velocity, boosted, desired,
            choice.pitch() - 1.0F, 0.1F, 20, choice
        );

        return choice.pitch();
    }

    /**
     * Searches a range of pitch values for the lowest prediction score.
     *
     * @param velocity current player velocity
     * @param boosted whether rocket acceleration should be predicted
     * @param desired desired vertical velocity
     * @param start first pitch value
     * @param step pitch increment
     * @param count number of pitch increments
     * @param choice current best choice
     * @return best pitch choice found
     */
    private Choice search(Vec3d velocity, boolean boosted,
        double desired, float start, float step, int count, Choice choice) {

        for (int index = 0; index <= count; index++) {
            float pitch = MathHelper.clamp(start + index * step, -bound, 20.0F);

            double score = this.score(velocity, pitch, desired, boosted);
            if (score < choice.score()) choice = new Choice(pitch, score);
        }

        return choice;
    }

    /**
     * Scores a pitch by predicted vertical speed, minimum speed and stability.
     *
     * @param velocity current player velocity
     * @param pitch candidate pitch
     * @param desired desired vertical velocity
     * @param boosted whether rocket acceleration should be predicted
     * @return candidate score
     */
    private double score(Vec3d velocity, float pitch, double desired, boolean boosted) {
        Vec3d next = this.predict(velocity, this.flight.yaw, pitch, boosted);
        double score = Math.abs(next.y - desired);

        score += Math.max(0.0,
            this.minimum.get() / ticks - next.horizontalLength()
        ) * 0.01;

        score += Math.abs(pitch - this.flight.pitch) * 1.0E-5;
        return score;
    }

    //endregion

    //region Rotation control

    /**
     * Synchronizes the rotation and launches a rocket when requested.
     *
     * @param launch whether a rocket should be launched
     */
    private void rotate(boolean launch) {
        boolean changed = this.changed();

        if (launch) {
            float yaw = this.flight.yaw;
            float pitch = this.flight.pitch;

            this.boost.launching = true;

            Rotations.rotate(yaw, pitch, priority, () -> this.rocket(yaw, pitch));
            changed = true;
        } else if (changed) {
            Rotations.rotate(this.flight.yaw, this.flight.pitch, priority);
        }

        if (changed) this.remember();
    }

    /**
     * Checks whether the rotation changed since the previous update.
     *
     * @return true when yaw or pitch requires synchronization
     */
    private boolean changed() {
        return !this.turn.active ||
            Math.abs(MathHelper.wrapDegrees(
                this.flight.yaw - this.turn.yaw
            )) > 0.05F ||
            Math.abs(
                this.flight.pitch - this.turn.pitch
            ) > 0.05F;
    }

    /**
     * Stores the most recently synchronized flight rotation.
     */
    private void remember() {
        this.turn.yaw = this.flight.yaw;
        this.turn.pitch = this.flight.pitch;
        this.turn.active = true;
    }

    //endregion

    //region Flight physics

    /**
     * Calculates the maximum allowed speed for a flight direction.
     *
     * @param dir normalized flight direction
     * @return maximum speed in blocks per tick
     */
    private double maximum(Vec3d dir) {
        double speed = ceiling;

        if (dir.y > 0.0) {
            speed -= this.penalty.get() * dir.y;
        }

        speed = Math.min(this.maximum.get(), speed);
        return Math.max(0.0, speed / ticks);
    }

    /**
     * Predicts the next velocity from elytra and rocket physics.
     *
     * @param velocity current velocity
     * @param yaw predicted yaw
     * @param pitch predicted pitch
     * @param boosted whether rocket acceleration should be applied
     * @return predicted next velocity
     */
    private Vec3d predict(Vec3d velocity, float yaw, float pitch, boolean boosted) {
        Vec3d rotation = Vec3d.fromPolar(pitch, yaw);
        Vec3d next = Elytra.glide(velocity, rotation);

        return boosted ? this.firework(next, rotation) : next;
    }

    /**
     * Applies firework acceleration to a predicted velocity.
     *
     * @param velocity current velocity
     * @param rotation current rotation direction
     * @return boosted velocity
     */
    private Vec3d firework(Vec3d velocity, Vec3d rotation) {
        return velocity.add(
            this.thrust(velocity.x, rotation.x),
            this.thrust(velocity.y, rotation.y),
            this.thrust(velocity.z, rotation.z)
        );
    }

    /**
     * Calculates firework acceleration for one velocity axis.
     *
     * @param velocity current axis velocity
     * @param rotation rotation direction on the same axis
     * @return acceleration applied to the axis
     */
    private double thrust(double velocity, double rotation) {
        return rotation * 0.1 + (rotation * 1.5 - velocity) * 0.5;
    }

    //endregion

    //region Rocket handling

    /**
     * Checks whether another rocket should be launched.
     *
     * @param dir normalized flight direction
     * @param boosted whether an active rocket is boosting the player
     * @param redirect whether controlled movement changed direction
     * @return true when direction or speed requires another rocket
     */
    private boolean launch(Vec3d dir, boolean boosted, boolean redirect) {
        if (this.busy() || !this.stocked()) return false;
        if (!boosted && redirect) return true;

        Vec3d next = this.predict(
            this.mc.player.getVelocity(),
            this.flight.yaw, this.flight.pitch, boosted
        );

        return next.length() < Math.min(
            this.minimum.get() / ticks, this.maximum(dir)
        );
    }

    /**
     * Marks a rocket launch as pending and adjusts leveling.
     */
    private void prepare() {
        this.await();

        if (!this.flight.manual && this.gravity.get()) {
            this.flight.pitch = this.level(
                this.mc.player.getVelocity(), true
            );
        }
    }

    /**
     * Checks whether a firework rocket is available in the hotbar or offhand.
     *
     * @return true when a rocket is available
     */
    private boolean stocked() {
        return InvUtils.findInHotbar(Items.FIREWORK_ROCKET).found();
    }

    /**
     * Selects and uses a firework rocket at the controlled rotation.
     *
     * @param yaw controlled flight yaw
     * @param pitch controlled flight pitch
     */
    private void rocket(float yaw, float pitch) {
        if (!Client.ready() || !Client.interaction()) {
            this.cancel();
            return;
        }

        FindItemResult result = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!result.found()) {
            this.cancel();
            return;
        }

        Hand hand = result.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

        ItemStack stack = this.stack(result);
        if (!stack.isOf(Items.FIREWORK_ROCKET)) {
            this.cancel();
            return;
        }

        int selected = Hotbar.selected();
        boolean swap = !result.isOffhand() && result.slot() != selected;

        this.boost.automatic = true;

        try {
            if (swap) Hotbar.sync(result.slot());
            Packets.item(hand, yaw, pitch);
            this.count(stack);
        } finally {
            if (swap) Hotbar.sync(selected);
            this.boost.automatic = false;
        }

        this.boost.expiry = this.mc.player.age + timeout;
        this.boost.launching = false;
    }

    /**
     * Returns the item stack represented by an inventory search result.
     *
     * @param result inventory search result
     * @return matching item stack
     */
    private ItemStack stack(FindItemResult result) {
        return result.isOffhand()
            ? this.mc.player.getOffHandStack()
            : Hotbar.stack(result.slot());
    }

    /**
     * Cancels the current pending rocket launch.
     */
    private void cancel() {
        this.boost.pending = false;
        this.boost.launching = false;
    }

    /**
     * Tracks the firework currently boosting the player.
     *
     * @param rocket player-owned firework rocket
     */
    public void track(FireworkRocketEntity rocket) {
        if (!Client.ready() || this.boost.rocket == rocket) {
            return;
        }

        if (this.boost.rocket != null &&
            this.boost.rocket.isAlive() &&
            rocket.age >= this.boost.rocket.age) {
            return;
        }

        this.boost.rocket = rocket;
        this.boost.pending = false;
        this.boost.launching = false;
    }

    //endregion

    //region Camera control

    /**
     * Activates and initializes the independent camera rotation.
     *
     * @return true when camera control is available
     */
    public boolean view() {
        if (!this.isActive() || this.mc.player == null ||
            !this.mc.player.isGliding()) return false;

        if (!this.moving()) {
            this.restore();
            return false;
        }

        if (!this.view.active) {
            this.view.yaw = this.mc.player.getYaw();
            this.view.pitch = this.mc.player.getPitch();
            this.view.active = true;
        }

        return true;
    }

    /**
     * Applies mouse movement to the independent camera rotation.
     *
     * @param x horizontal mouse movement
     * @param y vertical mouse movement
     */
    public void look(double x, double y) {
        this.view.yaw += (float) (x * 0.15);

        this.view.pitch = MathHelper.clamp(
            this.view.pitch + (float) (y * 0.15), -90.0F, 90.0F
        );
    }

    /**
     * Checks whether independent camera control is currently active.
     *
     * @return true when the custom camera rotation should be used
     */
    public boolean camera() {
        return this.isActive() && this.mc.player != null
            && this.view.active && this.mc.player.isGliding();
    }

    /**
     * Returns the independent camera yaw.
     *
     * @return camera yaw
     */
    public float yaw() {
        return this.view.yaw;
    }

    /**
     * Returns the independent camera pitch.
     *
     * @return camera pitch
     */
    public float pitch() {
        return this.view.pitch;
    }

    /**
     * Restores the independent camera rotation to the player.
     */
    private void restore() {
        if (!this.view.active) return;

        if (this.mc.player != null) {
            this.mc.player.setYaw(this.view.yaw);
            this.mc.player.setPitch(this.view.pitch);
        }

        this.view.active = false;
    }

    //endregion

    //region Data structures

    /**
     * Stores a candidate leveling pitch and its prediction score.
     *
     * @param pitch candidate pitch
     * @param score candidate score
     */
    private record Choice(float pitch, double score) {}

    /**
     * Stores active controlled flight state.
     */
    private static class Flight {
        private int input;

        private boolean manual;
        private boolean leveling;
        private boolean steering;
        private boolean brake;

        private double altitude;
        private float yaw;
        private float pitch;

        private Vec3d dir = Vec3d.ZERO;
    }

    /**
     * Stores the most recently synchronized flight rotation.
     */
    private static class Turn {
        private boolean active;
        private float yaw;
        private float pitch;
    }

    /**
     * Stores independent camera rotation state.
     */
    private static class View {
        private boolean active;
        private float yaw;
        private float pitch;
    }

    /**
     * Stores pending, launching and active rocket boost state.
     */
    private static class Boost {
        private int expiry;
        private int end;

        private boolean pending;
        private boolean launching;
        private boolean automatic;

        private FireworkRocketEntity rocket;
    }

    //endregion
}
