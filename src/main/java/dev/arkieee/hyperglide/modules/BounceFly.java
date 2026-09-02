package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.utilities.Baritone;
import dev.arkieee.hyperglide.utilities.Client;
import dev.arkieee.hyperglide.utilities.Elytra;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class BounceFly extends Module {
    private static final double stop = 0.2;
    private static final int grid = 10;
    private static final int reach = 5;

    private static final int launch = 3;
    private static final int wait = 20;
    private static final int warmup = 20;

    private final int[] dxs = {0, -1, -1, -1, 0, 1, 1, 1};
    private final int[] dzs = {1, 1, 0, -1, -1, -1, 0, 1};

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Double> pitch = this.general.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("The camera pitch used while bouncing.")
        .defaultValue(72.4)
        .min(-90.0)
        .sliderMax(90.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> obstacle = this.general.add(new BoolSetting.Builder()
        .name("obstacle-passer")
        .description("Uses Baritone to pass obstacles when movement stops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> avoid = this.general.add(new BoolSetting.Builder()
        .name("avoid-collisions")
        .description("Uses raycasts to detect obstacles and avoid collisions.")
        .defaultValue(true)
        .visible(this.obstacle::get)
        .build()
    );

    private final Setting<Integer> ticks = this.general.add(new IntSetting.Builder()
        .name("collision-ticks")
        .description("How many movement ticks ahead to scan for obstacles.")
        .defaultValue(8)
        .min(5)
        .sliderMax(10)
        .visible(() -> this.obstacle.get() && this.avoid.get())
        .build()
    );

    private int px;
    private int pz;
    private int dx;
    private int dz;

    private int slow;
    private int warm;
    private int jump;
    private int level;

    private boolean pass;
    private boolean started;

    public BounceFly() {
        super(Hyperglide.CATEGORY, "bounce-fly",
            "Uses elytra bouncing for fast highway travel."
        );
    }

    /**
     * Captures the current level and direction before flying.
     */
    @Override
    public void onActivate() {
        if (!Client.ready()) return;

        this.level = this.mc.player.getBlockY();
        this.face();
        this.center();

        this.slow = 0;
        this.warm = 0;
        this.jump = 0;

        this.pass = false;
        this.started = false;

        this.mc.player.setSprinting(false);
    }

    /**
     * Releases forced inputs, cancels active pathing and clears runtime state.
     */
    @Override
    public void onDeactivate() {
        this.mc.options.forwardKey.setPressed(false);
        this.mc.options.jumpKey.setPressed(false);

        if (this.mc.player != null) {
            this.mc.player.setSprinting(false);
        }

        if (this.pass) {
            Baritone.cancel();
        }

        this.slow = 0;
        this.warm = 0;
        this.jump = 0;

        this.pass = false;
        this.started = false;
    }

    //region Event handlers

    /**
     * Controls elytra bouncing and starts obstacle pathing when required.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Client.ready()) return;

        if (this.obstacle.get()) {
            if (this.pass && Baritone.moving()) {
                this.mc.options.forwardKey.setPressed(false);
                this.mc.options.jumpKey.setPressed(false);
                this.mc.player.setSprinting(false);

                this.slow = 0;
                this.warm = 0;
                this.jump = 0;
                return;
            }

            if (this.pass) {
                this.rotate();

                this.slow = 0;
                this.warm = 0;
                this.jump = 0;
                this.pass = false;
            }
        } else if (this.pass) {
            Baritone.cancel();

            this.slow = 0;
            this.warm = 0;
            this.jump = 0;
            this.pass = false;
        }

        if (!Elytra.equipped()) {
            this.mc.options.forwardKey.setPressed(false);
            this.mc.options.jumpKey.setPressed(false);
            this.mc.player.setSprinting(false);

            this.slow = 0;
            this.warm = 0;
            this.jump = 0;
            this.started = false;
            return;
        }

        if (!Baritone.moving()) this.rotate();
        this.mc.player.setPitch(this.pitch.get().floatValue());

        this.mc.options.forwardKey.setPressed(true);
        this.mc.options.jumpKey.setPressed(true);

        if (!this.takeoff()) return;

        if (this.obstacle.get() && this.avoid.get()) {
            Vec3d hit = this.collision();

            if (hit != null) {
                this.path(hit);
                return;
            }
        }

        this.warm++;

        if (this.obstacle.get() && this.warm >= warmup) {
            Vec3d velocity = this.mc.player.getVelocity();
            double speed = velocity.horizontalLength();

            if (speed < stop) this.slow++;
            else this.slow = 0;

            if (this.slow > wait) {
                this.path();
                return;
            }
        } else {
            this.slow = 0;
        }
    }

    //endregion

    //region Input control

    /**
     * Forces forward, jump and sprint input while bouncing.
     *
     * @param input player input state
     */
    public void input(Input input) {
        if (!this.isActive() || this.mc.player == null
            || this.pass || !Elytra.equipped()) return;

        PlayerInput state = input.playerInput;

        input.playerInput = new PlayerInput(
            true, false, state.left(), state.right(),
            true, state.sneak(), this.started
        );

        input.movementForward = 1.0F;
    }

    //endregion

    //region Takeoff control

    /**
     * Handles the initial delayed launch and later bounce restarts.
     *
     * @return true when normal bounce flight may continue
     */
    private boolean takeoff() {
        if (this.started) {
            this.mc.player.setSprinting(true);

            if (!this.mc.player.isGliding() &&
                !this.mc.player.isOnGround()) {
                this.glide();
            }

            return true;
        }

        this.slow = 0;
        this.warm = 0;
        this.mc.player.setSprinting(false);

        if (this.mc.player.isOnGround()) {
            this.jump = 0;
            return false;
        }

        this.jump++;

        if (this.jump < launch ||
            this.mc.player.getVelocity().y >= 0.0) {
            return false;
        }

        this.glide();
        this.mc.player.setSprinting(true);

        this.jump = 0;
        this.started = true;
        return true;
    }

    /**
     * Starts elytra flight.
     */
    private void glide() {
        this.mc.player.startGliding();
        Elytra.start();
    }

    //endregion

    //region Collision detection

    /**
     * Finds the closest obstacle along the current highway direction.
     *
     * @return closest collision point, or null when no obstacle is detected
     */
    private Vec3d collision() {
        Vec3d front = new Vec3d(this.dx, 0, this.dz).normalize();
        Vec3d side = new Vec3d(-front.z, 0, front.x);
        Vec3d vel = this.mc.player.getVelocity();

        double scan = vel.horizontalLength() * this.ticks.get();
        double width = this.mc.player.getWidth() / 2.0;
        width *= Math.abs(side.x) + Math.abs(side.z);

        Vec3d closest = null;
        double distance = Double.MAX_VALUE;

        for (int idx = -1; idx <= 1; idx++) {
            for (double y = 0.5; y <= 1.5; y++) {
                Vec3d start = new Vec3d(
                    this.mc.player.getX(), this.level + y,
                    this.mc.player.getZ()
                );

                start = start.add(side.multiply(width * idx));
                Vec3d end = start.add(front.multiply(scan));

                BlockHitResult hit = this.ray(start, end);
                if (hit.getType() != HitResult.Type.BLOCK) {
                    continue;
                }

                double current = start.squaredDistanceTo(hit.getPos());
                if (current < distance) {
                    distance = current;
                    closest = hit.getPos();
                }
            }
        }

        return closest;
    }

    /**
     * Raycasts between two points using block collision shapes.
     *
     * @param start raycast start position
     * @param end raycast end position
     * @return block raycast result
     */
    private BlockHitResult ray(Vec3d start, Vec3d end) {
        return this.mc.world.raycast(new RaycastContext(
            start, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, this.mc.player
        ));
    }

    //endregion

    //region Obstacle pathing

    /**
     * Starts Baritone pathing from the player's current position.
     */
    private void path() {
        this.path(this.mc.player.getPos());
    }

    /**
     * Starts Baritone pathing toward a point beyond the obstacle.
     *
     * @param point obstacle or starting reference point
     */
    private void path(Vec3d point) {
        this.mc.options.forwardKey.setPressed(false);
        this.mc.options.jumpKey.setPressed(false);

        this.slow = 0;
        this.warm = 0;
        this.jump = 0;

        this.pass = true;
        this.started = false;

        this.mc.player.setSprinting(false);
        Baritone.walk(this.goal(point));
    }

    /**
     * Calculates a pathing goal beyond an obstacle along the highway.
     *
     * @param point obstacle or starting reference point
     * @return block position used as the Baritone goal
     */
    private BlockPos goal(Vec3d point) {
        Vec3d dir = new Vec3d(this.dx, 0, this.dz);
        dir = dir.normalize();

        double ox = point.x - this.px;
        double oz = point.z - this.pz;

        double along = ox * dir.x + oz * dir.z;

        double px = this.px + dir.x * (along + reach);
        double pz = this.pz + dir.z * (along + reach);

        return new BlockPos(
            (int) Math.round(px), this.level,
            (int) Math.round(pz)
        );
    }

    //endregion

    //region Direction control

    /**
     * Stores the highway direction from the player's yaw.
     */
    private void face() {
        float yaw = this.mc.player.getYaw();

        float sector = (yaw + 22.5F) / 45.0F;
        int face = MathHelper.floor(sector) & 7;

        this.dx = this.dxs[face];
        this.dz = this.dzs[face];
    }

    /**
     * Finds the snapped center line of the current highway.
     */
    private void center() {
        double px = this.mc.player.getX();
        double pz = this.mc.player.getZ();

        if (this.dx == 0) {
            this.px = this.snap(px);
            this.pz = 0;
        } else if (this.dz == 0) {
            this.px = 0;
            this.pz = this.snap(pz);
        } else {
            boolean equal = this.dx == this.dz;
            this.px = this.snap(equal ? px - pz : px + pz);
            this.pz = 0;
        }
    }

    /**
     * Rotates the player toward the stored highway direction.
     */
    private void rotate() {
        float yaw = (float) Math.toDegrees(
            Math.atan2(-this.dx, this.dz)
        );

        this.mc.player.setYaw(yaw);
        this.mc.player.setHeadYaw(yaw);
        this.mc.player.setBodyYaw(yaw);
    }

    /**
     * Rounds a coordinate to the nearest highway grid position.
     *
     * @param value coordinate to round
     * @return coordinate aligned to the highway grid
     */
    private int snap(double value) {
        return (int) Math.round(value / grid) * grid;
    }

    //endregion
}
