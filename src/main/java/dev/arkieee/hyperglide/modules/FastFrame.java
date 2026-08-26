package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemFrameItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class FastFrame extends Module {
    private static final int life = 20;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Integer> first = this.general.add(new IntSetting.Builder()
        .name("first-slot")
        .description("First hotbar slot used for frame contents.")
        .defaultValue(8)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> last = this.general.add(new IntSetting.Builder()
        .name("last-slot")
        .description("Last hotbar slot used for frame contents.")
        .defaultValue(8)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Deque<Pending> queue = new ArrayDeque<>();

    private boolean main;
    private boolean off;

    public FastFrame() {
        super(Hyperglide.CATEGORY, "fast-frame",
            "Automatically fills placed item frames with items."
        );
    }

    /**
     * Clears pending placements before the module starts.
     */
    @Override
    public void onActivate() {
        this.reset();
    }

    /**
     * Clears pending placements when the module stops.
     */
    @Override
    public void onDeactivate() {
        this.reset();
    }

    //region Event handlers

    /**
     * Tracks whether either hand holds an item frame.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (!this.valid()) {
            this.reset();
            return;
        }

        this.main = this.held(Hand.MAIN_HAND);
        this.off = this.held(Hand.OFF_HAND);
    }

    /**
     * Tracks item frame placement attempts.
     *
     * @param event outgoing packet event
     */
    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (!this.valid() ||
            !(event.packet instanceof PlayerInteractBlockC2SPacket packet)) {
            return;
        }

        if (!this.held(packet.getHand()) && !this.cached(packet.getHand())) {
            return;
        }

        if (this.slot() < 0) return;

        BlockHitResult hit = packet.getBlockHitResult();
        BlockPos pos = hit.getBlockPos().offset(hit.getSide()).toImmutable();

        this.queue.addLast(new Pending(pos, this.frames(pos), life));
    }

    /**
     * Finds and fills newly placed item frames.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (!this.valid() || this.mc.interactionManager == null) {
            this.reset();
            return;
        }

        Iterator<Pending> iterator = this.queue.iterator();

        while (iterator.hasNext()) {
            Pending pending = iterator.next();

            if (--pending.life <= 0) {
                iterator.remove();
                continue;
            }

            if (pending.frame == null) {
                pending.frame = this.frame(pending);
                if (pending.frame == null) continue;
            }

            if (!pending.frame.isAlive() ||
                !pending.frame.getHeldItemStack().isEmpty()) {
                iterator.remove();
                continue;
            }

            if (!this.fill(pending.frame)) return;

            iterator.remove();
            return;
        }
    }

    //endregion

    //region State management

    /**
     * Clears pending placements and cached hand state.
     */
    private void reset() {
        this.queue.clear();

        this.main = false;
        this.off = false;
    }

    //endregion

    //region Frame handling

    /**
     * Finds the newly spawned empty item frame for a placement.
     *
     * @param pending pending frame placement
     * @return matching new item frame, or null when unavailable
     */
    private ItemFrameEntity frame(Pending pending) {
        Box box = new Box(pending.pos).expand(0.5);
        Vec3d center = Vec3d.ofCenter(pending.pos);

        ItemFrameEntity best = null;
        double distance = Double.MAX_VALUE;

        for (ItemFrameEntity frame : this.mc.world.getEntitiesByClass(
            ItemFrameEntity.class, box, entity -> entity.isAlive() &&
                entity.getHeldItemStack().isEmpty())) {

            if (pending.frames.contains(frame.getId())) continue;

            double current = frame.squaredDistanceTo(center);
            if (current >= distance) continue;

            distance = current;
            best = frame;
        }

        return best;
    }

    /**
     * Collects item frames already present near a placement position.
     *
     * @param pos expected frame position
     * @return existing frame entity IDs
     */
    private Set<Integer> frames(BlockPos pos) {
        Set<Integer> frames = new HashSet<>();
        Box box = new Box(pos).expand(0.5);

        for (ItemFrameEntity frame : this.mc.world.getEntitiesByClass(
            ItemFrameEntity.class, box, entity -> entity.isAlive())) {
            frames.add(frame.getId());
        }

        return frames;
    }

    /**
     * Places the first configured hotbar item into a frame.
     *
     * @param frame item frame to fill
     * @return true when the interaction was accepted
     */
    private boolean fill(ItemFrameEntity frame) {
        int slot = this.slot();
        if (slot < 0) return false;

        EntityHitResult hit = this.hit(frame);
        if (hit == null) return false;

        PlayerInventory inventory = this.mc.player.getInventory();
        int selected = inventory.selectedSlot;

        if (selected != slot) inventory.setSelectedSlot(slot);

        try {
            ActionResult result = this.mc.interactionManager.interactEntityAtLocation(
                this.mc.player, frame, hit, Hand.MAIN_HAND
            );

            if (!result.isAccepted()) {
                result = this.mc.interactionManager.interactEntity(
                    this.mc.player, frame, Hand.MAIN_HAND
                );
            }

            if (result.isAccepted()) {
                this.mc.player.swingHand(Hand.MAIN_HAND);
                return true;
            }

            return false;
        } finally {
            if (selected != slot) inventory.setSelectedSlot(selected);
        }
    }

    /**
     * Finds an interaction point on an item frame.
     *
     * @param frame item frame to target
     * @return frame hit result, or null when the frame is out of range
     */
    private EntityHitResult hit(ItemFrameEntity frame) {
        Vec3d eye = this.mc.player.getEyePos();
        Vec3d center = frame.getBoundingBox().getCenter();

        if (eye.squaredDistanceTo(center) >
            Math.pow(this.mc.player.getEntityInteractionRange(), 2.0)) {
            return null;
        }

        Box box = frame.getBoundingBox();
        Vec3d point = box.raycast(eye, center).orElse(center);

        return new EntityHitResult(frame, point);
    }

    /**
     * Finds the first usable item inside the configured hotbar range.
     *
     * @return matching hotbar slot, or -1 when no item is available
     */
    private int slot() {
        PlayerInventory inventory = this.mc.player.getInventory();

        int start = Math.min(this.first.get(), this.last.get()) - 1;
        int end = Math.max(this.first.get(), this.last.get()) - 1;

        for (int slot = start; slot <= end; slot++) {
            ItemStack stack = inventory.getStack(slot);

            if (!stack.isEmpty() && !(stack.getItem() instanceof ItemFrameItem)) {
                return slot;
            }
        }

        return -1;
    }

    /**
     * Checks whether a hand currently holds an item frame.
     *
     * @param hand hand to check
     * @return true when the hand contains an item frame
     */
    private boolean held(Hand hand) {
        ItemStack stack = this.mc.player.getStackInHand(hand);
        return stack.getItem() instanceof ItemFrameItem;
    }

    /**
     * Checks whether a hand held an item frame before the interaction.
     *
     * @param hand hand to check
     * @return true when the hand previously contained an item frame
     */
    private boolean cached(Hand hand) {
        return hand == Hand.MAIN_HAND ? this.main : this.off;
    }

    //endregion

    //region Validation and utilities

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

    //region Data structures

    /**
     * Stores a pending item frame placement.
     */
    private static class Pending {
        private final BlockPos pos;

        private final Set<Integer> frames;
        private ItemFrameEntity frame;

        private int life;

        private Pending(BlockPos pos, Set<Integer> frames, int life) {
            this.pos = pos;
            this.frames = frames;
            this.life = life;
        }
    }

    //endregion
}
