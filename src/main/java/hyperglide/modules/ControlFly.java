package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.Client;
import hyperglide.utilities.Elytra;
import hyperglide.utilities.Flight;
import hyperglide.utilities.Hotbar;
import hyperglide.utilities.Player;
import hyperglide.utilities.Takeoff;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ControlFly extends Module {
    private static final double epsilon = 1.0E-3;
    private static final double ceiling = 34.0;
    private static final double ticks = 20.0;

    private static final float bound = 60.0F;
    private static final int priority = 100;
    private static final int timeout = 4;

    private static final double gain = 0.60;
    private static final double sharp = Math.cos(Math.toRadians(45.0));

    private final SettingGroup movement = this.settings.createGroup("Movement");
    private final SettingGroup automation = this.settings.createGroup("Automation");

    private final Setting<Double> maximum = this.movement.add(new DoubleSetting.Builder()
        .name("maximum-speed")
        .description("Maximum controlled speed in blocks per second.")
        .defaultValue(34.0)
        .min(10.0)
        .sliderMax(34.0)
        .build()
    );

    private final Setting<Double> minimum = this.movement.add(new DoubleSetting.Builder()
        .name("minimum-speed")
        .description("Uses a rocket when speed drops below this value.")
        .defaultValue(27.0)
        .min(10.0)
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

    private final Setting<Integer> offset = this.movement.add(new IntSetting.Builder()
        .name("latency-offset")
        .description("Reduces boost time by milliseconds for ping variation.")
        .defaultValue(50)
        .min(0)
        .sliderMax(250)
        .build()
    );

    private final Setting<Boolean> forward = this.automation.add(new BoolSetting.Builder()
        .name("keep-forward")
        .description("Moves forward when no movement key is held.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> starter = this.automation.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Starts gliding after holding jump while airborne.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> timer = this.automation.add(new IntSetting.Builder()
        .name("takeoff-timer")
        .description("Jump hold ticks required before starting flight.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .visible(this.starter::get)
        .build()
    );

    private final Boost boost = new Boost();
    private final Flight flight = Flight.get();
    private final Takeoff input = new Takeoff();
    private final Motion motion = new Motion();
    private final Turn turn = new Turn();
    private final View view = new View();

    private int jump;

    public ControlFly() {
        super(Hyperglide.CATEGORY, "control-fly-",
            "Provides controlled flight with automatic rocket boosting."
        );
    }

    /**
     * Resets flight state and captures the current view orientation.
     */
    @Override
    public void onActivate() {
        this.reset();
        if (this.mc.player == null) return;

        this.view.yaw = this.mc.player.getYaw();
        this.view.pitch = this.mc.player.getPitch();

        this.motion.yaw = this.view.yaw;
        this.motion.pitch = this.view.pitch;
        this.motion.altitude = this.mc.player.getY();
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
     * Updates boost, takeoff and active flight control.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!Client.ready()) return;

        this.update();
        this.input.pulse();

        if (!this.mc.player.isGliding()) {
            this.restore();
            this.clear();
            this.takeoff();
            return;
        }

        this.jump = 0;
        this.input.reset();

        if (this.halted()) return;
        Vec3d input = this.direction();

        this.view(input);
        this.control(input);
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

        if (this.motion.brake) {
            this.motion.brake = false;
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
        if (!Client.ready() || this.boost.automatic || !this.mc.player.isGliding() ||
            !(event.packet instanceof PlayerInteractItemC2SPacket packet)) {
            return;
        }

        ItemStack stack = this.mc.player.getStackInHand(packet.getHand());
        if (!stack.isOf(Items.FIREWORK_ROCKET)) return;

        this.await();
        this.duration(stack);
    }

    //endregion

    //region State management

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
     * Resets all runtime flight, camera, boost and rotation state.
     */
    private void reset() {
        this.clear();

        this.jump = 0;
        this.input.reset();

        this.motion.yaw = 0.0F;
        this.motion.pitch = 0.0F;
        this.motion.altitude = 0.0;

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
        this.boost.endtime = 0;

        this.boost.pending = false;
        this.boost.automatic = false;
        this.boost.rocket = null;

        this.motion.leveling = false;
        this.motion.steering = false;

        this.motion.input = 0;
        this.motion.dir = Vec3d.ZERO;
        this.motion.brake = false;

        this.turn.active = false;
    }

    //endregion

    //region Flight control

    /**
     * Processes movement, steering, pitch control and rocket use.
     *
     * @param input current movement direction
     */
    private void control(Vec3d input) {
        if (input.lengthSquared() < epsilon) {
            this.rest();
            return;
        }

        int state = this.state();
        boolean redirect =
            this.motion.input != 0 &&
            this.motion.input != state;

        this.motion.input = state;

        boolean manual =
            this.mc.options.jumpKey.isPressed() ||
            this.mc.options.sneakKey.isPressed();

        Vec3d steer = this.steer(input.normalize());
        this.turn(steer);

        boolean boosted = this.active();
        this.aim(steer, boosted, manual);

        boolean launch = this.launch(steer, boosted, redirect);
        if (launch) this.prepare(manual);

        this.mc.player.setYaw(this.motion.yaw);
        this.mc.player.setPitch(this.motion.pitch);

        this.rotate(launch);
    }

    /**
     * Tracks steering changes and marks sharp turns for braking.
     *
     * @param direction requested steering direction
     */
    private void turn(Vec3d direction) {
        if (this.motion.dir.lengthSquared() >= epsilon &&
            this.sharp(this.motion.dir, direction)) {
            this.motion.brake = true;
        }
        this.motion.dir = direction;
    }

    /**
     * Keeps the view normal and renews boost while stopped.
     */
    private void rest() {
        this.motion.leveling = false;
        this.motion.steering = false;

        this.motion.yaw = this.mc.player.getYaw();
        this.motion.pitch = this.mc.player.getPitch();

        if (!this.renew()) return;

        this.await();
        this.rocket(this.motion.yaw, this.motion.pitch);
    }

    /**
     * Stops movement after idle input is confirmed.
     *
     * @param event player movement event
     */
    private void stop(PlayerMoveEvent event) {
        Player.velocity(event, Vec3d.ZERO);
    }

    /**
     * Limits horizontal movement to the configured maximum.
     *
     * @param event player movement event
     * @param dir normalized flight direction
     */
    private void limit(PlayerMoveEvent event, Vec3d dir) {
        double horizontal = event.movement.horizontalLength();

        double maximum = this.maximum(dir);
        if (horizontal <= maximum) return;

        Vec3d flat = this.flat(event.movement, maximum, horizontal);
        Vec3d velocity = new Vec3d(flat.x, event.movement.y, flat.z);

        Player.velocity(event, velocity);
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
            double scale = amount / horizontal;
            return new Vec3d(movement.x * scale, 0.0, movement.z * scale);
        }
        return Vec3d.fromPolar(0.0F, this.motion.yaw).multiply(amount);
    }

    //endregion

    //region Boost management

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
    }

    /**
     * Expires pending launches and removes inactive tracked rockets.
     */
    private void update() {
        if (this.boost.pending &&
            this.mc.player.age > this.boost.expiry) {
            this.boost.pending = false;
        }

        if (this.boost.rocket != null &&
            !this.boost.rocket.isAlive()) {
            this.boost.rocket = null;
        }
    }

    /**
     * Tracks a pending rocket until it appears or times out.
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
    private void duration(ItemStack stack) {
        FireworksComponent fireworks = stack.get(DataComponentTypes.FIREWORKS);

        int duration = fireworks == null ? 1 : fireworks.flightDuration();
        double time = Math.max(0.0, (duration + 1) * 500.0 - this.offset.get());

        int ticks = Math.max(1, (int) Math.ceil(time / 50.0));
        this.boost.endtime = this.mc.player.age + ticks;
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
     * Checks whether a rocket is pending or still boosting.
     *
     * @return true when another rocket should not be launched
     */
    private boolean busy() {
        return this.boost.pending || this.active()
            || this.boost.endtime > this.mc.player.age;
    }

    /**
     * Checks whether a stopped flight should renew its boost.
     *
     * @return true when the current boost is about to expire
     */
    private boolean renew() {
        return this.boost.endtime > 0
            && this.mc.player.age >= this.boost.endtime - 1
            && !this.boost.pending && this.stocked();
    }

    //endregion

    //region Takeoff and steering

    /**
     * Calculates movement input relative to the camera direction.
     *
     * @return combined movement direction
     */
    public Vec3d direction() {
        float yaw = this.view.active ?
            this.view.yaw : this.mc.player.getYaw();

        Vec3d forward = Vec3d.fromPolar(0.0F, yaw);
        Vec3d right = Vec3d.fromPolar(0.0F, yaw + 90.0F);

        Vec3d direction = Vec3d.ZERO;

        if (this.mc.options.forwardKey.isPressed()) {
            direction = direction.add(forward);
        }

        if (this.mc.options.backKey.isPressed()) {
            direction = direction.subtract(forward);
        }

        if (this.mc.options.rightKey.isPressed()) {
            direction = direction.add(right);
        }

        if (this.mc.options.leftKey.isPressed()) {
            direction = direction.subtract(right);
        }

        if (this.forward.get() &&
            direction.lengthSquared() < epsilon) {
            direction = forward;
        }

        if (this.mc.options.jumpKey.isPressed()) {
            direction = direction.add(0.0, 1.0, 0.0);
        }

        if (this.mc.options.sneakKey.isPressed()) {
            direction = direction.add(0.0, -1.0, 0.0);
        }

        return direction;
    }

    /**
     * Retrieves the current movement input state as a bitmask.
     *
     * @return a bitmask containing the active movement inputs
     */
    private int state() {
        int state = 0;

        state |= this.mc.options.forwardKey.isPressed() ? 1 : 0;
        state |= this.mc.options.backKey.isPressed() ? 2 : 0;
        state |= this.mc.options.rightKey.isPressed() ? 4 : 0;
        state |= this.mc.options.leftKey.isPressed() ? 8 : 0;
        state |= this.mc.options.jumpKey.isPressed() ? 16 : 0;
        state |= this.mc.options.sneakKey.isPressed() ? 32 : 0;

        return state != 0 ? state : this.forward.get() ? 1 : 0;
    }

    /**
     * Starts automatic takeoff after jump is held long enough.
     */
    private void takeoff() {
        if (!this.starter.get() || !this.flight.available()) {
            this.jump = 0;
            this.input.reset();
            return;
        }

        if (this.input.active()) return;

        if (!this.mc.options.jumpKey.isPressed() ||
            this.mc.player.isOnGround()) {
            this.jump = 0;
            return;
        }

        if (++this.jump >= this.timer.get()) {
            this.input.start();
        }
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
            if (!this.motion.steering) {
                this.motion.yaw = this.mc.player.getYaw();
                this.motion.steering = true;
            }
            return input;
        }

        this.motion.steering = true;
        this.motion.yaw = (float) (Math.toDegrees(
            Math.atan2(input.z, input.x)) - 90.0
        );

        Vec3d flat = Vec3d.fromPolar(0.0F, this.motion.yaw);
        flat = flat.multiply(length);

        return flat.add(0.0, input.y, 0.0).normalize();
    }

    /**
     * Checks whether the movement direction changes sharply.
     *
     * @param first previous movement direction
     * @param second requested movement direction
     * @return true when the turn requires a brake tick
     */
    private boolean sharp(Vec3d first, Vec3d second) {
        if (first.horizontalLength() < epsilon &&
            second.horizontalLength() < epsilon) {
            return false;
        }

        double prev = first.length(), next = second.length();
        if (prev < epsilon || next < epsilon) return false;

        double dot = first.dotProduct(second);
        return dot / (prev * next) < sharp - epsilon;
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

    //endregion

    //region Altitude control

    /**
     * Selects manual pitch control or automatic altitude leveling.
     *
     * @param dir normalized movement direction
     * @param boosted whether an active rocket is boosting the player
     * @param manual whether vertical movement input is controlling pitch
     */
    private void aim(Vec3d dir, boolean boosted, boolean manual) {
        if (manual) {
            this.motion.leveling = false;
            this.motion.altitude = this.mc.player.getY();
            this.motion.pitch = this.angle(dir);
            return;
        }

        if (!this.motion.leveling) {
            this.motion.altitude = this.mc.player.getY();
            this.motion.leveling = true;
        }

        this.motion.pitch = this.level(
            this.mc.player.getVelocity(), boosted
        );
    }

    /**
     * Finds the pitch that most closely maintains the target altitude.
     *
     * @param velocity current player velocity
     * @param boosted whether rocket acceleration should be predicted
     * @return selected leveling pitch
     */
    private float level(Vec3d velocity, boolean boosted) {
        double error = this.motion.altitude - this.mc.player.getY();
        double desired = MathHelper.clamp(error * gain, -0.2, 0.2);

        Choice choice = new Choice(
            this.motion.pitch, Double.POSITIVE_INFINITY
        );

        choice = this.search(velocity, boosted, desired,
            -bound, 5.0F, 16, choice
        );

        choice = this.search(velocity, boosted, desired,
            choice.pitch() - 5.0F, 0.5F, 20, choice
        );

        choice = this.search(velocity, boosted, desired,
            choice.pitch() - 0.5F, 0.05F, 20, choice
        );

        return choice.pitch();
    }

    /**
     * Searches pitch values for the best leveling result.
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
     * Scores a leveling pitch from predicted flight behavior.
     *
     * @param velocity current player velocity
     * @param pitch candidate pitch
     * @param desired desired vertical velocity
     * @param boosted whether rocket acceleration should be predicted
     * @return candidate score
     */
    private double score(Vec3d velocity, float pitch, double desired, boolean boosted) {
        Vec3d next = this.predict(velocity, this.motion.yaw, pitch, boosted);
        double score = Math.abs(next.y - desired);

        score += Math.max(0.0,
            this.minimum.get() / ticks - next.horizontalLength()
        ) * 0.01;

        score += Math.abs(pitch - this.motion.pitch) * 1.0E-5;
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
            changed = true;
            float yaw = this.motion.yaw, pitch = this.motion.pitch;
            Rotations.rotate(yaw, pitch, priority, () -> this.rocket(yaw, pitch));
        } else if (changed) {
            Rotations.rotate(this.motion.yaw, this.motion.pitch, priority);
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
                this.motion.yaw - this.turn.yaw)) > 0.05F ||
            Math.abs(
                this.motion.pitch - this.turn.pitch) > 0.05F;
    }

    /**
     * Stores the most recently synchronized flight rotation.
     */
    private void remember() {
        this.turn.yaw = this.motion.yaw;
        this.turn.pitch = this.motion.pitch;
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
            this.motion.yaw, this.motion.pitch, boosted
        );

        return next.horizontalLength() < Math.min(
            this.minimum.get() / ticks, this.maximum(dir)
        );
    }

    /**
     * Marks a rocket launch as pending and adjusts leveling.
     *
     * @param manual whether vertical movement input is controlling pitch
     */
    private void prepare(boolean manual) {
        this.await();
        if (manual) return;

        this.motion.pitch = this.level(
            this.mc.player.getVelocity(), true
        );
    }

    /**
     * Requests a firework at the controlled rotation.
     *
     * @param yaw controlled flight yaw
     * @param pitch controlled flight pitch
     */
    private void rocket(float yaw, float pitch) {
        if (!Client.ready() || !Client.interaction()) {
            this.cancel();
            return;
        }

        if (!this.flight.request(() -> this.firework(yaw, pitch))) {
            this.cancel();
        }
    }

    /**
     * Sends a firework and updates the current boost timer.
     *
     * @param yaw controlled flight yaw
     * @param pitch controlled flight pitch
     * @return true when the firework packet was sent
     */
    private boolean firework(float yaw, float pitch) {
        ItemStack stack = this.stack();
        if (!stack.isOf(Items.FIREWORK_ROCKET)) {
            return false;
        }

        this.boost.automatic = true;

        try {
            if (Elytra.firework(yaw, pitch)) {
                this.duration(stack);
            } else {
                return false;
            }
        } finally {
            this.boost.automatic = false;
        }

        this.boost.expiry = this.mc.player.age + timeout;
        return true;
    }

    /**
     * Cancels the current pending rocket launch.
     */
    private void cancel() {
        this.boost.pending = false;
    }

    //endregion

    //region Camera control

    /**
     * Activates and initializes the independent camera rotation.
     *
     * @return true when camera control is available
     */
    public boolean view() {
        return this.view(this.direction());
    }

    /**
     * Activates camera control for the current movement input.
     *
     * @param input current movement direction
     * @return true when camera control is available
     */
    private boolean view(Vec3d input) {
        if (!this.isActive() ||
            this.mc.player == null ||
            !this.mc.player.isGliding()) {
            return false;
        }

        if (input.lengthSquared() < epsilon) {
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
     * @param mx horizontal mouse movement
     * @param my vertical mouse movement
     */
    public void look(double mx, double my) {
        this.view.yaw += (float) (mx * 0.15);

        this.view.pitch = MathHelper.clamp(
            this.view.pitch + (float) (my * 0.15), -90.0F, 90.0F
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

    //region Utilities and validation

    /**
     * Checks whether Elytra Tweaks currently stops movement.
     *
     * @return true while collision avoidance is stopping movement
     */
    private boolean halted() {
        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        return tweaks != null && tweaks.halted();
    }

    /**
     * Checks whether a firework rocket is available in the hotbar.
     *
     * @return true when a rocket is available
     */
    private boolean stocked() {
        return this.stack().isOf(Items.FIREWORK_ROCKET);
    }

    /**
     * Finds the firework stack that controlled flight can use.
     *
     * @return available firework stack, or empty when unavailable
     */
    private ItemStack stack() {
        ItemStack main = this.mc.player.getMainHandStack();
        if (main.isOf(Items.FIREWORK_ROCKET)) return main;

        ItemStack offhand = this.mc.player.getOffHandStack();
        if (offhand.isOf(Items.FIREWORK_ROCKET)) return offhand;

        int slot = Hotbar.find(Items.FIREWORK_ROCKET);
        return slot >= 0 ? Hotbar.stack(slot) : ItemStack.EMPTY;
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
     * Stores the movement state used by controlled flight.
     */
    private static class Motion {
        private int input;
        private Vec3d dir;

        private boolean leveling;
        private boolean steering;
        private boolean brake;

        private double altitude;

        private float yaw;
        private float pitch;
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
     * Stores pending and active rocket boost state.
     */
    private static class Boost {
        private int expiry;
        private int endtime;

        private boolean pending;
        private boolean automatic;

        private FireworkRocketEntity rocket;
    }

    //endregion
}
