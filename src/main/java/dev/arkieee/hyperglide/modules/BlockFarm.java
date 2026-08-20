package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.List;

public class BlockFarm extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<List<Block>> blocks = this.general.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks placed and mined by Block Farm.")
        .defaultValue(Blocks.ENDER_CHEST)
        .build()
    );

    private final Direction[] sides = {
        Direction.NORTH, Direction.EAST,
        Direction.SOUTH, Direction.WEST
    };

    private MiningTweaks mining;
    private BlockPos pos;

    private boolean started;
    private boolean enabled;
    private boolean instant;

    public BlockFarm() {
        super(Hyperglide.CATEGORY, "block-farm",
            "Places selected blocks for fast repeated mining."
        );
    }

    /**
     * Prepares Mining Tweaks and clears the farm target.
     */
    @Override
    public void onActivate() {
        this.reset();

        this.mining = Modules.get().get(MiningTweaks.class);
        if (this.mining == null) return;

        this.enabled = this.mining.isActive();
        this.instant = this.mining.instant();

        if (!this.enabled) this.mining.toggle();
        this.mining.instant(true);
    }

    /**
     * Restores Mining Tweaks and clears the farm target.
     */
    @Override
    public void onDeactivate() {
        this.restore();
        this.reset();
    }

    //region Event handlers

    /**
     * Places the selected block and starts the first mining cycle.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!this.valid() || !this.prepare() ||
            !this.mc.player.isOnGround()) {
            return;
        }

        if (this.pos == null || !this.adjacent(this.pos)
            || this.occupied(this.pos)) {
            this.clear();
            this.pos = this.target();
        }

        if (this.pos == null) return;

        BlockState state = this.mc.world.getBlockState(this.pos);

        if (!state.isReplaceable()) {
            if (!this.allowed(state.getBlock())) {
                this.clear();
                return;
            }

            this.start(state);
            return;
        }

        if (!this.support(this.pos)) {
            this.clear();
            return;
        }

        if (this.started && !this.mining.armed(this.pos)) return;

        int slot = this.slot();
        if (slot < 0) return;

        BlockState placed = this.state(slot);
        if (!this.place(slot)) return;

        if (this.started) {
            this.mining.rebreak(this.pos, placed, Direction.UP);
        }
    }

    //endregion

    //region State management

    /**
     * Clears the active farm position.
     */
    private void clear() {
        this.pos = null;
        this.started = false;
    }

    /**
     * Clears the farm target and runtime state.
     */
    private void reset() {
        this.mining = null;
        this.clear();

        this.enabled = false;
        this.instant = false;
    }

    //endregion

    //region Farm targeting

    /**
     * Selects the open adjacent position closest to the player's view.
     *
     * @return selected farm position, or null when none is available
     */
    private BlockPos target() {
        BlockPos base = this.mc.player.getBlockPos();
        Vec3d look = Vec3d.fromPolar(0.0F, this.mc.player.getYaw());

        BlockPos best = null;
        double score = -Double.MAX_VALUE;

        for (Direction side : this.sides) {
            BlockPos pos = base.offset(side);

            if (!this.mc.world.getBlockState(pos).isReplaceable() ||
                !this.support(pos) || this.occupied(pos)) {
                continue;
            }

            double value = look.x * side.getOffsetX();
            value += look.z * side.getOffsetZ();
            if (value <= score) continue;

            score = value;
            best = pos.toImmutable();
        }

        return best;
    }

    /**
     * Checks whether the player intersects a farm position.
     *
     * @param pos farm position to check
     * @return true when the player occupies the block space
     */
    private boolean occupied(BlockPos pos) {
        return new Box(pos).intersects(this.mc.player.getBoundingBox());
    }

    /**
     * Checks whether a farm position is still beside the player.
     *
     * @param pos farm position to check
     * @return true when the position is one horizontal block away
     */
    private boolean adjacent(BlockPos pos) {
        BlockPos base = this.mc.player.getBlockPos();

        return pos.getY() == base.getY() &&
            base.getManhattanDistance(pos) == 1;
    }

    //endregion

    //region Block placement

    /**
     * Places the selected hotbar block into the farm position.
     *
     * @param slot hotbar slot containing the selected block
     * @return true when the placement interaction was sent
     */
    private boolean place(int slot) {
        if (!InvUtils.swap(slot, true)) return false;

        try {
            this.mc.interactionManager.interactBlock(
                this.mc.player, Hand.MAIN_HAND, this.hit()
            );

            this.mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        } finally {
            InvUtils.swapBack();
        }
    }

    /**
     * Creates a placement hit against the ground below the farm position.
     *
     * @return block hit result used for placement
     */
    private BlockHitResult hit() {
        BlockPos ground = this.pos.down();

        return new BlockHitResult(
            Vec3d.ofCenter(ground).add(0.0, 0.5, 0.0),
            Direction.UP, ground, false
        );
    }

    /**
     * Finds a hotbar slot containing a selected block.
     *
     * @return matching hotbar slot, or -1 when unavailable
     */
    private int slot() {
        int selected = this.mc.player.getInventory().selectedSlot;
        if (this.allowed(selected)) return selected;

        for (int idx = 0; idx < 9; idx++) {
            if (idx != selected && this.allowed(idx)) return idx;
        }

        return -1;
    }

    /**
     * Returns the block state represented by a hotbar slot.
     *
     * @param slot hotbar slot containing a block item
     * @return default state of the contained block
     */
    private BlockState state(int slot) {
        ItemStack stack = this.mc.player.getInventory().getStack(slot);
        BlockItem item = (BlockItem) stack.getItem();

        return item.getBlock().getDefaultState();
    }

    /**
     * Checks whether a hotbar slot contains a selected block.
     *
     * @param slot hotbar slot to inspect
     * @return true when the stack is an allowed block item
     */
    private boolean allowed(int slot) {
        ItemStack stack = this.mc.player.getInventory().getStack(slot);

        return stack.getItem() instanceof BlockItem item
            && this.allowed(item.getBlock());
    }

    //endregion

    //region Mining control

    /**
     * Starts mining the farm position once for instant remine setup.
     *
     * @param state current farm block state
     */
    private void start(BlockState state) {
        if (this.started || this.pos == null ||
            !this.allowed(state.getBlock())) {
            return;
        }

        if (this.mining.mine(this.pos, Direction.UP)) {
            this.started = true;
        }
    }

    /**
     * Keeps Mining Tweaks active with instant remine enabled.
     *
     * @return true when Mining Tweaks is ready
     */
    private boolean prepare() {
        if (this.mining == null) return false;

        if (!this.mining.isActive()) this.mining.toggle();
        if (!this.mining.instant()) this.mining.instant(true);

        return this.mining.isActive();
    }

    /**
     * Restores the Mining Tweaks state from before activation.
     */
    private void restore() {
        if (this.mining == null) return;

        this.mining.instant(this.instant);

        if (!this.enabled && this.mining.isActive()) {
            this.mining.toggle();
        }
    }

    //endregion

    //region Validation and utilities

    /**
     * Checks whether a block is selected for farming.
     *
     * @param block block to check
     * @return true when the block is selected
     */
    private boolean allowed(Block block) {
        return this.blocks.get().contains(block);
    }

    /**
     * Checks whether solid ground exists below a farm position.
     *
     * @param pos farm position to check
     * @return true when the block below can support placement
     */
    private boolean support(BlockPos pos) {
        BlockState state = this.mc.world.getBlockState(pos.down());
        return !state.isReplaceable() && state.getFluidState().isEmpty();
    }

    /**
     * Checks whether the required client state is available.
     *
     * @return true when ready to run the module
     */
    private boolean valid() {
        return this.mc.player != null
            && this.mc.world != null
            && this.mc.interactionManager != null;
    }

    //endregion
}
