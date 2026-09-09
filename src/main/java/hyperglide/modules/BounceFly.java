package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.API;
import hyperglide.utilities.Baritone;
import hyperglide.utilities.Client;
import hyperglide.utilities.Elytra;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
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

    private static final int wait = 20;
    private static final int warmup = 20;
    private static final int delay = 3;

    private static final int[] dxs = {0, -1, -1, -1, 0, 1, 1, 1};
    private static final int[] dzs = {1, 1, 0, -1, -1, -1, 0, 1};

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
    private boolean launch;
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
        this.reset();

        this.pass = false;
        this.started = false;
    }

    /**
     * Releases movement, stops pathing and clears flight state.
     */
    @Override
    public void onDeactivate() {
        this.release();

        if (this.pass) Baritone.cancel();
        this.reset();

        this.pass = false;
        this.started = false;
    }

    //region Event handlers

    /**
     * Controls normal bounce movement and obstacle handling.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Client.ready() || !this.available()) {
            return;
        }

        if (!Baritone.pathing()) this.rotate();

        float pitch = this.pitch.get().floatValue();
        this.mc.player.setPitch(pitch);

        this.launch(this.mc.player.getVelocity());
        this.mc.player.setSprinting(true);

        if (!this.takeoff() || this.blocked()) {
            return;
        }

        this.stuck();
    }

    //endregion

    //region State management

    /**
     * Checks whether bounce input should be forced.
     *
     * @return true while normal bounce movement is active
     */
    public boolean enabled() {
        return this.isActive() && this.mc.player != null
            && !this.pass && Elytra.equipped();
    }

    /**
     * Returns the current bounce jump input.
     *
     * @return forced jump state
     */
    public boolean jumping() {
        return this.launch;
    }

    /**
     * Clears bounce movement state.
     */
    private void reset() {
        this.slow = 0;
        this.warm = 0;
        this.jump = 0;
        this.launch = false;
    }

    //endregion

    //region Bounce control

    /**
     * Checks whether bounce movement is available.
     *
     * @return true when Bounce Fly can control movement
     */
    private boolean available() {
        return !this.pathing() && this.equipped();
    }

    /**
     * Validates the equipped elytra and clears bounce state.
     *
     * @return true when an elytra is equipped
     */
    private boolean equipped() {
        if (Elytra.equipped()) {
            return true;
        }

        this.release();
        this.reset();

        this.started = false;
        return false;
    }

    /**
     * Updates jump input for the current bounce state.
     * 
     * @param velocity current player velocity
     */
    private void launch(Vec3d velocity) {
        if (this.mc.player.isOnGround()) {
            this.jump = 0;
            this.launch = this.started ||
                this.mc.player.isSprinting() &&
                velocity.horizontalLength() > 0.05;
            return;
        }

        if (this.mc.player.isGliding()) {
            this.jump = 0;
            this.launch = false;
            return;
        }

        if (!this.started && ++this.jump < delay) {
            this.launch = false;
            return;
        }

        this.launch = !this.launch;
    }

    /**
     * Waits for the first elytra flight before bouncing begins.
     *
     * @return true after the initial flight has started
     */
    private boolean takeoff() {
        if (this.started) return true;

        this.slow = 0;
        this.warm = 0;

        if (!this.mc.player.isGliding()) {
            return false;
        }

        this.started = true;
        return true;
    }

    /**
     * Releases forced movement and sprint state.
     */
    private void release() {
        this.mc.options.forwardKey.setPressed(false);
        this.mc.options.jumpKey.setPressed(false);

        if (this.mc.player != null) {
            this.mc.player.setSprinting(false);
        }
    }

    //endregion

    //region Obstacle passing

    /**
     * Handles transitions into and out of Baritone pathing.
     *
     * @return true while Baritone owns movement
     */
    private boolean pathing() {
        if (!this.obstacle.get()) {
            if (this.pass) {
                Baritone.cancel();
                this.reset();
                this.pass = false;
            }
            return false;
        }

        if (this.pass && Baritone.pathing()) {
            this.release();
            this.reset();
            return true;
        }

        if (this.pass) {
            this.rotate();
            this.reset();
            this.pass = false;
        }

        return false;
    }

    /**
     * Starts obstacle pathing when a collision is detected.
     *
     * @return true when pathing was started
     */
    private boolean blocked() {
        if (!this.obstacle.get() || !this.avoid.get()) {
            return false;
        }

        Vec3d hit = this.collision();
        if (hit == null) return false;

        this.path(hit);
        return true;
    }

    /**
     * Detects low movement speed and starts recovery pathing.
     */
    private void stuck() {
        this.warm++;

        if (!this.obstacle.get() || this.warm < warmup) {
            this.slow = 0;
            return;
        }

        Vec3d velocity = this.mc.player.getVelocity();
        double speed = velocity.horizontalLength();

        if (speed < stop) this.slow++;
        else this.slow = 0;

        if (this.slow > wait) this.path();
    }

    /**
     * Starts Baritone pathing from the player's position.
     */
    private void path() {
        this.path(API.pos(this.mc.player));
    }

    /**
     * Starts Baritone pathing toward a point beyond the obstacle.
     *
     * @param point obstacle or starting reference point
     */
    private void path(Vec3d point) {
        this.mc.options.forwardKey.setPressed(false);
        this.mc.options.jumpKey.setPressed(false);

        this.reset();

        this.pass = true;
        this.started = false;

        this.mc.player.setSprinting(false);
        Baritone.walk(this.goal(point));
    }

    /**
     * Calculates a goal beyond an obstacle along the highway.
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

    //region Collision detection

    /**
     * Finds the closest obstacle along the highway direction.
     *
     * @return closest collision point, or null when undetected
     */
    private Vec3d collision() {
        Vec3d front = new Vec3d(this.dx, 0, this.dz);
        front = front.normalize();

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

    //region Direction control

    /**
     * Stores the highway direction from the player's yaw.
     */
    private void face() {
        float yaw = this.mc.player.getYaw();

        float sector = (yaw + 22.5F) / 45.0F;
        int face = MathHelper.floor(sector) & 7;

        this.dx = dxs[face];
        this.dz = dzs[face];
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
