package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.*;

public class AutoWeb extends Module {
    private static final double edge = 0.0001;
    private static final int life = 5;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Set<EntityType<?>>> entities = this.general.add(
        new EntityTypeListSetting.Builder()
            .name("entities")
            .description("Entities targeted by Auto Web.")
            .defaultValue(EntityType.PLAYER)
            .build()
    );

    private final Setting<Double> range = this.general.add(
        new DoubleSetting.Builder()
            .name("max-range")
            .description("Maximum placement range.")
            .defaultValue(4.5)
            .min(0.0)
            .sliderMax(6.0)
            .build()
    );

    private final Setting<Integer> delay = this.general.add(
        new IntSetting.Builder()
            .name("place-delay")
            .description("Delay in ticks between placements.")
            .defaultValue(2)
            .min(0)
            .sliderMax(4)
            .build()
    );

    private final Setting<Integer> extrapolation = this.general.add(
        new IntSetting.Builder()
            .name("extrapolation")
            .description("Ticks used to predict entity movement.")
            .defaultValue(2)
            .min(0)
            .sliderMax(5)
            .build()
    );

    private final LinkedHashSet<BlockPos> queue = new LinkedHashSet<>();
    private final Map<BlockPos, Integer> marks = new HashMap<>();

    private int timer;
    private int tick;

    public AutoWeb() {
        super(Hyperglide.CATEGORY, "auto-web",
            "Places cobwebs around selected entities."
        );
    }

    /**
     * Clears runtime state and prepares the placement timer.
     */
    @Override
    public void onActivate() {
        this.reset();
        this.timer = this.delay.get();
    }

    /**
     * Clears all queued and marked placement state.
     */
    @Override
    public void onDeactivate() {
        this.reset();
    }

    //region Event handlers

    /**
     * Collects target positions and places the next cobweb.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null ||
            this.mc.interactionManager == null) return;

        this.tick++;
        this.clean();
        this.collect();

        if (++this.timer < this.delay.get()) return;

        BlockPos pos = this.next();
        if (pos == null) return;

        int slot = this.slot();
        if (slot == -1 || !this.place(pos, slot)) return;

        this.timer = 0;
        this.marks.put(pos, this.tick);
    }

    /**
     * Clears queued positions, placement marks and timers.
     */
    private void reset() {
        this.queue.clear();
        this.marks.clear();

        this.timer = 0;
        this.tick = 0;
    }

    //endregion

    //region Target collection

    /**
     * Finds valid entities and queues their current and predicted positions.
     */
    private void collect() {
        this.queue.clear();

        List<Entity> targets = new ArrayList<>();
        double range = this.range.get() * this.range.get();

        for (Entity entity : this.mc.world.getEntities()) {
            if (entity == this.mc.player || !entity.isAlive() ||
                entity.isSpectator() ||
                !this.entities.get().contains(entity.getType()) ||
                this.mc.player.squaredDistanceTo(entity) > range) {
                continue;
            }
            targets.add(entity);
        }

        targets.sort(Comparator.comparingDouble(entity ->
            this.mc.player.squaredDistanceTo(entity))
        );

        for (Entity entity : targets) {
            Box box = entity.getBoundingBox();
            this.add(box);

            Vec3d move = this.move(entity);
            if (move.lengthSquared() > 0) {
                this.add(box.offset(move));
            }
        }
    }

    /**
     * Adds every block position intersecting an entity hitbox.
     *
     * @param box entity hitbox
     */
    private void add(Box box) {
        int minx = MathHelper.floor(box.minX + edge);
        int miny = MathHelper.floor(box.minY + edge);
        int minz = MathHelper.floor(box.minZ + edge);

        int maxx = MathHelper.floor(box.maxX - edge);
        int maxy = MathHelper.floor(box.maxY - edge);
        int maxz = MathHelper.floor(box.maxZ - edge);

        for (int px = minx; px <= maxx; px++) {
            for (int py = miny; py <= maxy; py++) {
                for (int pz = minz; pz <= maxz; pz++) {
                    this.add(new BlockPos(px, py, pz));
                }
            }
        }
    }

    /**
     * Adds a valid untracked block position to the queue.
     *
     * @param pos candidate block position
     */
    private void add(BlockPos pos) {
        pos = pos.toImmutable();

        if (!this.valid(pos) || this.queue.contains(pos)
            || this.marks.containsKey(pos)) return;

        this.queue.addLast(pos);
    }

    //endregion

    //region Queue management

    /**
     * Removes and returns the next valid queued position.
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

    /**
     * Removes expired or no longer replaceable placement marks.
     */
    private void clean() {
        this.marks.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            return !this.mc.world.getBlockState(pos).isReplaceable()
                || this.tick - entry.getValue() > life;
        });
    }

    //endregion

    //region Movement prediction

    /**
     * Predicts entity movement from its current velocity.
     *
     * @param entity entity to extrapolate
     * @return predicted movement offset
     */
    private Vec3d move(Entity entity) {
        if (this.extrapolation.get() == 0) return Vec3d.ZERO;

        Vec3d vel = entity.getVelocity();
        if (vel.lengthSquared() < edge) return Vec3d.ZERO;

        return vel.multiply(this.extrapolation.get());
    }

    //endregion

    //region Block placement

    /**
     * Places a cobweb using a custom interaction packet.
     *
     * @param pos destination block position
     * @param slot hotbar slot containing cobwebs
     * @return true when the placement packets were sent
     */
    private boolean place(BlockPos pos, int slot) {
        ItemStack stack = this.mc.player.getInventory().getStack(slot);

        if (!(stack.getItem() instanceof BlockItem item) ||
            !stack.isOf(Items.COBWEB) || !InvUtils.swap(slot, true)) {
            return false;
        }

        PlayerActionC2SPacket swap = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN, Direction.DOWN
        );

        try {
            this.mc.player.networkHandler.sendPacket(swap);

            this.mc.player.networkHandler.sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, this.hit(pos),
                    this.mc.player.currentScreenHandler.getRevision() + 2
                )
            );

            this.mc.player.networkHandler.sendPacket(swap);
            this.mc.player.swingHand(Hand.MAIN_HAND);
            this.sound(item, pos);

            return true;
        } finally {
            InvUtils.swapBack();
        }
    }

    /**
     * Finds a neighboring block face that can support normal placement.
     *
     * @param pos destination block position
     * @return block hit result used for interaction
     */
    private BlockHitResult hit(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos near = pos.offset(side);

            if (this.mc.world.getBlockState(near).isReplaceable() ||
                !this.mc.world.getFluidState(near).isEmpty()) {
                continue;
            }

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

    //endregion

    //region Validation and inventory

    /**
     * Checks whether a block position is replaceable and within range.
     *
     * @param pos block position to check
     * @return true when the position is valid
     */
    private boolean valid(BlockPos pos) {
        if (!this.mc.world.getBlockState(pos).isReplaceable()) {
            return false;
        }

        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d eye = this.mc.player.getEyePos();

        return center.squaredDistanceTo(eye) <=
            this.range.get() * this.range.get();
    }

    /**
     * Finds a cobweb stack in the hotbar.
     *
     * @return matching hotbar slot, or -1 when none exists
     */
    private int slot() {
        for (int idx = 0; idx < 9; idx++) {
            if (this.mc.player.getInventory().getStack(idx).isOf(Items.COBWEB)) {
                return idx;
            }
        }
        return -1;
    }

    //endregion

    //region Sound effects

    /**
     * Plays the local cobweb placement sound.
     *
     * @param item placed cobweb block item
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
