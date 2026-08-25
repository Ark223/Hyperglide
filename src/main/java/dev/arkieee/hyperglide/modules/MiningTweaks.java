package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.mixin.InteractionAccessor;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class MiningTweaks extends Module {
    private static final double threshold = 0.7;
    private static final double reach = 6.0;

    private static final long restart = 300;
    private static final long pause = 275;
    private static final int bursts = 22;
    private static final int height = 2048;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Boolean> remine = this.general.add(new BoolSetting.Builder()
        .name("instant-remine")
        .description("Instantly mines the last broken block when replaced.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> retries = this.general.add(new IntSetting.Builder()
        .name("maximum-retries")
        .description("Maximum mining retries after a block fails to break.")
        .defaultValue(1)
        .min(0)
        .sliderMax(2)
        .build()
    );

    private final Setting<Integer> cooldown = this.general.add(new IntSetting.Builder()
        .name("retry-cooldown")
        .description("Delay in ticks before starting another mining attempt.")
        .defaultValue(6)
        .min(1)
        .sliderMax(12)
        .build()
    );

    private final Setting<Integer> arming = this.general.add(new IntSetting.Builder()
        .name("tool-sync-delay")
        .description("Delay in ticks after switching tools before mining starts.")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> validation = this.general.add(new IntSetting.Builder()
        .name("validation-wait")
        .description("Checks whether the block was mined after this many ticks.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> vanilla = this.general.add(new IntSetting.Builder()
        .name("vanilla-cutoff")
        .description("Uses vanilla mining for breaks within this limit in ticks.")
        .defaultValue(1)
        .min(0)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders packet mining progress and queued blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How mining progress and queued blocks are rendered.")
        .defaultValue(ShapeMode.Lines)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> qside = this.visuals.add(new ColorSetting.Builder()
        .name("queue-side-color")
        .description("The queued block fill color.")
        .defaultValue(new SettingColor(255, 255, 255, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> qline = this.visuals.add(new ColorSetting.Builder()
        .name("queue-line-color")
        .description("The queued block outline color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> pside = this.visuals.add(new ColorSetting.Builder()
        .name("primary-side-color")
        .description("The primary block fill color.")
        .defaultValue(new SettingColor(255, 160, 0, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> pline = this.visuals.add(new ColorSetting.Builder()
        .name("primary-line-color")
        .description("The primary block outline color.")
        .defaultValue(new SettingColor(255, 160, 0, 255))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> sside = this.visuals.add(new ColorSetting.Builder()
        .name("secondary-side-color")
        .description("The secondary block fill color.")
        .defaultValue(new SettingColor(255, 0, 0, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> sline = this.visuals.add(new ColorSetting.Builder()
        .name("secondary-line-color")
        .description("The secondary block outline color.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(this.render::get)
        .build()
    );

    private final Deque<Request> queue = new ArrayDeque<>();
    private final Deque<Retry> waiting = new ArrayDeque<>();

    private Target primary;
    private Target secondary;
    private Request last;

    private int tick;
    private long ready;
    private long stopped;
    private boolean fast;
    private boolean paired;

    public MiningTweaks() {
        super(Hyperglide.CATEGORY, "mining-tweaks",
            "Enables queued packet mining with double break support."
        );
    }

    /**
     * Clears runtime state before the module starts.
     */
    @Override
    public void onActivate() {
        this.reset();
    }

    /**
     * Aborts active mining targets and clears runtime state.
     */
    @Override
    public void onDeactivate() {
        if (this.primary != null && !this.primary.finished) {
            this.action(this.primary,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                this.primary.pos
            );
        }

        if (this.secondary != null && !this.secondary.finished) {
            this.action(this.secondary,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                this.secondary.pos
            );
        }

        this.reset();
    }

    //region Event handlers

    /**
     * Updates queues, retries, remine state and active mining targets.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!this.valid() || this.mc.interactionManager == null) {
            return;
        }

        this.tick++;

        this.promote();
        this.clean();

        if (this.remine()) return;

        this.fill();

        this.update(this.secondary);
        this.update(this.primary);
    }

    /**
     * Renders queued blocks and active mining progress.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get()) return;

        for (Request request : this.queue) {
            event.renderer.box(
                request.pos, this.qside.get(),
                this.qline.get(), this.shape.get(), 0
            );
        }

        for (Retry retry : this.waiting) {
            event.renderer.box(
                retry.request.pos, this.qside.get(),
                this.qline.get(), this.shape.get(), 0
            );
        }

        if (this.secondary != null) {
            this.box(event, this.secondary,
                this.sside.get(), this.sline.get()
            );
        }

        if (this.primary != null) {
            this.box(event, this.primary,
                this.pside.get(), this.pline.get()
            );
        }
    }

    //endregion

    //region State management

    /**
     * Clears queues, targets, timers and mining state.
     */
    private void reset() {
        this.queue.clear();
        this.waiting.clear();

        this.primary = null;
        this.secondary = null;
        this.last = null;

        this.tick = 0;
        this.ready = 0;
        this.stopped = 0;

        this.fast = false;
        this.paired = false;
    }

    //endregion

    //region Mining requests

    /**
     * Checks whether instant remine is enabled.
     *
     * @return true when instant remine is enabled
     */
    public boolean instant() {
        return this.remine.get();
    }

    /**
     * Changes the instant remine setting.
     *
     * @param enabled whether instant remine should be enabled
     */
    public void instant(boolean enabled) {
        this.remine.set(enabled);
    }

    /**
     * Checks whether a position is armed for instant remine.
     *
     * @param pos block position to check
     * @return true when the position is stored for instant remine
     */
    public boolean armed(BlockPos pos) {
        return this.remine.get() && this.last != null
            && this.last.pos.equals(pos);
    }

    /**
     * Instantly rebreaks a known block without waiting for a world update.
     *
     * @param pos block position to rebreak
     * @param state expected block state
     * @param side block face used by the packet
     * @return true when the rebreak packet was sent
     */
    public boolean rebreak(BlockPos pos, BlockState state, Direction side) {
        if (!this.remine.get() || !this.valid() ||
            this.mc.interactionManager == null ||
            pos == null || state == null || side == null ||
            !this.breakable(pos, state)) return false;

        int slot = this.best(state, pos);

        this.select(slot);
        this.packet(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            pos, side
        );

        this.stopped = System.currentTimeMillis();
        return true;
    }

    /**
     * Checks whether a block should use vanilla mining.
     *
     * @param pos block position to check
     * @return true when vanilla mining should handle the block
     */
    public boolean bypass(BlockPos pos) {
        if (!this.valid() || pos == null ||
            this.vanilla.get() <= 0 || this.tracked(pos)) {
            return false;
        }

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return false;

        float delta = state.calcBlockBreakingDelta(
            this.mc.player, this.mc.world, pos
        );

        return delta >= 1.0F / this.vanilla.get();
    }

    /**
     * Queues a block for packet mining.
     *
     * @param pos block position to mine
     * @param side preferred block face
     * @return true when the block is tracked or queued
     */
    public boolean mine(BlockPos pos, Direction side) {
        if (!this.valid() || pos == null || side == null ||
            this.mc.interactionManager == null) {
            return false;
        }

        if (this.tracked(pos)) return true;

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return false;

        this.queue.addLast(new Request(pos, side, 0));

        this.fill();
        return true;
    }

    //endregion

    //region Queue management

    /**
     * Moves ready retry requests back into the active queue.
     */
    private void promote() {
        if (this.waiting.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Retry> iterator = this.waiting.iterator();

        while (iterator.hasNext()) {
            Retry retry = iterator.next();
            if (now < retry.ready) continue;

            BlockState state = this.mc.world.getBlockState(retry.request.pos);
            iterator.remove();

            if (!this.breakable(retry.request.pos, state)) continue;
            this.queue.addFirst(retry.request);
        }
    }

    /**
     * Removes queued and waiting requests that can no longer be mined.
     */
    private void clean() {
        this.queue.removeIf(request -> {
            BlockState state = this.mc.world.getBlockState(request.pos);
            return !this.breakable(request.pos, state);
        });

        this.waiting.removeIf(retry -> {
            BlockState state = this.mc.world.getBlockState(retry.request.pos);
            return !this.breakable(retry.request.pos, state);
        });
    }

    /**
     * Starts available primary and secondary mining targets.
     */
    private void fill() {
        if (this.paired) {
            if (this.primary != null || this.secondary != null) {
                return;
            }

            this.paired = false;
        }

        if (this.queue.isEmpty()) return;

        if (this.primary == null) {
            if (!this.startable()) return;

            Target target = this.next();
            if (target != null) this.begin(target);
        }

        if (this.primary == null || this.secondary != null ||
            this.queue.isEmpty() || !this.parkable()) {
            return;
        }

        Target target = this.next();
        if (target == null) return;

        this.park();
        this.begin(target);
    }

    /**
     * Removes the next breakable request and creates its target.
     *
     * @return next mining target, or null when none is available
     */
    private Target next() {
        while (!this.queue.isEmpty()) {
            Request request = this.queue.removeFirst();

            BlockState state = this.mc.world.getBlockState(request.pos);
            if (!this.breakable(request.pos, state)) continue;

            Direction side = this.face(request.pos, request.side);
            return new Target(request, state, side);
        }
        return null;
    }

    /**
     * Checks whether another primary target may begin.
     *
     * @return true when the restart delay has elapsed or fast mode is active
     */
    private boolean startable() {
        return this.fast || System.currentTimeMillis() - this.stopped > restart;
    }

    /**
     * Checks whether the primary target can be parked as the secondary target.
     *
     * @return true when double-break parking is currently allowed
     */
    private boolean parkable() {
        return this.secondary == null
            && System.currentTimeMillis() >= this.ready
            && this.primary != null && !this.primary.arming
            && !this.primary.finished && !this.primary.instant
            && this.progress(this.primary) < 1.0;
    }

    /**
     * Stops and converts the primary target into a parked secondary target.
     */
    private void park() {
        Target target = this.primary;

        this.action(target,
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos
        );

        Target parked = new Target(
            new Request(target.pos, target.side, target.retry),
            target.state, target.side
        );

        long now = System.currentTimeMillis();

        parked.started = now;
        parked.updated = now;

        parked.slot = target.slot;
        parked.delta = this.delta(parked);
        parked.work = Math.max(0.0, parked.delta);

        parked.instant = parked.delta >= 1.0F;
        parked.burst = target.burst;

        this.secondary = parked;
        this.primary = null;
        this.paired = true;
    }

    //endregion

    //region Mining control

    /**
     * Instantly rebreaks the last confirmed block when it gets replaced.
     *
     * @return true when an instant rebreak packet was sent
     */
    private boolean remine() {
        if (!this.remine.get() || this.last == null ||
            this.primary != null || this.secondary != null) {
            return false;
        }

        BlockState state = this.mc.world.getBlockState(this.last.pos);
        if (!this.breakable(this.last.pos, state)) return false;

        Direction side = this.face(this.last.pos, this.last.side);
        return this.rebreak(this.last.pos, state, side);
    }

    /**
     * Prepares a target, selects its best tool and starts arming when required.
     *
     * @param target target to begin
     */
    private void begin(Target target) {
        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.primary = target;

        int selected = this.mc.player.getInventory().selectedSlot;

        this.select(target.slot);

        if (selected != target.slot) {
            target.arming = true;
            target.arm = this.tick + this.arming.get();
            return;
        }

        this.start(target);
    }

    /**
     * Initializes mining progress by sending destroy packets.
     *
     * @param target target to start
     */
    private void start(Target target) {
        long now = System.currentTimeMillis();

        target.arming = false;
        target.started = now;
        target.updated = now;

        target.delta = this.delta(target);
        target.work = Math.max(0.0, target.delta);

        target.instant = target.delta >= 1.0F;

        this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            target.pos, target.side
        );

        if (!target.instant) {
            this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                this.fake(target.pos), target.side
            );
        }

        this.mc.player.swingHand(Hand.MAIN_HAND);
        if (target.instant) this.finish(target);
    }

    /**
     * Updates a target through arming, progress, finish and validation states.
     *
     * @param target target to update
     */
    private void update(Target target) {
        if (target == null) return;

        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        if (!this.reachable(target.pos) || !state.equals(target.state)) {
            this.fail(target);
            return;
        }

        if (target.arming) {
            int slot = this.best(target.state, target.pos);
            int selected = this.mc.player.getInventory().selectedSlot;

            if (slot != target.slot || selected != slot) {
                target.slot = slot;
                this.select(slot);

                target.arm = this.tick + this.arming.get();
                return;
            }

            if (this.tick < target.arm) {
                return;
            }

            this.start(target);
            return;
        }

        if (target.finished) {
            int delay = target == this.primary ?
                this.validation.get() : this.validation.get() * 2;

            if (this.tick - target.finish >= delay) this.verify(target);
            return;
        }

        int slot = this.best(target.state, target.pos);
        if (slot != target.slot) target.slot = slot;

        this.advance(target);

        double progress = this.progress(target);
        long elapsed = System.currentTimeMillis() - target.started;

        if (!target.burst && elapsed >= pause &&
            this.expected(target) >= pause && progress < 1.0) {
            this.burst(target);
        }

        if (progress >= 1.0) this.finish(target);
    }

    /**
     * Accumulates mining work since the target's previous update.
     *
     * @param target target receiving accumulated work
     */
    private void advance(Target target) {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, now - target.updated);

        target.delta = this.delta(target);

        if (elapsed > 0 && target.delta > 0.0F) {
            target.work += target.delta * elapsed / 50.0;
        }

        target.updated = now;
    }

    /**
     * Sends repeated mining packets to recover stalled progress.
     *
     * @param target stalled target
     */
    private void burst(Target target) {
        this.advance(target);

        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);

        BlockPos pos = this.fake(target.pos);

        for (int idx = 0; idx < bursts; idx++) {
            this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                pos, target.side
            );
        }

        target.burst = true;
    }

    //endregion

    //region Target lifecycle

    /**
     * Marks a target finished and sends its final stop action.
     *
     * @param target target to finish
     */
    private void finish(Target target) {
        if (target.finished) return;

        this.advance(target);

        target.finished = true;
        target.finish = this.tick;

        this.fast = target.burst;

        if (target == this.primary && !target.instant) {
            this.action(target,
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos
            );
        }

        this.stopped = System.currentTimeMillis();
    }

    /**
     * Checks whether a finished target was successfully broken.
     *
     * @param target target to validate
     */
    private void verify(Target target) {
        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        this.fail(target);
    }

    /**
     * Aborts a failed target and schedules another attempt when allowed.
     *
     * @param target failed target
     */
    private void fail(Target target) {
        boolean reachable = this.reachable(target.pos);

        Direction side = this.face(target.pos, target.side);
        target.side = side;

        this.action(target,
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, target.pos
        );

        this.remove(target, false);

        if (!reachable || target.retry >= this.retries.get()) {
            return;
        }

        long ready = System.currentTimeMillis();
        ready += this.cooldown.get() * 50L;

        this.waiting.addLast(new Retry(new Request(
            target.pos, side, target.retry + 1), ready)
        );
    }

    /**
     * Records a successful target for instant remine and removes it.
     *
     * @param target confirmed target
     */
    private void confirm(Target target) {
        this.last = new Request(target.pos,
            this.face(target.pos, target.side), 0
        );

        this.remove(target, true);
    }

    /**
     * Removes a target from its active slot and updates secondary readiness.
     *
     * @param target target to remove
     * @param confirmed whether the target was successfully broken
     */
    private void remove(Target target, boolean confirmed) {
        if (target == this.primary) {
            this.primary = null;
        }

        if (target == this.secondary) {
            this.secondary = null;
            this.ready = System.currentTimeMillis();
            this.ready += (confirmed ? 50L : pause);
        }
    }

    //endregion

    //region Progress calculation

    /**
     * Calculates normalized mining progress for a target.
     *
     * @param target target to evaluate
     * @return progress between zero and one
     */
    private double progress(Target target) {
        if (target.finished) return 1.0;

        double limit = this.limit(target);
        if (limit <= 0.0) return 1.0;

        return Math.min(1.0, target.work / limit);
    }

    /**
     * Estimates remaining mining time from the current breaking delta.
     *
     * @param target target to evaluate
     * @return expected remaining time in milliseconds
     */
    private long expected(Target target) {
        if (target.delta <= 0.0F) return Long.MAX_VALUE;

        double limit = this.limit(target);
        double ratio = limit / target.delta - 1.0;

        return (long) Math.max(0.0, ratio * 50.0);
    }

    /**
     * Returns the work limit required to finish a target.
     *
     * @param target target to evaluate
     * @return primary threshold or full secondary limit
     */
    private double limit(Target target) {
        return target == this.primary ? threshold : 1.0;
    }

    /**
     * Calculates block breaking delta using the target's selected tool.
     *
     * @param target target to evaluate
     * @return block breaking delta per tick
     */
    private float delta(Target target) {
        PlayerInventory inventory = this.mc.player.getInventory();
        int selected = inventory.selectedSlot;

        inventory.setSelectedSlot(target.slot);

        try {
            return target.state.calcBlockBreakingDelta(
                this.mc.player, this.mc.world, target.pos
            );
        } finally {
            inventory.setSelectedSlot(selected);
        }
    }

    //endregion

    //region Tool and packet handling

    /**
     * Finds the fastest suitable hotbar tool for a block.
     *
     * @param state block state to mine
     * @param pos block position to mine
     * @return best hotbar slot
     */
    private int best(BlockState state, BlockPos pos) {
        PlayerInventory inventory = this.mc.player.getInventory();

        int selected = inventory.selectedSlot;
        int best = selected;
        float speed = -1.0F;

        boolean suitable = false;
        boolean required = state.isToolRequired();

        try {
            for (int idx = 0; idx < 9; idx++) {
                ItemStack stack = inventory.getStack(idx);
                boolean good = stack.isSuitableFor(state);

                inventory.setSelectedSlot(idx);

                float value = state.calcBlockBreakingDelta(
                    this.mc.player, this.mc.world, pos
                );

                if (required && good != suitable) {
                    if (!good) continue;
                    best = idx;
                    speed = value;
                    suitable = true;
                    continue;
                }

                if (value <= speed) continue;

                best = idx;
                speed = value;
                suitable = good;
            }
        } finally {
            inventory.setSelectedSlot(selected);
        }

        return best;
    }

    /**
     * Refreshes target data and sends a mining action packet.
     *
     * @param target target associated with the action
     * @param action mining action to send
     * @param pos packet block position
     */
    private void action(Target target, PlayerActionC2SPacket.Action action, BlockPos pos) {
        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);
        this.packet(action, pos, target.side);
    }

    /**
     * Synchronizes a selected hotbar slot with the server.
     *
     * @param slot hotbar slot to select
     */
    private void select(int slot) {
        PlayerInventory inventory = this.mc.player.getInventory();
        if (inventory.selectedSlot == slot) return;

        inventory.setSelectedSlot(slot);

        this.mc.player.networkHandler.sendPacket(
            new UpdateSelectedSlotC2SPacket(slot)
        );
    }

    /**
     * Sends a sequenced player action packet.
     *
     * @param action mining action to send
     * @param pos packet block position
     * @param side block face used by the packet
     */
    private void packet(PlayerActionC2SPacket.Action action, BlockPos pos, Direction side) {
        if (!this.valid() || this.mc.interactionManager == null) {
            return;
        }

        ((InteractionAccessor) this.mc.interactionManager)
            .hyperglide$sendSequencedPacket(this.mc.world, sequence ->
                new PlayerActionC2SPacket(action, pos, side, sequence)
        );
    }

    //endregion

    //region Targeting utilities

    /**
     * Finds the nearest visible face of a block.
     *
     * @param pos block position
     * @param fallback fallback face when no visible face is found
     * @return selected block face
     */
    private Direction face(BlockPos pos, Direction fallback) {
        Vec3d eye = this.mc.player.getEyePos();

        Direction best = fallback == null ? Direction.UP : fallback;
        double distance = Double.POSITIVE_INFINITY;

        for (Direction side : Direction.values()) {
            Vec3d point = this.point(pos, side);

            BlockHitResult hit = this.mc.world.raycast(
                new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, this.mc.player
                )
            );

            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
                continue;
            }

            double value = eye.squaredDistanceTo(point);
            if (value >= distance) continue;

            distance = value;
            best = hit.getSide();
        }

        if (distance < Double.POSITIVE_INFINITY) {
            return best;
        }

        for (Direction side : Direction.values()) {
            Vec3d point = this.point(pos, side);

            double value = eye.squaredDistanceTo(point);
            if (value >= distance) continue;

            distance = value;
            best = side;
        }

        return best;
    }

    /**
     * Calculates a point near the center of a block face.
     *
     * @param pos block position
     * @param side block face
     * @return face point used for raycasting
     */
    private Vec3d point(BlockPos pos, Direction side) {
        return new Vec3d(
            pos.getX() + 0.5 + side.getOffsetX() * 0.49,
            pos.getY() + 0.5 + side.getOffsetY() * 0.49,
            pos.getZ() + 0.5 + side.getOffsetZ() * 0.49
        );
    }

    /**
     * Creates the fake position used by burst packets.
     *
     * @param pos source block position
     * @return fake position with the configured height
     */
    private BlockPos fake(BlockPos pos) {
        return new BlockPos(pos.getX(), height, pos.getZ());
    }

    //endregion

    //region Validation and utilities

    /**
     * Checks whether a block state can be mined.
     *
     * @param pos block position
     * @param state block state to check
     * @return true when the block is within reach and can be mined
     */
    private boolean breakable(BlockPos pos, BlockState state) {
        return this.reachable(pos) && !state.isAir()
            && state.getHardness(this.mc.world, pos) >= 0.0F;
    }

    /**
     * Checks whether a block is within mining reach.
     *
     * @param pos block position to check
     * @return true when the block is within reach
     */
    private boolean reachable(BlockPos pos) {
        Vec3d eye = this.mc.player.getEyePos();

        double px = Math.max(pos.getX(), Math.min(eye.x, pos.getX() + 1.0));
        double py = Math.max(pos.getY(), Math.min(eye.y, pos.getY() + 1.0));
        double pz = Math.max(pos.getZ(), Math.min(eye.z, pos.getZ() + 1.0));

        double dx = px - eye.x;
        double dy = py - eye.y;
        double dz = pz - eye.z;

        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * Checks whether a block is active, queued or waiting for retry.
     *
     * @param pos block position to check
     * @return true when the block is already tracked
     */
    private boolean tracked(BlockPos pos) {
        if (this.primary != null && this.primary.pos.equals(pos)) {
            return true;
        }

        if (this.secondary != null && this.secondary.pos.equals(pos)) {
            return true;
        }

        for (Request request : this.queue) {
            if (request.pos.equals(pos)) {
                return true;
            }
        }

        for (Retry retry : this.waiting) {
            if (retry.request.pos.equals(pos)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether the required client state is available.
     *
     * @return true when ready to run the module
     */
    private boolean valid() {
        return this.mc.player != null
            && this.mc.world != null
            && this.mc.getNetworkHandler() != null;
    }

    //endregion

    //region Visual effects

    /**
     * Renders mining progress as a shrinking box.
     *
     * @param event active 3D render event
     * @param target target being rendered
     * @param side fill color
     * @param line outline color
     */
    private void box(Render3DEvent event, Target target,
        SettingColor side, SettingColor line) {

        double offset = (1.0 - this.progress(target)) / 2.0;

        Box box = new Box(
            target.pos.getX() + offset,
            target.pos.getY() + offset,
            target.pos.getZ() + offset,
            target.pos.getX() + 1.0 - offset,
            target.pos.getY() + 1.0 - offset,
            target.pos.getZ() + 1.0 - offset
        );

        event.renderer.box(box, side, line, this.shape.get(), 0);
    }

    //endregion

    //region Data structures

    /**
     * Represents a queued mining request.
     *
     * @param pos block position
     * @param side preferred block face
     * @param retry retry count
     */
    private record Request(BlockPos pos, Direction side, int retry) {
        private Request {
            pos = pos.toImmutable();
        }
    }

    /**
     * Represents a mining request waiting for another attempt.
     *
     * @param request queued mining request
     * @param ready time when another attempt may begin
     */
    private record Retry(Request request, long ready) {}

    /**
     * Tracks the state of an active mining target.
     */
    private static class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final int retry;

        private Direction side;

        private long started;
        private long updated;

        private float delta;
        private double work;

        private int slot;
        private int arm;
        private int finish;

        private boolean arming;
        private boolean burst;
        private boolean instant;
        private boolean finished;

        /**
         * Creates a mining target from a queued request.
         *
         * @param request source mining request
         * @param state initial block state
         * @param side selected block face
         */
        private Target(Request request, BlockState state, Direction side) {
            this.pos = request.pos;
            this.state = state;
            this.side = side;
            this.retry = request.retry;
        }
    }

    //endregion
}
