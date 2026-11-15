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
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.function.BooleanSupplier;

public class ControlFly extends Module {
    private static final double epsilon = 1.0E-3;
    private static final double floor = -128.0;
    private static final double ticks = 20.0;

    private static final double gain = 0.3;
    private static final double push = 0.05;
    private static final double shift = 0.15;

    private static final int timeout = 4;

    private final SettingGroup movement = this.settings.createGroup("Movement");
    private final SettingGroup automation = this.settings.createGroup("Automation");

    private final Setting<Mode> mode = this.movement.add(new EnumSetting.Builder<Mode>()
        .name("control-mode")
        .description("Controls how flight responds to camera pitch.")
        .defaultValue(Mode.Horizontal)
        .build()
    );

    private final Setting<Double> speed = this.movement.add(new DoubleSetting.Builder()
        .name("motion-speed")
        .description("Controlled flight speed in blocks per second.")
        .defaultValue(34.0)
        .min(10.0)
        .sliderMax(34.0)
        .build()
    );

    private final Setting<Double> penalty = this.movement.add(new DoubleSetting.Builder()
        .name("ascent-penalty")
        .description("Reduces motion speed while flying upward.")
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
        .sliderMax(200)
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
    private final View view = new View();

    private int jump;
    private double height = floor;

    /**
     * Controls whether forward flight follows camera pitch.
     */
    private enum Mode {
        Horizontal,
        Directional
    }

    public ControlFly() {
        super(Hyperglide.CATEGORY, "control-fly-",
            "Provides controlled flight with automatic rocket boosting."
        );
    }

    /**
     * Resets flight state and saves camera orientation.
     */
    @Override
    public void onActivate() {
        this.reset();

        if (this.mc.player != null) {
            this.view.yaw = this.mc.player.getYaw();
            this.view.pitch = this.mc.player.getPitch();
        }
    }

    /**
     * Restores camera orientation and resets flight state.
     */
    @Override
    public void onDeactivate() {
        this.restore();
        this.reset();
    }

    //region Event handlers

    /**
     * Updates takeoff, flight steering and rocket boosts.
     *
     * @param event tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!Client.ready()) return;

        this.update();
        this.input.pulse();

        if (!this.mc.player.isGliding()) {
            this.prepare();
        } else {
            this.glide();
        }
    }

    /**
     * Controls flight direction and limits horizontal speed.
     *
     * @param event movement event
     */
    @EventHandler
    private void move(PlayerMoveEvent event) {
        if (!Client.ready() || this.halted() ||
            event.type != MovementType.SELF ||
            !this.mc.player.isGliding()) return;

        Vec3d motion = this.motion(event.movement);
        Player.velocity(event, motion);
    }

    /**
     * Tracks firework rockets launched by the player.
     *
     * @param event outgoing packet
     */
    @EventHandler
    private void packet(PacketEvent.Send event) {
        if (!Client.ready() || !this.mc.player.isGliding() ||
            this.boost.automatic || !(event.packet instanceof
            PlayerInteractItemC2SPacket packet)) return;

        Hand hand = packet.getHand();
        ItemStack stack = this.mc.player.getStackInHand(hand);
        if (!stack.isOf(Items.FIREWORK_ROCKET)) return;

        this.await();
        this.duration(stack);
    }

    //endregion

    //region State management

    /**
     * Checks whether automatic takeoff is in progress.
     *
     * @return true while takeoff input is active
     */
    public boolean deploying() {
        return this.isActive() && this.input.active();
    }

    /**
     * Checks whether takeoff is holding the jump key.
     *
     * @return forced jump state
     */
    public boolean jumping() {
        return this.input.pressed();
    }

    /**
     * Resets flight state and camera control.
     */
    private void reset() {
        this.clear();

        this.jump = 0;
        this.height = floor;
        this.input.reset();

        this.view.active = false;
        this.view.yaw = 0.0F;
        this.view.pitch = 0.0F;
    }

    /**
     * Clears active and pending rocket boosts.
     */
    private void clear() {
        this.boost.expiry = 0;
        this.boost.endtime = 0;

        this.boost.pending = false;
        this.boost.automatic = false;
        this.boost.rocket = null;
    }

    //endregion

    //region Flight control

    /**
     * Resets the glide state while preparing for takeoff.
     */
    private void prepare() {
        this.height = floor;
        this.restore();
        this.takeoff();
    }

    /**
     * Updates steering and boosts during an active glide.
     */
    private void glide() {
        this.jump = 0;
        this.input.reset();

        if (this.halted()) {
            this.height = floor;
            return;
        }

        Vec3d dir = this.direction();
        this.view(dir);
        this.control(dir);
    }

    /**
     * Calculates flight movement and altitude correction.
     *
     * @param motion original movement
     * @return adjusted movement
     */
    private Vec3d motion(Vec3d motion) {
        double level = this.level(motion.y);

        Vec3d direction = this.direction();
        if (direction.lengthSquared() < epsilon) {
            return new Vec3d(0.0, level, 0.0);
        }

        direction = direction.normalize();
        return this.horizontal(motion, direction, level);
    }

    /**
     * Limits horizontal speed and handles ceiling collisions.
     *
     * @param motion original movement
     * @param direction normalized flight direction
     * @param level corrected vertical movement
     * @return adjusted movement
     */
    private Vec3d horizontal(Vec3d motion, Vec3d direction, double level) {
        double penalty = this.penalty.get();
        penalty *= Math.max(0.0, direction.y);

        double extra = this.mc.player.verticalCollision
            && !this.mc.player.isOnGround() ? push : 0.0;

        double length = direction.horizontalLength();
        if (length < epsilon) {
            Vec3d zero = new Vec3d(0.0, level, 0.0);
            if (extra <= 0.0) return zero;

            direction = Vec3d.fromPolar(0.0F, this.yaw());
            length = direction.horizontalLength();
        }

        double speed = this.speed.get() - penalty;
        double dist = motion.horizontalLength() + extra;
        double amount = Math.min(dist, speed / ticks);

        double px = direction.x * amount / length;
        double pz = direction.z * amount / length;
        return new Vec3d(px, level, pz);
    }

    /**
     * Holds the current altitude during horizontal flight.
     *
     * @param motion original vertical movement
     * @return corrected vertical movement
     */
    private double level(double motion) {
        double level = this.mc.player.getY();

        if (this.mode.get() != Mode.Horizontal ||
            this.mc.options.jumpKey.isPressed() ||
            this.mc.options.sneakKey.isPressed()) {
            this.height = floor;
            return motion;
        }

        if (this.height == floor) this.height = level;
        double correction = (this.height - level) * gain;
        return MathHelper.clamp(correction, -shift, shift);
    }

    /**
     * Steers the player and maintains rocket boosting.
     *
     * @param direction flight direction
     */
    private void control(Vec3d direction) {
        boolean moving = direction.lengthSquared() >= epsilon;
        Vec3d aim = moving ? direction.normalize() : Vec3d.ZERO;

        float[] angles = this.rotation(aim, moving);
        float yaw = angles[0], pitch = angles[1];

        if (moving) {
            this.mc.player.setYaw(yaw);
            this.mc.player.setPitch(pitch);
        }

        if (!this.busy() && this.stocked()) {
            this.await();
            this.rocket(yaw, pitch);
        }
    }

    /**
     * Calculates flight rotation from movement direction.
     *
     * @param aim normalized flight direction
     * @param moving whether movement input is active
     * @return flight yaw and pitch
     */
    private float[] rotation(Vec3d aim, boolean moving) {
        float yaw = this.mc.player.getYaw();
        float pitch = this.mc.player.getPitch();
        if (!moving) return new float[] { yaw, pitch };

        double length = aim.horizontalLength();
        if (length >= epsilon) {
            double angle = Math.atan2(aim.z, aim.x);
            yaw = (float) (Math.toDegrees(angle) - 90.0);
        }

        double angle = Math.atan2(aim.y, length);
        pitch = (float) -Math.toDegrees(angle);
        return new float[] { yaw, pitch };
    }

    /**
     * Calculates movement relative to the camera.
     *
     * @return combined movement direction
     */
    public Vec3d direction() {
        int state = this.state();
        float yaw = this.yaw();

        boolean free = this.mode.get() == Mode.Directional;
        float pitch = free ? this.pitch() : 0.0F;

        Vec3d dir = Vec3d.ZERO;
        Vec3d front = Vec3d.fromPolar(pitch, yaw);
        Vec3d right = Vec3d.fromPolar(0.0F, yaw + 90.0F);

        if ((state & 1) != 0) dir = dir.add(front);
        if ((state & 2) != 0) dir = dir.subtract(front);
        if ((state & 4) != 0) dir = dir.add(right);
        if ((state & 8) != 0) dir = dir.subtract(right);
        if ((state & 16) != 0) dir = dir.add(0.0, 1.0, 0.0);
        if ((state & 32) != 0) dir = dir.add(0.0, -1.0, 0.0);

        return dir;
    }

    /**
     * Reads movement keys and optional forward input.
     *
     * @return movement input bitmask
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
     * Starts gliding after holding jump while airborne.
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

    //endregion

    //region Boost management

    /**
     * Tracks the rocket currently boosting the player.
     *
     * @param rocket player rocket entity
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
     * Clears expired rocket requests and inactive rockets.
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
     * Marks a firework launch as the pending action.
     */
    private void await() {
        this.boost.pending = true;
        this.boost.expiry = this.mc.player.age + timeout;
    }

    /**
     * Records the expected duration of a rocket boost.
     *
     * @param stack firework stack
     */
    private void duration(ItemStack stack) {
        var rockets = stack.get(DataComponentTypes.FIREWORKS);

        int duration = rockets == null ? 1 : rockets.flightDuration();
        double time = (duration + 1) * 500.0 - this.offset.get();

        int ticks = Math.max(1, (int) Math.ceil(time / 50.0));
        this.boost.endtime = this.mc.player.age + ticks;
    }

    /**
     * Checks whether a rocket boost is already underway.
     *
     * @return true while a boost is ongoing
     */
    private boolean busy() {
        return this.boost.pending || this.boost.rocket
            != null && this.boost.rocket.isAlive()
            || this.boost.endtime > this.mc.player.age;
    }

    //endregion

    //region Rocket handling

    /**
     * Requests a firework using the current flight rotation.
     *
     * @param yaw flight yaw
     * @param pitch flight pitch
     */
    private void rocket(float yaw, float pitch) {
        if (!Client.ready() || !Client.interaction()) {
            this.cancel();
            return;
        }

        BooleanSupplier action = () -> this.firework(yaw, pitch);
        if (!this.flight.request(action)) this.cancel();
    }

    /**
     * Launches a firework and records its boost duration.
     *
     * @param yaw flight yaw
     * @param pitch flight pitch
     * @return true when the firework was launched
     */
    private boolean firework(float yaw, float pitch) {
        Item rocket = Items.FIREWORK_ROCKET;

        ItemStack stack = this.stack();
        if (!stack.isOf(rocket)) return false;
        if (!this.send(yaw, pitch)) return false;

        this.duration(stack);
        this.boost.expiry = this.mc.player.age + timeout;

        return true;
    }

    /**
     * Sends a firework without triggering boost detection.
     *
     * @param yaw flight yaw
     * @param pitch flight pitch
     * @return true when the firework packet was sent
     */
    private boolean send(float yaw, float pitch) {
        this.boost.automatic = true;

        try {
            return Elytra.firework(yaw, pitch);
        } finally {
            this.boost.automatic = false;
        }
    }

    /**
     * Cancels the pending request to launch a rocket.
     */
    private void cancel() {
        this.boost.pending = false;
    }

    //endregion

    //region Camera control

    /**
     * Enables camera control for the current direction.
     *
     * @return true when camera control is enabled
     */
    public boolean view() {
        return this.view(this.direction());
    }

    /**
     * Enables camera control while movement input is active.
     *
     * @param direction flight direction
     * @return true when camera control is enabled
     */
    private boolean view(Vec3d direction) {
        if (!this.isActive() || this.mc.player == null ||
            !this.mc.player.isGliding()) return false;

        if (direction.lengthSquared() < epsilon) {
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
     * Updates the camera rotation from mouse movement.
     *
     * @param mx horizontal mouse movement
     * @param my vertical mouse movement
     */
    public void look(double mx, double my) {
        float pitch = this.view.pitch + (float) (my * shift);
        this.view.pitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
        this.view.yaw += (float) (mx * shift);
    }

    /**
     * Checks whether independent camera control is active.
     *
     * @return true while independent camera control is active
     */
    public boolean camera() {
        return this.isActive() && this.mc.player != null
            && this.view.active && this.mc.player.isGliding();
    }

    /**
     * Returns the camera yaw used during active flight mode.
     *
     * @return camera yaw
     */
    public float yaw() {
        if (this.view.active) return this.view.yaw;
        return this.mc.player.getYaw();
    }

    /**
     * Returns the camera pitch used during active flight mode.
     *
     * @return camera pitch
     */
    public float pitch() {
        if (this.view.active) return this.view.pitch;
        return this.mc.player.getPitch();
    }

    /**
     * Restores the player's original camera orientation.
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
     * Checks whether collision avoidance has stopped movement.
     *
     * @return true while collision avoidance is active
     */
    private boolean halted() {
        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        return tweaks != null && tweaks.halted();
    }

    /**
     * Checks whether a firework rocket is available.
     *
     * @return true when a rocket can be used
     */
    private boolean stocked() {
        return this.stack().isOf(Items.FIREWORK_ROCKET);
    }

    /**
     * Finds an available firework rocket stack.
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
     * Stores the current independent camera rotation.
     */
    private static class View {
        private boolean active;
        private float yaw, pitch;
    }

    /**
     * Tracks the active rocket and pending boost.
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
