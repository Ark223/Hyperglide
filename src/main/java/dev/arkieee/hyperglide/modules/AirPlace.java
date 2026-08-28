package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.utilities.Render;
import dev.arkieee.hyperglide.utilities.Client;
import dev.arkieee.hyperglide.utilities.Hotbar;
import dev.arkieee.hyperglide.utilities.Placement;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AirPlace extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Double> range = this.general.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far Air Place can target.")
        .defaultValue(3.0)
        .min(0.0)
        .sliderMax(6.0)
        .build()
    );

    private final Render box = new Render(
        this.visuals,
        "Renders the air-place target.",
        "How the target box is rendered.",
        "The fill color of the target box.",
        "The outline color of the target box.",
        new SettingColor(255, 255, 255, 32),
        new SettingColor(255, 255, 255, 255)
    );

    private BlockHitResult hit;
    private boolean lock;
    private boolean own;

    public AirPlace() {
        super(Hyperglide.CATEGORY, "air-place",
            "Places one block in the air per right-click."
        );
    }

    /**
     * Resets the target and click state.
     */
    @Override
    public void onActivate() {
        this.hit = null;
        this.lock = this.mc.options.useKey.isPressed();
        this.own = false;
    }

    /**
     * Clears the target and click state.
     */
    @Override
    public void onDeactivate() {
        this.hit = null;
        this.lock = false;
        this.own = false;
    }

    //region Event handlers

    /**
     * Updates the air-place target and handles a new right click.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Client.ready() || this.mc.getCameraEntity() == null) {
            this.hit = null;
            this.lock = false;
            return;
        }

        boolean pressed = this.mc.options.useKey.isPressed();
        if (!pressed) this.lock = false;

        ItemStack stack = this.mc.player.getMainHandStack();
        if (!this.valid(stack)) {
            this.hit = null;
            return;
        }

        if (this.mc.crosshairTarget != null &&
            this.mc.crosshairTarget.getType() != HitResult.Type.MISS) {
            this.hit = null;
            return;
        }

        HitResult ray = this.mc.getCameraEntity().raycast(
            this.range.get(), 0.0F, false
        );

        if (ray instanceof BlockHitResult block &&
            this.mc.world.getBlockState(block.getBlockPos()).isReplaceable()) {
            this.hit = block;
        } else {
            this.hit = null;
        }

        if (!pressed || this.lock || this.hit == null) {
            return;
        }

        this.lock = true;
        this.place(this.hit, stack);
    }

    /**
     * Cancels the normal interaction after air placement.
     *
     * @param event outgoing packet event
     */
    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (this.lock && !this.own &&
            event.packet instanceof PlayerInteractBlockC2SPacket) {
            event.cancel();
        }
    }

    /**
     * Renders the current air-place target.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.box.enabled() || this.hit == null || !Client.ready() ||
            !this.valid(this.mc.player.getMainHandStack()) ||
            !this.mc.world.getBlockState(this.hit.getBlockPos()).isReplaceable()) {
            return;
        }

        this.box.box(event, this.hit.getBlockPos());
    }

    //endregion

    //region Block placement

    /**
     * Places a block from hotbar at a specific position.
     *
     * @param pos target block position
     * @param slot hotbar slot containing the block
     * @return true when the placement packet was sent
     */
    public boolean place(BlockPos pos, int slot) {
        if (!Client.ready() || slot < 0 || slot > 8 ||
            !this.mc.world.getBlockState(pos).isReplaceable()) {
            return false;
        }

        ItemStack stack = Hotbar.stack(slot);
        if (!(stack.getItem() instanceof BlockItem)) {
            return false;
        }

        int selected = Hotbar.selected();
        if (selected != slot) Hotbar.select(slot);

        try {
            BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(pos), Direction.UP, pos, false
            );

            this.place(hit, stack);
        } finally {
            if (selected != slot) Hotbar.select(selected);
        }

        return true;
    }

    /**
     * Places the selected item at the target.
     *
     * @param hit target block hit result
     * @param stack selected item stack
     */
    private void place(BlockHitResult hit, ItemStack stack) {
        Placement.place(hit, value -> this.own = value);
        this.mc.player.swingHand(Hand.MAIN_HAND);

        if (stack.getItem() instanceof BlockItem block) {
            Placement.sound(block, hit.getBlockPos());
        }
    }

    /**
     * Checks whether an item can be placed using Air Place.
     *
     * @param stack item stack to check
     * @return true when the stack contains a block or spawn egg
     */
    private boolean valid(ItemStack stack) {
        return stack.getItem() instanceof BlockItem
            || stack.getItem() instanceof SpawnEggItem;
    }

    //endregion
}
