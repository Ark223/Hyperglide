package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.BlockFilter;
import hyperglide.utilities.Client;
import hyperglide.utilities.Hotbar;
import hyperglide.utilities.Placement;
import hyperglide.utilities.Render;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

public class Scaffolding extends Module {
    private static final double extend = 1.25;
    private static final double step = 0.25;
    private static final double reach = 2.0;

    private static final int life = 10;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final BlockFilter filter = new BlockFilter(
        this.general, "Blocks used by Scaffolding."
    );

    private final Setting<Integer> delay = this.general.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between placements.")
        .defaultValue(2)
        .min(1)
        .sliderMax(3)
        .build()
    );

    private final Setting<Boolean> dynamic = this.general.add(new BoolSetting.Builder()
        .name("dynamic-mode")
        .description("Uses a one-tick delay when close to the edge.")
        .defaultValue(true)
        .build()
    );

    private final Render box = new Render(
        this.visuals,
        "Renders queued placements.",
        "How queued placements are rendered.",
        "The fill color of queued placements.",
        "The outline color of queued placements.",
        new SettingColor(255, 255, 255, 32),
        new SettingColor(255, 255, 255, 255)
    );

    private final LinkedHashSet<BlockPos> queue = new LinkedHashSet<>();
    private final Map<BlockPos, Integer> marks = new HashMap<>();
    private final BlockPos.Mutable scan = new BlockPos.Mutable();

    private int timer;
    private int tick;
    private int level;

    public Scaffolding() {
        super(Hyperglide.CATEGORY, "scaffolding",
            "Places queued blocks under and ahead of the player."
        );
    }

    /**
     * Resets runtime state and prepares the active scaffold layer.
     */
    @Override
    public void onActivate() {
        this.reset();
        this.timer = this.delay.get();
        this.level = this.mc.player == null ? 0 : this.layer();
    }

    /**
     * Clears all runtime state.
     */
    @Override
    public void onDeactivate() {
        this.reset();
    }

    //region Event handlers

    /**
     * Updates the scaffold layer, queues and block placement.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Client.interaction()) return;

        this.tick++;

        this.update();
        this.clean();
        this.collect();

        if (++this.timer < this.pace()) return;

        BlockPos pos = this.next();
        if (pos == null) return;

        int slot = Hotbar.block(this.filter, pos);
        if (slot == -1) {
            this.add(pos, true);
            return;
        }

        if (!Placement.place(pos, slot)) return;

        this.timer = 0;
        this.marks.put(pos, this.tick);
    }

    /**
     * Renders queued and recently placed block positions.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.box.enabled() || this.mc.world == null) {
            return;
        }

        for (BlockPos pos : this.queue) {
            this.box.box(event, pos);
        }

        for (BlockPos pos : this.marks.keySet()) {
            if (!this.queue.contains(pos)) {
                this.box.box(event, pos);
            }
        }
    }

    //endregion

    //region Scaffold control

    /**
     * Clears queued positions, placement marks and timers.
     */
    private void reset() {
        this.queue.clear();
        this.marks.clear();

        this.timer = 0;
        this.tick = 0;
    }

    /**
     * Updates the active scaffold layer and clears queued positions.
     */
    private void update() {
        int level = this.layer();
        if (this.level == level) {
            return;
        }

        this.level = level;
        this.queue.clear();
    }

    /**
     * Removes invalid queued positions and expired placement marks.
     */
    private void clean() {
        this.queue.removeIf(pos -> !this.valid(pos));

        this.marks.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            return !this.open(pos) || this.far(pos)
                || this.tick - entry.getValue() > life;
        });
    }

    /**
     * Collects scaffold positions under and ahead of the player.
     */
    private void collect() {
        this.add(BlockPos.ofFloored(
            this.mc.player.getX(), this.level,
            this.mc.player.getZ()
        ), true);

        Vec3d move = this.move();
        if (move.lengthSquared() == 0) return;

        for (double idx = step; idx <= extend + 0.001; idx += step) {
            this.add(BlockPos.ofFloored(
                this.mc.player.getX() + move.x * idx, this.level,
                this.mc.player.getZ() + move.z * idx
            ), false);
        }
    }

    /**
     * Adds a valid untracked position to the queue.
     *
     * @param pos candidate block position
     * @param first whether the position should be queued first
     */
    private void add(BlockPos pos, boolean first) {
        pos = pos.toImmutable();

        if (!this.valid(pos) ||
            this.queue.contains(pos) ||
            this.marks.containsKey(pos)) return;

        if (first) this.queue.addFirst(pos);
        else this.queue.addLast(pos);
    }

    /**
     * Removes and returns the next valid position from the queue.
     *
     * @return next valid position, or null when none is available
     */
    private BlockPos next() {
        while (!this.queue.isEmpty()) {
            BlockPos pos = this.queue.removeFirst();
            if (this.valid(pos)) return pos;
        }
        return null;
    }

    //endregion

    //region Placement control

    /**
     * Calculates the delay required before the next placement.
     *
     * @return required delay in ticks
     */
    private int pace() {
        return this.dynamic.get()
            && this.edge() ? 1 : this.delay.get();
    }

    /**
     * Checks whether the player reaches an open scaffold block.
     *
     * @return true when part of the player is over an open edge
     */
    private boolean edge() {
        Box box = this.mc.player.getBoundingBox();

        int minx = (int) Math.floor(box.minX);
        int maxx = (int) Math.floor(Math.nextDown(box.maxX));
        int minz = (int) Math.floor(box.minZ);
        int maxz = (int) Math.floor(Math.nextDown(box.maxZ));

        for (int px = minx; px <= maxx; px++) {
            for (int pz = minz; pz <= maxz; pz++) {
                this.scan.set(px, this.level, pz);
                if (this.open(this.scan)) return true;
            }
        }

        return false;
    }

    /**
     * Checks whether two blocks above a scaffold position are clear.
     *
     * @param pos scaffold position to check
     * @return true when the player has enough vertical space
     */
    private boolean space(BlockPos pos) {
        for (int level = 1; level <= 2; level++) {
            this.scan.set(pos.getX(), pos.getY() + level, pos.getZ());

            BlockState state = this.mc.world.getBlockState(this.scan);
            if (!state.getCollisionShape(this.mc.world, this.scan).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    //endregion

    //region Player positioning

    /**
     * Calculates the normalized movement direction from pressed keys.
     *
     * @return normalized movement direction, or zero when stationary
     */
    private Vec3d move() {
        Vec3d move = Vec3d.ZERO;
        float yaw = this.mc.player.getYaw();

        if (this.mc.options.forwardKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw));
        }

        if (this.mc.options.backKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw + 180));
        }

        if (this.mc.options.leftKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw - 90));
        }

        if (this.mc.options.rightKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw + 90));
        }

        return move.lengthSquared() == 0 ? Vec3d.ZERO : move.normalize();
    }

    /**
     * Calculates the block layer directly below the player.
     *
     * @return Y coordinate of the scaffold layer
     */
    private int layer() {
        boolean ground = this.mc.player.isOnGround();
        double offset = ground ? 0.01 : 1.0;

        double px = this.mc.player.getX();
        double pz = this.mc.player.getZ();

        double py = this.mc.player.getY() - offset;
        return BlockPos.ofFloored(px, py, pz).getY();
    }

    //endregion

    //region Utilities and validation

    /**
     * Checks whether a position is outside placement reach.
     *
     * @param pos position to check
     * @return true when the position is too far from the player
     */
    private boolean far(BlockPos pos) {
        double px = pos.getX() + 0.5 - this.mc.player.getX();
        double pz = pos.getZ() + 0.5 - this.mc.player.getZ();
        return px * px + pz * pz > reach * reach;
    }

    /**
     * Checks whether a position is replaceable on the scaffold layer.
     *
     * @param pos position to check
     * @return true when the position is open on the active layer
     */
    private boolean open(BlockPos pos) {
        return pos.getY() == this.level && Placement.open(pos);
    }

    /**
     * Checks whether a position can be used as a scaffold candidate.
     *
     * @param pos position to check
     * @return true when the position is open, clear and within reach
     */
    private boolean valid(BlockPos pos) {
        return this.open(pos) && this.space(pos) && !this.far(pos);
    }

    //endregion
}
