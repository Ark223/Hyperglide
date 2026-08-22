package dev.arkieee.hyperglide.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.*;

public class SelfTrapper extends Module {
    private static final double edge = 0.0001;
    private static final int verify = 5;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup control = this.settings.createGroup("Control");
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<List<Block>> blocks = this.general.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks used by Self Trapper.")
        .build()
    );

    private final Setting<Mode> mode = this.general.add(new EnumSetting.Builder<Mode>()
        .name("list-mode")
        .description("How the block list is used.")
        .defaultValue(Mode.Whitelist)
        .build()
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

    private final Setting<Integer> cooldown = this.control.add(new IntSetting.Builder()
        .name("retry-cooldown")
        .description("Delay in ticks before retrying a failed placement.")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders queued block placements.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How queued placements are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> side = this.visuals.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color of queued placements.")
        .defaultValue(new SettingColor(255, 255, 255, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> line = this.visuals.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline color of queued placements.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(this.render::get)
        .build()
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

    public enum Mode {
        Whitelist,
        Blacklist
    }

    public SelfTrapper() {
        super(Hyperglide.CATEGORY, "self-trapper",
            "Surrounds the player with queued block placements."
        );
    }

    /**
     * Resets runtime state and prepares centering or immediate trapping.
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
        if (this.mc.player == null || this.mc.world == null
            || this.mc.interactionManager == null) return;

        this.tick++;

        if (!this.centered) {
            if (!this.center()) return;
            this.timer = this.delay.get();
            return;
        }

        this.collect();
        this.clean();
        this.verify();
        this.promote();
        this.fill();

        int amount = this.amount();
        if (amount == 0 || ++this.timer < this.pace(amount)) return;

        int attempts = 0;

        for (int idx = 0; idx < amount; idx++) {
            BlockPos pos = this.next();
            if (pos == null) break;

            int slot = this.slot(pos);
            if (slot == -1) {
                this.queue.addFirst(pos);
                break;
            }

            attempts++;

            if (!this.place(pos, slot)) {
                this.retry(pos);
                continue;
            }

            this.pending.put(pos, this.tick + verify);
        }

        if (attempts > 0) this.timer = 0;
    }

    /**
     * Renders queued, pending and delayed block positions.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void render(Render3DEvent event) {
        if (!this.render.get() || this.mc.world == null) return;

        LinkedHashSet<BlockPos> boxes = new LinkedHashSet<>(this.queue);
        boxes.addAll(this.pending.keySet());
        boxes.addAll(this.waiting.keySet());

        for (BlockPos pos : boxes) {
            if (this.wanted.contains(pos) && this.open(pos)) {
                this.box(event, pos);
            }
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

    //region Player centering

    /**
     * Moves the player toward the selected block center using Baritone inputs.
     *
     * @return true when the player's hitbox fits entirely inside the target block
     */
    private boolean center() {
        if (this.target == null) {
            this.target = this.nearest();

            if (this.target == null) {
                this.error("Unable to find a safe block center.");
                this.toggle();
                return false;
            }
        }

        IBaritone baritone = this.baritone();

        if (this.arrived()) {
            this.finish(baritone);
            return true;
        }

        double dx = this.target.x - this.mc.player.getX();
        double dz = this.target.z - this.mc.player.getZ();

        Vec3d forward = Vec3d.fromPolar(0.0F, this.mc.player.getYaw());
        Vec3d right = Vec3d.fromPolar(0.0F, this.mc.player.getYaw() + 90.0F);

        double front = dx * forward.x + dz * forward.z;
        double side = dx * right.x + dz * right.z;

        IInputOverrideHandler input = baritone.getInputOverrideHandler();
        input.clearAllKeys();

        if (front > edge) input.setInputForceState(Input.MOVE_FORWARD, true);
        else if (front < -edge) input.setInputForceState(Input.MOVE_BACK, true);

        if (side > edge) input.setInputForceState(Input.MOVE_RIGHT, true);
        else if (side < -edge) input.setInputForceState(Input.MOVE_LEFT, true);

        input.setInputForceState(Input.SPRINT, true);
        this.mc.player.setSprinting(true);

        this.moving = true;
        return false;
    }

    /**
     * Finds the closest supported and collision-free block center touched by the player.
     *
     * @return the closest valid center, or null when no valid center exists
     */
    private Vec3d nearest() {
        Box box = this.mc.player.getBoundingBox();

        int minx = MathHelper.floor(box.minX + edge);
        int minz = MathHelper.floor(box.minZ + edge);
        int maxx = MathHelper.floor(box.maxX - edge);
        int maxz = MathHelper.floor(box.maxZ - edge);

        Vec3d target = null;
        double distance = Double.MAX_VALUE;

        for (int px = minx; px <= maxx; px++) {
            for (int pz = minz; pz <= maxz; pz++) {
                double centerx = px + 0.5, centerz = pz + 0.5;
                double dx = centerx - this.mc.player.getX();
                double dz = centerz - this.mc.player.getZ();
                double current = dx * dx + dz * dz;

                if (current >= distance || !this.supported(box, px, pz)
                    || !this.safe(box, centerx, centerz)) continue;

                target = new Vec3d(centerx, this.mc.player.getY(), centerz);
                distance = current;
            }
        }

        return target;
    }

    /**
     * Checks whether a candidate column supports the player at the current feet level.
     *
     * @param box player's current bounding box
     * @param x candidate block X coordinate
     * @param z candidate block Z coordinate
     * @return true when the candidate surface matches the player's feet height
     */
    private boolean supported(Box box, int x, int z) {
        int y = MathHelper.floor(box.minY - edge);
        BlockPos pos = new BlockPos(x, y, z);

        VoxelShape shape = this.mc.world.getBlockState(pos)
            .getCollisionShape(this.mc.world, pos);

        if (shape.isEmpty()) return false;

        double top = y + shape.getMax(Direction.Axis.Y);
        return Math.abs(top - box.minY) <= 0.01;
    }

    /**
     * Checks whether the player's hitbox fits inside the target block.
     *
     * @return true when centering is complete
     */
    private boolean arrived() {
        if (this.target == null) return false;
        Box box = this.mc.player.getBoundingBox();

        double px = Math.max(0.01, 0.5 - (box.maxX - box.minX) / 2.0 - edge);
        double pz = Math.max(0.01, 0.5 - (box.maxZ - box.minZ) / 2.0 - edge);

        double dx = Math.abs(this.target.x - this.mc.player.getX());
        double dz = Math.abs(this.target.z - this.mc.player.getZ());

        return dx <= px && dz <= pz;
    }

    /**
     * Checks whether the player can occupy a candidate without collisions.
     *
     * @param box player's current bounding box
     * @param x candidate center X coordinate
     * @param z candidate center Z coordinate
     * @return true when the candidate is collision-free
     */
    private boolean safe(Box box, double x, double z) {
        double ox = this.mc.player.getX();
        double oz = this.mc.player.getZ();

        Box moved = box.offset(x - ox, 0.0, z - oz);
        return this.mc.world.isSpaceEmpty(this.mc.player, moved);
    }

    /**
     * Finishes centering, clears movement inputs and removes velocity.
     *
     * @param baritone Baritone instance controlling movement
     */
    private void finish(IBaritone baritone) {
        baritone.getInputOverrideHandler().clearAllKeys();

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
        if (!this.moving) return;

        this.baritone().getInputOverrideHandler().clearAllKeys();
        this.moving = false;
    }

    //endregion

    //region Trap structure

    /**
     * Calculates the side walls and roof required around the player's hitbox.
     */
    private void collect() {
        this.wanted.clear();

        Set<BlockPos> set = new HashSet<>();
        Box box = this.mc.player.getBoundingBox();

        int minx = MathHelper.floor(box.minX + edge);
        int miny = MathHelper.floor(box.minY + edge);
        int minz = MathHelper.floor(box.minZ + edge);

        int maxx = MathHelper.floor(box.maxX - edge);
        int maxy = MathHelper.floor(box.maxY - edge);
        int maxz = MathHelper.floor(box.maxZ - edge);

        int face = MathHelper.floor(this.mc.player.getEyeY());

        for (int py = miny; py <= maxy; py++) {
            if (this.face.get() && py == face) continue;

            for (int px = minx; px <= maxx; px++) {
                this.add(set, new BlockPos(px, py, minz - 1), box);
                this.add(set, new BlockPos(px, py, maxz + 1), box);
            }

            for (int pz = minz; pz <= maxz; pz++) {
                this.add(set, new BlockPos(minx - 1, py, pz), box);
                this.add(set, new BlockPos(maxx + 1, py, pz), box);
            }
        }

        int roof = maxy + 1;

        for (int px = minx; px <= maxx; px++) {
            for (int pz = minz; pz <= maxz; pz++) {
                this.add(set, new BlockPos(px, roof, pz), box);
            }
        }

        List<BlockPos> list = new ArrayList<>(set);

        list.sort(Comparator.comparingDouble(
            (BlockPos pos) -> this.distance(pos))
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));

        this.wanted.addAll(list);
    }

    /**
     * Adds a trap position when it does not intersect the player's hitbox.
     *
     * @param set set receiving valid trap positions
     * @param pos candidate block position
     * @param box player's bounding box
     */
    private void add(Set<BlockPos> set, BlockPos pos, Box box) {
        pos = pos.toImmutable();
        if (!new Box(pos).intersects(box)) set.add(pos);
    }

    //endregion

    //region Queue management

    /**
     * Removes positions that are no longer required or replaceable.
     */
    private void clean() {
        this.queue.removeIf(pos ->
            !this.wanted.contains(pos) || !this.open(pos)
        );

        this.pending.entrySet().removeIf(entry ->
            !this.wanted.contains(entry.getKey())
            || !this.open(entry.getKey())
        );

        this.waiting.entrySet().removeIf(entry ->
            !this.wanted.contains(entry.getKey())
            || !this.open(entry.getKey())
        );
    }

    /**
     * Verifies pending placements and schedules failed attempts for retry.
     */
    private void verify() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator =
            this.pending.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (this.tick < entry.getValue()) continue;

            BlockPos pos = entry.getKey();
            iterator.remove();

            if (this.wanted.contains(pos) && this.open(pos)) {
                this.retry(pos);
            }
        }
    }

    /**
     * Moves expired retry entries back into the active queue.
     */
    private void promote() {
        Iterator<Map.Entry<BlockPos, Integer>> iterator =
            this.waiting.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (this.tick < entry.getValue()) continue;

            BlockPos pos = entry.getKey();
            iterator.remove();

            if (this.wanted.contains(pos) && this.open(pos)) {
                this.queue.add(pos);
            }
        }
    }

    /**
     * Adds untracked required positions to the active queue.
     */
    private void fill() {
        for (BlockPos pos : this.wanted) {
            if (this.open(pos) && !this.tracked(pos)) {
                this.queue.add(pos);
            }
        }
    }

    //endregion

    //region Placement scheduling

    /**
     * Counts how many queued blocks can be handled during the next cycle.
     *
     * @return the number of blocks available for the next cycle
     */
    private int amount() {
        int limit = this.batch.get() ? this.count.get() : 1;
        int amount = 0;

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

        int maximum = this.batch.get() ? this.count.get() : 1;

        return Math.max(1, (int) Math.ceil(
            this.delay.get() * amount / (double) maximum)
        );
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
     * Checks whether a position can currently be processed.
     *
     * @param pos position to check
     * @return true when the position is required, open and not already tracked
     */
    private boolean ready(BlockPos pos) {
        return this.wanted.contains(pos)
            && this.open(pos)
            && !this.pending.containsKey(pos)
            && !this.waiting.containsKey(pos);
    }

    /**
     * Delays another attempt for a failed block position.
     *
     * @param pos failed block position
     */
    private void retry(BlockPos pos) {
        if (!this.wanted.contains(pos) || !this.open(pos)) return;
        this.waiting.put(pos.toImmutable(), this.tick + this.cooldown.get());
    }

    //endregion

    //region Block placement

    /**
     * Selects the requested hotbar slot and sends a block interaction.
     *
     * @param pos destination block position
     * @param slot hotbar slot containing the block
     * @return true when the placement interaction was sent
     */
    private boolean place(BlockPos pos, int slot) {
        ItemStack stack = this.mc.player.getInventory().getStack(slot);

        if (!(stack.getItem() instanceof BlockItem item)) return false;
        if (!InvUtils.swap(slot, true)) return false;

        try {
            this.mc.interactionManager.interactBlock(
                this.mc.player, Hand.MAIN_HAND, this.hit(pos)
            );

            this.mc.player.swingHand(Hand.MAIN_HAND);
            this.sound(item, pos);

            return true;
        } finally {
            InvUtils.swapBack();
        }
    }

    /**
     * Finds a block face that can support normal placement.
     *
     * @param pos destination block position
     * @return block hit result used for interaction
     */
    private BlockHitResult hit(BlockPos pos) {
        for (Direction side : Direction.values()) {

            BlockPos near = pos.offset(side);
            BlockState state = this.mc.world.getBlockState(near);

            if (state.isReplaceable()) continue;
            if (!state.getFluidState().isEmpty()) continue;

            Direction face = side.getOpposite();
            Vec3d hit = Vec3d.ofCenter(near).add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5
            );

            return new BlockHitResult(hit, face, near, false);
        }

        return new BlockHitResult(
            Vec3d.ofCenter(pos), Direction.UP, pos, false
        );
    }

    /**
     * Finds a hotbar slot containing an allowed full cube block.
     *
     * @param pos destination used to evaluate the block collision shape
     * @return the matching hotbar slot, or -1 when none is available
     */
    private int slot(BlockPos pos) {
        for (int idx = 0; idx < 9; idx++) {
            ItemStack stack = this.mc.player.getInventory().getStack(idx);
            if (!(stack.getItem() instanceof BlockItem item)) continue;

            Block block = item.getBlock();
            if (!this.allowed(block) || block instanceof FallingBlock) continue;

            if (Block.isShapeFullCube(
                block.getDefaultState().getCollisionShape(this.mc.world, pos)
            )) return idx;
        }
        return -1;
    }

    //endregion

    //region Validation and utilities

    /**
     * Checks whether a block passes the configured whitelist or blacklist.
     *
     * @param block block to check
     * @return true when the block is allowed
     */
    private boolean allowed(Block block) {
        return this.mode.get() == Mode.Blacklist
            ? !this.blocks.get().contains(block)
            : this.blocks.get().contains(block);
    }

    /**
     * Checks whether a block position can be replaced.
     *
     * @param pos position to check
     * @return true when a block can be placed there
     */
    private boolean open(BlockPos pos) {
        return this.mc.world.getBlockState(pos).isReplaceable();
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

    /**
     * Calculates squared distance from the player's bounding box center.
     *
     * @param pos block position
     * @return the squared distance to the position
     */
    private double distance(BlockPos pos) {
        Vec3d center = this.mc.player.getBoundingBox().getCenter();

        double px = pos.getX() + 0.5 - center.x;
        double py = pos.getY() + 0.5 - center.y;
        double pz = pos.getZ() + 0.5 - center.z;

        return px * px + py * py + pz * pz;
    }

    /**
     * Returns the primary Baritone instance.
     *
     * @return primary Baritone instance
     */
    private IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    //endregion

    //region Visual and sound effects

    /**
     * Renders a configured box around a block position.
     *
     * @param event active 3D render event
     * @param pos block position to render
     */
    private void box(Render3DEvent event, BlockPos pos) {
        event.renderer.box(pos, this.side.get(),
            this.line.get(), this.shape.get(), 0
        );
    }

    /**
     * Plays the local placement sound for a selected block.
     *
     * @param item placed block item
     * @param pos placement position
     */
    private void sound(BlockItem item, BlockPos pos) {
        BlockSoundGroup sound = item.getBlock().getDefaultState().getSoundGroup();

        this.mc.world.playSound(pos.getX() + 0.5, pos.getY() + 0.5,
            pos.getZ() + 0.5, sound.getPlaceSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false
        );
    }

    //endregion
}
