package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.Baritone;
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
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import java.util.*;

public class SelfTrapper extends Module {
    private static final double edge = 1.0E-4;
    private static final int verify = 5;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup control = this.settings.createGroup("Control");
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final BlockFilter filter = new BlockFilter(
        this.general, "Blocks used by Self Trapper."
    );

    private final Setting<Boolean> face = this.general.add(new BoolSetting.Builder()
        .name("open-face")
        .description("Leaves the player's face level open.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> centering = this.general.add(new BoolSetting.Builder()
        .name("center")
        .description("Moves the player to the closest block center.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delay = this.control.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between full placement cycles.")
        .defaultValue(7)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> batch = this.control.add(new BoolSetting.Builder()
        .name("batch-mode")
        .description("Places multiple blocks in each placement cycle.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> count = this.control.add(new IntSetting.Builder()
        .name("place-count")
        .description("Maximum blocks placed in each placement cycle.")
        .defaultValue(9)
        .min(2)
        .sliderMax(10)
        .visible(this.batch::get)
        .build()
    );

    private final Setting<Boolean> dynamic = this.control.add(new BoolSetting.Builder()
        .name("dynamic-mode")
        .description("Scales the delay to the remaining number of blocks.")
        .defaultValue(true)
        .visible(this.batch::get)
        .build()
    );

    private final Setting<Integer> timeout = this.control.add(new IntSetting.Builder()
        .name("retry-timeout")
        .description("Delay in ticks before retrying a failed placement.")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Render box = new Render(
        this.visuals,
        "Renders queued block placements.",
        "How queued placements are rendered.",
        "The fill color of queued placements.",
        "The outline color of queued placements.",
        new SettingColor(255, 255, 255, 32),
        new SettingColor(255, 255, 255, 255)
    );

    private final LinkedHashSet<BlockPos> wanted = new LinkedHashSet<>();
    private final LinkedHashSet<BlockPos> queue = new LinkedHashSet<>();

    private final Map<BlockPos, Integer> pending = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> waiting = new LinkedHashMap<>();

    private Vec3d target;
    private int timer;
    private int tick;

    private boolean centered;
    private boolean moving;

    public SelfTrapper() {
        super(Hyperglide.CATEGORY, "self-trapper",
            "Surrounds the player with queued block placements."
        );
    }

    /**
     * Prepares centering or immediate trapping state.
     */
    @Override
    public void onActivate() {
        this.reset();
        this.centered = !this.centering.get();
        this.timer = this.delay.get();
    }

    /**
     * Stops forced movement and clears all runtime state.
     */
    @Override
    public void onDeactivate() {
        this.stop();
        this.reset();
    }

    //region Event handlers

    /**
     * Updates centering, queues, retries and block placement.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!Client.interaction()) return;

        this.tick++;
        if (this.align()) return;

        this.collect();
        this.update();
        this.place();
    }

    /**
     * Renders queued, pending and delayed block positions.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void render(Render3DEvent event) {
        if (!this.box.enabled() || this.mc.world == null) return;

        LinkedHashSet<BlockPos> boxes = new LinkedHashSet<>(this.queue);

        boxes.addAll(this.pending.keySet());
        boxes.addAll(this.waiting.keySet());

        for (BlockPos pos : boxes) {
            if (this.wanted.contains(pos) && Placement.open(pos)) {
                this.box.box(event, pos);
            }
        }
    }

    //endregion

    //region State and centering

    /**
     * Handles the centering phase before trapping begins.
     *
     * @return true while centering is still being handled
     */
    private boolean align() {
        if (this.centered) return false;

        if (this.center()) {
            this.timer = this.delay.get();
        }

        return true;
    }

    /**
     * Moves the player toward the selected block center.
     *
     * @return true when the player fits inside the target block
     */
    private boolean center() {
        if (this.target == null && !this.seek()) {
            return false;
        }

        if (this.arrived()) {
            this.finish();
            return true;
        }

        this.move();
        return false;
    }

    /**
     * Selects the closest safe block center.
     *
     * @return true when a target was found
     */
    private boolean seek() {
        this.target = this.nearest();
        if (this.target != null) return true;

        this.error("Unable to find a safe block center.");
        this.toggle();
        return false;
    }

    /**
     * Applies movement toward the selected block center.
     */
    private void move() {
        double dx = this.target.x - this.mc.player.getX();
        double dz = this.target.z - this.mc.player.getZ();

        float yaw = this.mc.player.getYaw();
        Vec3d forward = Vec3d.fromPolar(0.0F, yaw);
        Vec3d right = Vec3d.fromPolar(0.0F, yaw + 90.0F);

        double front = dx * forward.x + dz * forward.z;
        double side = dx * right.x + dz * right.z;

        Baritone.clear();

        if (front > edge) Baritone.forward(true);
        else if (front < -edge) Baritone.back(true);

        if (side > edge) Baritone.right(true);
        else if (side < -edge) Baritone.left(true);

        Baritone.sprint(true);
        this.mc.player.setSprinting(true);

        this.moving = true;
    }

    /**
     * Finds the closest collision-free block center for the player.
     *
     * @return the closest valid center, or null if not exists
     */
    private Vec3d nearest() {
        Box box = this.mc.player.getBoundingBox();

        int minx = MathHelper.floor(box.minX + edge);
        int minz = MathHelper.floor(box.minZ + edge);
        int maxx = MathHelper.floor(box.maxX - edge);
        int maxz = MathHelper.floor(box.maxZ - edge);

        double px = this.mc.player.getX();
        double py = this.mc.player.getY();
        double pz = this.mc.player.getZ();

        Vec3d best = null;
        double distance = Double.MAX_VALUE;

        for (int mx = minx; mx <= maxx; mx++) {
            for (int mz = minz; mz <= maxz; mz++) {
                double cx = mx + 0.5, cz = mz + 0.5;
                double dx = cx - px, dz = cz - pz;

                double current = dx * dx + dz * dz;
                if (current >= distance) continue;

                if (!this.supported(box, mx, mz)) continue;
                if (!this.safe(box, cx, cz)) continue;

                best = new Vec3d(cx, py, cz);
                distance = current;
            }
        }

        return best;
    }

    /**
     * Checks whether a candidate column supports the player.
     *
     * @param box player's current bounding box
     * @param px candidate block X coordinate
     * @param pz candidate block Z coordinate
     * @return true when the candidate matches the player's height
     */
    private boolean supported(Box box, int px, int pz) {
        int level = MathHelper.floor(box.minY - edge);
        BlockPos pos = new BlockPos(px, level, pz);

        BlockState state = this.mc.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(this.mc.world, pos);
        if (shape.isEmpty()) return false;

        double top = level + shape.getMax(Axis.Y);
        return Math.abs(top - box.minY) <= 0.01;
    }

    /**
     * Checks whether the player can occupy a position without collisions.
     *
     * @param box player's current bounding box
     * @param px candidate center X coordinate
     * @param pz candidate center Z coordinate
     * @return true when the candidate is collision-free
     */
    private boolean safe(Box box, double px, double pz) {
        double ox = px - this.mc.player.getX();
        double oz = pz - this.mc.player.getZ();

        return this.mc.world.isSpaceEmpty(
            this.mc.player, box.offset(ox, 0.0, oz)
        );
    }

    /**
     * Checks whether the player's hitbox fits inside the target block.
     *
     * @return true when centering is complete
     */
    private boolean arrived() {
        if (this.target == null) return false;
        Box box = this.mc.player.getBoundingBox();

        double px = 0.5 - (box.maxX - box.minX) / 2.0 - edge;
        double pz = 0.5 - (box.maxZ - box.minZ) / 2.0 - edge;

        double dx = Math.abs(this.target.x - this.mc.player.getX());
        double dz = Math.abs(this.target.z - this.mc.player.getZ());

        return dx <= Math.max(0.01, px) && dz <= Math.max(0.01, pz);
    }

    /**
     * Finishes centering, clears movement inputs and removes velocity.
     */
    private void finish() {
        Baritone.clear();

        Vec3d velocity = this.mc.player.getVelocity();
        this.mc.player.setVelocity(0.0, velocity.y, 0.0);
        this.mc.player.setSprinting(true);

        this.centered = true;
        this.moving = false;
    }

    /**
     * Clears forced Baritone movement inputs.
     */
    private void stop() {
        if (this.moving) {
            Baritone.clear();
            this.moving = false;
        }
    }

    /**
     * Clears all queues, timers and centering state.
     */
    private void reset() {
        this.wanted.clear();
        this.queue.clear();
        this.pending.clear();
        this.waiting.clear();

        this.target = null;
        this.timer = 0;
        this.tick = 0;
        this.centered = false;
        this.moving = false;
    }

    //endregion

    //region Trap structure

    /**
     * Builds the side walls and roof around the player's hitbox.
     */
    private void collect() {
        this.wanted.clear();

        Set<BlockPos> set = new HashSet<>();
        Box box = this.mc.player.getBoundingBox();

        BlockPos min = new BlockPos(
            MathHelper.floor(box.minX + edge),
            MathHelper.floor(box.minY + edge),
            MathHelper.floor(box.minZ + edge)
        );

        BlockPos max = new BlockPos(
            MathHelper.floor(box.maxX - edge),
            MathHelper.floor(box.maxY - edge),
            MathHelper.floor(box.maxZ - edge)
        );

        this.walls(set, box, min, max);
        this.roof(set, box, min, max);
        this.order(set);
    }

    /**
     * Builds the side walls around the player's hitbox.
     *
     * @param set set receiving trap positions
     * @param box player's bounding box
     * @param min minimum occupied block position
     * @param max maximum occupied block position
     */
    private void walls(Set<BlockPos> set, Box box, BlockPos min, BlockPos max) {
        int face = MathHelper.floor(this.mc.player.getEyeY());

        for (int py = min.getY(); py <= max.getY(); py++) {
            if (this.face.get() && py == face) continue;

            for (int px = min.getX(); px <= max.getX(); px++) {
                this.add(set, new BlockPos(px, py, min.getZ() - 1), box);
                this.add(set, new BlockPos(px, py, max.getZ() + 1), box);
            }

            for (int pz = min.getZ(); pz <= max.getZ(); pz++) {
                this.add(set, new BlockPos(min.getX() - 1, py, pz), box);
                this.add(set, new BlockPos(max.getX() + 1, py, pz), box);
            }
        }
    }

    /**
     * Builds the roof above the player's hitbox.
     *
     * @param set set receiving trap positions
     * @param box player's bounding box
     * @param min minimum occupied block position
     * @param max maximum occupied block position
     */
    private void roof(Set<BlockPos> set, Box box, BlockPos min, BlockPos max) {
        int py = max.getY() + 1;
        for (int px = min.getX(); px <= max.getX(); px++) {
            for (int pz = min.getZ(); pz <= max.getZ(); pz++) {
                this.add(set, new BlockPos(px, py, pz), box);
            }
        }
    }

    /**
     * Sorts trap positions by distance and stable coordinates.
     *
     * @param set collected trap positions
     */
    private void order(Set<BlockPos> set) {
        List<BlockPos> list = new ArrayList<>(set);

        list.sort(Comparator.comparingDouble(
            (BlockPos pos) -> this.distance(pos))
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));

        this.wanted.addAll(list);
    }

    /**
     * Adds a trap position that does not intersect the player.
     *
     * @param set set receiving valid trap positions
     * @param pos candidate block position
     * @param box player's bounding box
     */
    private void add(Set<BlockPos> set, BlockPos pos, Box box) {
        pos = pos.toImmutable();
        if (!new Box(pos).intersects(box)) set.add(pos);
    }

    /**
     * Calculates distance from the player to a trap position.
     *
     * @param pos block position
     * @return squared distance to the position
     */
    private double distance(BlockPos pos) {
        return Vec3d.ofCenter(pos).squaredDistanceTo(
            this.mc.player.getBoundingBox().getCenter()
        );
    }

    //endregion

    //region Queue management

    /**
     * Refreshes queued, pending and delayed trap positions.
     */
    private void update() {
        this.clean();
        this.verify();
        this.promote();
        this.fill();
    }

    /**
     * Removes positions that are no longer required or replaceable.
     */
    private void clean() {
        this.queue.removeIf(pos ->
            !this.wanted.contains(pos) ||
            !Placement.open(pos)
        );

        this.pending.entrySet().removeIf(entry ->
            !this.wanted.contains(entry.getKey()) ||
            !Placement.open(entry.getKey())
        );

        this.waiting.entrySet().removeIf(entry ->
            !this.wanted.contains(entry.getKey()) ||
            !Placement.open(entry.getKey())
        );
    }

    /**
     * Verifies pending placements and retries failed positions.
     */
    private void verify() {
        Iterator<Map.Entry<BlockPos, Integer>>
            iterator = this.pending.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (this.tick < entry.getValue()) continue;

            BlockPos pos = entry.getKey();
            iterator.remove();

            if (this.wanted.contains(pos) && Placement.open(pos)) {
                this.retry(pos);
            }
        }
    }

    /**
     * Moves expired retry entries back into the active queue.
     */
    private void promote() {
        Iterator<Map.Entry<BlockPos, Integer>>
            iterator = this.waiting.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (this.tick < entry.getValue()) continue;

            BlockPos pos = entry.getKey();
            iterator.remove();

            if (this.wanted.contains(pos) && Placement.open(pos)) {
                this.queue.add(pos);
            }
        }
    }

    /**
     * Adds untracked required positions to the active queue.
     */
    private void fill() {
        for (BlockPos pos : this.wanted) {
            if (Placement.open(pos) && !this.tracked(pos)) {
                this.queue.add(pos);
            }
        }
    }

    /**
     * Checks whether a position is already being handled.
     *
     * @param pos position to check
     * @return true when the position is queued, pending or waiting
     */
    private boolean tracked(BlockPos pos) {
        return this.queue.contains(pos)
            || this.pending.containsKey(pos)
            || this.waiting.containsKey(pos);
    }

    //endregion

    //region Placement control

    /**
     * Processes the next placement cycle.
     */
    private void place() {
        int amount = this.amount();
        if (amount == 0) return;

        if (++this.timer < this.pace(amount)) return;
        if (this.cycle(amount)) this.timer = 0;
    }

    /**
     * Attempts the requested number of queued placements.
     *
     * @param amount maximum placements to process
     * @return true when at least one placement was attempted
     */
    private boolean cycle(int amount) {
        boolean attempted = false;

        for (int idx = 0; idx < amount; idx++) {
            BlockPos pos = this.next();
            if (pos == null) break;

            int slot = Hotbar.block(this.filter, pos);
            if (slot == -1) {
                this.queue.addFirst(pos);
                break;
            }

            attempted = true;

            if (Placement.place(pos, slot)) {
                this.pending.put(pos, this.tick + verify);
            } else {
                this.retry(pos);
            }
        }

        return attempted;
    }

    /**
     * Counts queued blocks available for the next placement cycle.
     *
     * @return the number of blocks available for the next cycle
     */
    private int amount() {
        int amount = 0;

        int limit = this.count.get();
        if (!this.batch.get()) limit = 1;

        for (BlockPos pos : this.queue) {
            if (!this.ready(pos)) continue;
            if (++amount >= limit) break;
        }

        return amount;
    }

    /**
     * Calculates the delay required before processing a cycle.
     *
     * @param amount number of blocks in the upcoming cycle
     * @return the required delay in ticks
     */
    private int pace(int amount) {
        if (!this.dynamic.get()) return this.delay.get();

        double max = this.batch.get() ? this.count.get() : 1.0;
        double delay = this.delay.get() * amount / max;
        return Math.max(1, (int) Math.ceil(delay));
    }

    /**
     * Removes and returns the next valid position from the queue.
     *
     * @return the next ready position, or null when none is available
     */
    private BlockPos next() {
        while (!this.queue.isEmpty()) {
            BlockPos pos = this.queue.removeFirst();
            if (this.ready(pos)) return pos;
        }
        return null;
    }

    /**
     * Delays another attempt for a failed block position.
     *
     * @param pos failed block position
     */
    private void retry(BlockPos pos) {
        if (!Placement.open(pos)) return;
        if (!this.wanted.contains(pos)) return;

        int timer = this.tick + this.timeout.get();
        this.waiting.put(pos.toImmutable(), timer);
    }

    /**
     * Checks whether a position can currently be processed.
     *
     * @param pos position to check
     * @return true when the position is required and open
     */
    private boolean ready(BlockPos pos) {
        return this.wanted.contains(pos)
            && Placement.open(pos)
            && !this.pending.containsKey(pos)
            && !this.waiting.containsKey(pos);
    }

    //endregion
}
