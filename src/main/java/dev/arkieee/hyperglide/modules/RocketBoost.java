package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.utilities.Baritone;
import dev.arkieee.hyperglide.utilities.Client;
import dev.arkieee.hyperglide.utilities.Elytra;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

public class RocketBoost extends Module {
    private static final double epsilon = 1.0E-12;
    private static final double rescale = 1.65;
    private static final double base = 1.7;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Double> maximum = this.general.add(new DoubleSetting.Builder()
        .name("max-boost")
        .description("Maximum extra speed added above normal rocket speed.")
        .defaultValue(10.0)
        .min(0.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Double> delta = this.general.add(new DoubleSetting.Builder()
        .name("safety-delta")
        .description("Shortens fallback rocket time for ping variation.")
        .defaultValue(0.1)
        .min(0.0)
        .sliderMax(1.0)
        .build()
    );

    private FireworkRocketEntity rocket;
    private Vec3d velocity = Vec3d.ZERO;
    private Vec3d target;

    private int expiry;
    private float yaw;
    private float pitch;

    private boolean moved = true;
    private boolean replace;
    private boolean seen;

    public RocketBoost() {
        super(Hyperglide.CATEGORY, "rocket-boost",
            "Provides improved rocket acceleration while gliding."
        );
    }

    /**
     * Initializes movement and rotation state.
     */
    @Override
    public void onActivate() {
        this.reset();
        if (this.mc.player == null) return;

        this.yaw = this.mc.player.getYaw();
        this.pitch = this.mc.player.getPitch();
        this.velocity = this.mc.player.getVelocity();
    }

    /**
     * Clears all runtime boost state.
     */
    @Override
    public void onDeactivate() {
        this.reset();
    }

    //region Event handlers

    /**
     * Calculates the strongest allowed rocket velocity.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        this.target = null;
        this.replace = false;

        if (!Client.ready()) return;

        if (!this.mc.player.isGliding()) {
            this.idle();
            return;
        }

        if (!this.active() || this.controlled()) return;

        Vec3d aim = this.mc.player.getRotationVec(1.0F);
        double[] bounds = this.bounds(this.velocity, aim);
        Vec3d target = this.point(bounds, aim);

        this.target = this.limit(target);
        this.replace = true;

        this.mc.player.setVelocity(this.target);
    }

    /**
     * Applies the selected rocket velocity to player movement.
     *
     * @param event player movement event
     */
    @EventHandler
    private void move(PlayerMoveEvent event) {
        if (!Client.ready() || this.target == null ||
            event.type != MovementType.SELF ||
            !this.mc.player.isGliding() ||
            this.controlled()) return;

        Vec3d target = this.target;
        this.target = null;

        ((IVec3d) event.movement).meteor$set(
            target.x, target.y, target.z
        );

        this.mc.player.setVelocity(target);
    }

    /**
     * Tracks used rockets and movement state sent to the server.
     *
     * @param event outgoing packet event
     */
    @EventHandler
    private void packet(PacketEvent.Send event) {
        if (!Client.ready()) return;

        if (event.packet instanceof PlayerInteractItemC2SPacket packet) {
            if (!this.mc.player.isGliding()) return;

            ItemStack stack = this.mc.player.getStackInHand(packet.getHand());
            if (!stack.isOf(Items.FIREWORK_ROCKET)) return;

            this.expiry = this.mc.player.age;

            FireworksComponent fireworks = stack.get(DataComponentTypes.FIREWORKS);
            int flight = fireworks == null ? 1 : fireworks.flightDuration();

            double seconds = Math.max(0.0, flight * 0.5 + 0.5 - this.delta.get());
            this.expiry += Math.max(1, (int) Math.ceil(seconds * 20.0));
            return;
        }

        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) {
            return;
        }

        if (packet.changesPosition()) {
            double px = this.mc.player.prevX;
            double py = this.mc.player.prevY;
            double pz = this.mc.player.prevZ;

            this.velocity = new Vec3d(
                packet.getX(px) - px,
                packet.getY(py) - py,
                packet.getZ(pz) - pz
            );
        }

        if (packet.changesLook()) {
            this.yaw = packet.getYaw(this.yaw);
            this.pitch = packet.getPitch(this.pitch);
        }

        this.moved = packet.changesPosition();
    }

    //endregion

    //region State management

    /**
     * Clears the current rocket boost.
     */
    private void idle() {
        this.target = null;
        this.rocket = null;
        this.expiry = 0;

        this.replace = false;
        this.seen = false;
    }

    /**
     * Clears all boost states.
     */
    private void reset() {
        this.idle();

        this.yaw = 0.0F;
        this.pitch = 0.0F;
        this.velocity = Vec3d.ZERO;

        this.moved = true;
    }

    //endregion

    //region Boost control

    /**
     * Tracks the firework currently boosting the player.
     *
     * @param rocket player-owned firework rocket
     */
    public void track(FireworkRocketEntity rocket) {
        if (!this.isActive() || !Client.ready() ||
            !this.mc.player.isGliding() ||
            this.controlled()) return;

        this.rocket = rocket;
        this.seen = true;
    }

    /**
     * Checks whether vanilla firework acceleration should be suppressed.
     *
     * @return true when rocket boosting should replace vanilla acceleration
     */
    public boolean boost() {
        return this.isActive() && Client.ready()
            && this.mc.player.isGliding()
            && !this.controlled() && this.replace;
    }

    /**
     * Checks whether a tracked rocket is still boosting the player.
     *
     * @return true while the boost is still active
     */
    private boolean active() {
        if (this.rocket != null && this.rocket.isAlive()) {
            return true;
        }

        if (this.seen && this.mc.player != null &&
            this.mc.player.age <= this.expiry) {
            return true;
        }

        this.rocket = null;
        this.seen = false;
        return false;
    }

    /**
     * Limits the selected velocity to the configured maximum boost.
     *
     * @param velocity selected rocket velocity
     * @return speed-limited velocity
     */
    private Vec3d limit(Vec3d velocity) {
        double maximum = base + this.maximum.get();
        double square = velocity.lengthSquared();

        if (square <= maximum * maximum) return velocity;
        return velocity.multiply(maximum / Math.sqrt(square));
    }

    /**
     * Checks whether another module owns player movement.
     *
     * @return true while another module controls movement
     */
    private boolean controlled() {
        ControlFly module = Modules.get().get(ControlFly.class);
        if (module != null && module.isActive()) return true;

        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        if (tweaks != null && tweaks.halted()) return true;

        return Baritone.elytra();
    }

    //endregion

    //region Boost prediction

    /**
     * Calculates the allowed firework velocity bounds.
     *
     * @param velocity last movement velocity
     * @param rotation current rocket direction
     * @return minimum and maximum velocity bounds
     */
    private double[] bounds(Vec3d velocity, Vec3d rotation) {
        Vec3d simulated = Elytra.glide(
            velocity, rotation, this.mc.player.getFinalGravity()
        );

        Vec3d current = rotation;
        Vec3d previous = Vec3d.fromPolar(this.pitch, this.yaw);

        double skip = this.moved ? 0.05 : 0.0;

        double minx = Math.min(-skip, current.x) + Math.min(-skip, previous.x);
        double miny = Math.min(-skip, current.y) + Math.min(-skip, previous.y);
        double minz = Math.min(-skip, current.z) + Math.min(-skip, previous.z);

        double maxx = Math.max(skip, current.x) + Math.max(skip, previous.x);
        double maxy = Math.max(skip, current.y) + Math.max(skip, previous.y);
        double maxz = Math.max(skip, current.z) + Math.max(skip, previous.z);

        minx = Math.max(-rescale, minx * rescale);
        miny = Math.max(-rescale, miny * rescale);
        minz = Math.max(-rescale, minz * rescale);

        maxx = Math.min(rescale, maxx * rescale);
        maxy = Math.min(rescale, maxy * rescale);
        maxz = Math.min(rescale, maxz * rescale);

        return new double[] {
            simulated.x + Math.min(0.0, minx - velocity.x),
            simulated.y + Math.min(0.0, miny - velocity.y),
            simulated.z + Math.min(0.0, minz - velocity.z),
            simulated.x + Math.max(0.0, maxx - velocity.x),
            simulated.y + Math.max(0.0, maxy - velocity.y),
            simulated.z + Math.max(0.0, maxz - velocity.z)
        };
    }

    /**
     * Finds the farthest point inside a velocity box toward the aim direction.
     *
     * @param bounds minimum and maximum velocity bounds
     * @param aim desired rocket direction
     * @return farthest allowed velocity
     */
    private Vec3d point(double[] bounds, Vec3d aim) {
        Vec3d center = new Vec3d(
            (bounds[0] + bounds[3]) / 2.0,
            (bounds[1] + bounds[4]) / 2.0,
            (bounds[2] + bounds[5]) / 2.0
        );

        double far = Double.POSITIVE_INFINITY;

        for (int axis = 0; axis < 3; axis++) {
            double origin = axis == 0 ? center.x :
                axis == 1 ? center.y : center.z;

            double dir = axis == 0 ? aim.x :
                axis == 1 ? aim.y : aim.z;

            if (Math.abs(dir) < epsilon) continue;

            double edge = dir > 0.0 ?
                bounds[axis + 3] : bounds[axis];

            far = Math.min(far, (edge - origin) / dir);
        }

        return center.add(aim.multiply(far));
    }

    //endregion
}
