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
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.ArrayDeque;
import java.util.Deque;

public class BounceFly extends Module {
    private static final double range = 5.0;
    private static final double stop = 0.2;
    private static final int grid = 10;

    private static final int reach = 2;
    private static final int ahead = 8;
    private static final int span = 192;

    private static final int delay = 3;
    private static final int wait = 20;
    private static final int warmup = 20;

    private static final int[] dxs = {0, -1, -1, -1, 0, 1, 1, 1};
    private static final int[] dzs = {1, 1, 0, -1, -1, -1, 0, 1};

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Boolean> acceleration = this.general.add(new BoolSetting.Builder()
        .name("acceleration")
        .description("Uses dynamic pitch to build up speed while bouncing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> pitch = this.general.add(new DoubleSetting.Builder()
        .name("standard-pitch")
        .description("Camera pitch used while bouncing in standard mode.")
        .defaultValue(72.4)
        .min(0.0)
        .sliderMax(90.0)
        .decimalPlaces(2)
        .visible(() -> !this.acceleration.get())
        .build()
    );

    private final Setting<Double> threshold = this.general.add(new DoubleSetting.Builder()
        .name("fall-threshold")
        .description("Downward velocity used to switch acceleration pitch.")
        .defaultValue(0.193)
        .min(0.0)
        .sliderMax(1.0)
        .decimalPlaces(3)
        .visible(this.acceleration::get)
        .build()
    );

    private final Setting<Boolean> obstacle = this.general.add(new BoolSetting.Builder()
        .name("obstacle-passer")
        .description("Uses Baritone to pass through detected obstacles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> avoid = this.general.add(new BoolSetting.Builder()
        .name("avoid-collisions")
        .description("Uses raycasts to detect obstacles along the highway.")
        .defaultValue(true)
        .visible(this.obstacle::get)
        .build()
    );

    private final Setting<Boolean> dig = this.general.add(new BoolSetting.Builder()
        .name("mine-obstacles")
        .description("Clears obstacles on the path before resuming travel.")
        .defaultValue(false)
        .visible(() -> this.obstacle.get() && this.avoid.get())
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

    private final Deque<BlockPos> blocks = new ArrayDeque<>();

    private MiningTweaks mining;
    private BlockPos focus;
    private BlockPos goal;

    private int px;
    private int pz;
    private int dx;
    private int dz;

    private double nx;
    private double nz;

    private int slow;
    private int warm;
    private int jump;
    private int level;

    private Vec3d last;

    private boolean pass;
    private boolean launch;
    private boolean enabled;
    private boolean started;

    public BounceFly() {
        super(Hyperglide.CATEGORY, "bounce-fly",
            "Uses elytra bouncing for fast highway travel."
        );
    }

    /**
     * Prepares flight state and required modules.
     */
    @Override
    public void onActivate() {
        if (!Client.ready()) return;

        this.level = this.mc.player.getBlockY();

        this.face();
        this.center();
        this.reset();

        this.mining = Modules.get().get(MiningTweaks.class);

        if (this.mining != null) {
            this.enabled = this.mining.isActive();
            if (!this.enabled) this.mining.toggle();
        }

        this.clear();
        this.pass = false;
        this.started = false;
    }

    /**
     * Stops active movement and restores module state.
     */
    @Override
    public void onDeactivate() {
        this.release();

        if (this.pass) Baritone.stop();
        this.restore();
        this.reset();
        this.clear();

        this.mining = null;
        this.enabled = false;
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
        Vec3d vel = this.mc.player.getVelocity();

        float pitch = !this.acceleration.get()
            ? this.pitch.get().floatValue()
            : vel.y > -this.threshold.get()
            ? 90.0F : 0.0F;

        this.mc.player.setPitch(pitch);

        this.launch(vel);
        this.mc.player.setSprinting(true);

        if (!this.takeoff()) return;
        if (this.blocked()) return;

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
        this.last = null;
    }

    /**
     * Clears obstacle passing state.
     */
    private void clear() {
        this.blocks.clear();
        this.goal = null;
        this.focus = null;
    }

    /**
     * Keeps Mining Tweaks enabled while mining obstacles.
     */
    private void setup() {
        if (this.mining != null && !this.mining.isActive()) {
            this.mining.toggle();
        }
    }

    /**
     * Restores the Mining Tweaks state from before activation.
     */
    private void restore() {
        if (this.mining != null && !this.enabled &&
            this.mining.isActive()) {
            this.mining.toggle();
        }
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

    //region Travel control

    /**
     * Manages active obstacle passing and mining.
     *
     * @return true while obstacle handling is active
     */
    private boolean pathing() {
        if (!this.obstacle.get()) {
            if (this.pass) {
                Baritone.stop();
                this.reset();
                this.clear();
                this.pass = false;
            }
            return false;
        }

        if (this.goal != null) {
            this.release();
            this.reset();
            this.mine();
            return true;
        }

        if (this.pass && Baritone.pathing()) {
            this.release();
            this.reset();
            return true;
        }

        if (this.pass) {
            this.rotate();
            this.reset();
            this.clear();
            this.pass = false;
            this.started = true;
        }

        return false;
    }

    /**
     * Starts obstacle handling when a collision is detected.
     *
     * @return true when obstacle handling was started
     */
    private boolean blocked() {
        if (!this.obstacle.get() || !this.avoid.get()) {
            return false;
        }

        Vec3d hit = this.collision();
        if (hit == null) return false;

        this.mc.player.stopGliding();
        BlockPos goal = this.trace(hit);

        if (this.mining != null && this.dig.get() &&
            !this.blocks.isEmpty()) {
            this.mine(goal);
        } else {
            this.path(goal);
        }

        return true;
    }

    /**
     * Detects stalled movement and starts recovery pathing.
     */
    private void stuck() {
        Vec3d pos = API.pos(this.mc.player);

        if (this.last == null) {
            this.last = pos;
            return;
        }

        double dx = pos.x - this.last.x;
        double dz = pos.z - this.last.z;
        double moved = Math.hypot(dx, dz);

        this.last = pos;
        this.warm++;

        if (!this.obstacle.get() || this.warm < warmup) {
            this.slow = 0;
            return;
        }

        if (moved < stop) this.slow++;
        else this.slow = 0;

        if (this.slow > wait) this.path();
    }

    //endregion

    //region Obstacle pathing

    /**
     * Starts obstacle mining before pathing onward.
     *
     * @param goal safe pathing goal after the obstacle
     */
    private void mine(BlockPos goal) {
        this.release();
        this.reset();
        this.setup();

        this.pass = true;
        this.goal = goal;
        this.focus = null;
        this.started = false;

        this.mine();
    }

    /**
     * Queues obstacles for mining while approaching them.
     */
    private void mine() {
        this.blocks.removeIf(pos -> !this.solid(pos));

        if (this.blocks.isEmpty()) {
            BlockPos goal = this.goal;
            this.goal = null;
            this.focus = null;

            if (goal != null) this.path(goal);
            return;
        }

        BlockPos pos = this.blocks.peekFirst();
        if (!pos.equals(this.focus)) {
            this.focus = pos;
            Baritone.near(pos, 2);
        }

        this.setup();

        for (BlockPos block : this.blocks) {
            if (!this.mining.reachable(block, range)) {
                continue;
            }

            this.mining.mine(block, Direction.UP);
        }
    }

    /**
     * Starts recovery pathing from the current highway position.
     */
    private void path() {
        this.path(this.base());
    }

    /**
     * Starts Baritone pathing slightly beyond the selected goal.
     *
     * @param pos pathing goal
     */
    private void path(BlockPos pos) {
        this.release();
        this.reset();
        this.clear();

        this.pass = true;
        this.started = false;

        Baritone.walk(pos.add(
            this.dx * reach, 0,
            this.dz * reach
        ));
    }

    //endregion

    //region Obstacle scanning

    /**
     * Finds a safe pathing goal beyond the detected obstacle.
     *
     * @param hit detected collision point
     * @return pathing goal
     */
    private BlockPos trace(Vec3d hit) {
        this.blocks.clear();

        BlockPos start = this.base();
        BlockPos point = this.point(hit.x, hit.z);

        int distance = Math.max(
            Math.abs(point.getX() - start.getX()),
            Math.abs(point.getZ() - start.getZ())
        );

        BlockPos pos = start;
        BlockPos last = null;
        int clear = 0;

        for (int idx = 0; idx < span; idx++) {
            if (this.step(pos)) {
                last = pos;
                clear = 0;
            } else if (++clear >= ahead &&
                (last != null || idx >= distance)) {
                return last != null ? last : pos;
            }
            pos = pos.add(this.dx, 0, this.dz);
        }

        return start.add(this.dx * span, 0, this.dz * span);
    }

    /**
     * Checks the player-sized space for one trail step.
     *
     * @param pos center block of the trail step
     * @return true when the step is obstructed
     */
    private boolean step(BlockPos pos) {
        boolean blocked = this.column(pos);
        if (this.dig.get()) this.collect(pos);

        if (this.dx != 0 && this.dz != 0) {
            BlockPos px = pos.add(this.dx, 0, 0);
            BlockPos pz = pos.add(0, 0, this.dz);

            blocked |= this.column(px);
            blocked |= this.column(pz);

            if (this.dig.get()) {
                this.collect(px);
                this.collect(pz);
            }
        }

        return blocked;
    }

    /**
     * Records breakable blocks from a trail column.
     *
     * @param pos lower block of the column
     */
    private void collect(BlockPos pos) {
        for (int py = 0; py <= 1; py++) {
            BlockPos block = pos.up(py);
            if (!this.solid(block) || this.blocks.contains(block)) {
                continue;
            }

            BlockState state = this.mc.world.getBlockState(block);
            if (state.getHardness(this.mc.world, block) < 0) {
                continue;
            }

            this.blocks.addLast(block);
        }
    }

    //endregion

    //region Collision detection

    /**
     * Finds the closest obstacle along the highway direction.
     *
     * @return closest collision point, or null when undetected
     */
    private Vec3d collision() {
        Vec3d front = new Vec3d(this.nx, 0, this.nz);
        Vec3d side = new Vec3d(-front.z, 0, front.x);
        Vec3d vel = this.mc.player.getVelocity();

        double scan = vel.horizontalLength();
        scan = Math.max(1.0, scan * this.ticks.get());

        double level = Math.floor(this.mc.player.getY());

        double width = this.mc.player.getWidth() / 2.0;
        width *= Math.abs(side.x) + Math.abs(side.z);

        Vec3d closest = null;
        double distance = Double.MAX_VALUE;

        for (int idx = -1; idx <= 1; idx++) {
            for (double py = 0.5; py <= 1.5; py++) {
                Vec3d start = new Vec3d(
                    this.mc.player.getX(), level + py,
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
                    closest = hit.getPos();
                    distance = current;
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

        double len = Math.hypot(this.dx, this.dz);

        this.nx = this.dx / len;
        this.nz = this.dz / len;
    }

    /**
     * Stores the snapped center line of the current highway.
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
     * Finds the current block on the stored highway line.
     *
     * @return current center block
     */
    private BlockPos base() {
        return this.point(
            this.mc.player.getX(),
            this.mc.player.getZ()
        );
    }

    /**
     * Projects a position onto the stored highway line.
     *
     * @param px world X coordinate
     * @param pz world Z coordinate
     * @return projected center block
     */
    private BlockPos point(double px, double pz) {
        return this.dx == 0 || this.dz == 0 ?
            this.cardinal(px, pz) : this.diagonal(px, pz);
    }

    /**
     * Projects a position onto a cardinal highway line.
     *
     * @param px world X coordinate
     * @param pz world Z coordinate
     * @return projected center block
     */
    private BlockPos cardinal(double px, double pz) {
        if (this.dx == 0) return this.block(this.px, pz);
        return this.block(px, this.pz);
    }

    /**
     * Projects a position onto the diagonal highway line.
     *
     * @param px world X coordinate
     * @param pz world Z coordinate
     * @return projected center block
     */
    private BlockPos diagonal(double px, double pz) {
        boolean equal = this.dx == this.dz;
        double axis = equal ? px + pz : px - pz;

        double bx = axis + this.px;
        double bz = equal ? axis - this.px : this.px - axis;

        return this.block(bx / 2.0, bz / 2.0);
    }

    //endregion

    //region Utilities and validation

    /**
     * Creates a block position on the highway level.
     *
     * @param px world X coordinate
     * @param pz world Z coordinate
     * @return block position on the highway level
     */
    private BlockPos block(double px, double pz) {
        return new BlockPos(
            (int) Math.round(px), this.level,
            (int) Math.round(pz)
        );
    }

    /**
     * Checks a two-block-high trail column for collision.
     *
     * @param pos lower block of the column
     * @return true when either block can collide
     */
    private boolean column(BlockPos pos) {
        return this.solid(pos) || this.solid(pos.up());
    }

    /**
     * Checks whether a block has a collision shape.
     *
     * @param pos block position to check
     * @return true when the block can collide with the player
     */
    private boolean solid(BlockPos pos) {
        BlockState state = this.mc.world.getBlockState(pos);
        return !state.getCollisionShape(this.mc.world, pos).isEmpty();
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
