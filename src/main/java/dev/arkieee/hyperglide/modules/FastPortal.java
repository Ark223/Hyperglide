package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.utilities.Client;
import dev.arkieee.hyperglide.utilities.Render;
import dev.arkieee.hyperglide.utilities.Hotbar;
import dev.arkieee.hyperglide.utilities.Placement;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

public class FastPortal extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Integer> delay = this.general.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between placements.")
        .defaultValue(2)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Render box = new Render(
        this.visuals,
        "Renders the remaining portal frame.",
        "How the portal frame is rendered.",
        "The portal frame fill color.",
        "The portal frame outline color.",
        new SettingColor(64, 0, 128, 32),
        new SettingColor(64, 0, 128, 255)
    );

    private final List<BlockPos> portal = new ArrayList<>();

    private int index;
    private int timer;

    public FastPortal() {
        super(Hyperglide.CATEGORY, "fast-portal",
            "Builds a portal in the direction you are looking."
        );
    }

    /**
     * Calculates the portal frame and validates the required space and obsidian.
     */
    @Override
    public void onActivate() {
        if (!Client.loaded()) {
            this.toggle();
            return;
        }

        this.portal.clear();
        this.index = 0;
        this.timer = this.delay.get();

        this.collect();

        int need = 0;

        for (BlockPos pos : this.portal) {
            if (this.obsidian(pos)) continue;

            if (!this.mc.world.getBlockState(pos).isReplaceable()) {
                this.error("Portal area is obstructed.");
                this.toggle();
                return;
            }

            need++;
        }

        if (need == 0) {
            this.info("A complete portal already exists here.");
            this.toggle();
            return;
        }

        if (Hotbar.count(Items.OBSIDIAN) < need) {
            this.error("Not enough obsidian in hotbar (need " + need + ").");
            this.toggle();
        }
    }

    /**
     * Clears the portal frame and placement state.
     */
    @Override
    public void onDeactivate() {
        this.portal.clear();
        this.index = 0;
        this.timer = 0;
    }

    //region Event handlers

    /**
     * Places the next required frame block after the delay.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Client.loaded()) return;

        this.skip();

        if (this.index >= this.portal.size()) {
            this.done();
            return;
        }

        if (++this.timer < this.delay.get()) return;

        BlockPos pos = this.portal.get(this.index);
        if (!this.mc.world.getBlockState(pos).isReplaceable()) {
            this.error("Portal area became obstructed.");
            this.toggle();
            return;
        }

        int slot = Hotbar.find(Items.OBSIDIAN);
        if (slot == -1) {
            this.error("No obsidian found in the hotbar.");
            this.toggle();
            return;
        }

        if (!this.place(pos, slot)) return;

        this.index++;
        this.timer = 0;
        this.skip();

        if (this.index >= this.portal.size()) {
            this.done();
        }
    }

    /**
     * Renders the remaining incomplete portal frame positions.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.box.enabled()) return;

        for (int idx = this.index; idx < this.portal.size(); idx++) {
            BlockPos pos = this.portal.get(idx);
            if (this.obsidian(pos)) continue;

            this.box.box(event, pos);
        }
    }

    //endregion

    //region Portal structure

    /**
     * Calculates the portal frame positions in front of the player.
     */
    private void collect() {
        Direction front = this.mc.player.getHorizontalFacing();
        Direction right = front.rotateYClockwise();

        BlockPos base = BlockPos.ofFloored(
            this.mc.player.getX() + front.getOffsetX() * 2,
            this.mc.player.getY(),
            this.mc.player.getZ() + front.getOffsetZ() * 2
        ).offset(right, -1);

        this.portal.add(base.offset(right, 1));
        this.portal.add(base.offset(right, 2));

        for (int idx = 1; idx <= 3; idx++) {
            this.portal.add(base.up(idx));
        }

        for (int idx = 1; idx <= 3; idx++) {
            this.portal.add(base.offset(right, 3).up(idx));
        }

        this.portal.add(base.offset(right, 1).up(4));
        this.portal.add(base.offset(right, 2).up(4));
    }

    /**
     * Checks whether a position contains obsidian.
     *
     * @param pos block position to check
     * @return true when the position contains obsidian
     */
    private boolean obsidian(BlockPos pos) {
        return this.mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN);
    }

    /**
     * Advances past frame positions that already contain obsidian.
     */
    private void skip() {
        while (this.index < this.portal.size() &&
            this.obsidian(this.portal.get(this.index))) {
            this.index++;
        }
    }

    //endregion

    //region Portal interaction

    /**
     * Places obsidian at a frame position.
     *
     * @param pos destination block position
     * @param slot hotbar slot containing obsidian
     * @return true when the placement packets were sent
     */
    private boolean place(BlockPos pos, int slot) {
        if (!Hotbar.swap(slot)) return false;

        try {
            Placement.place(pos);
            this.mc.player.swingHand(Hand.MAIN_HAND);

            Placement.sound(Blocks.OBSIDIAN, pos);
            return true;
        } finally {
            Hotbar.restore();
        }
    }

    /**
     * Attempts to ignite the completed portal using flint and steel.
     *
     * @return true when the ignition interaction was sent
     */
    private boolean ignite() {
        int slot = Hotbar.find(Items.FLINT_AND_STEEL);
        if (slot == -1 || this.mc.interactionManager == null) {
            return false;
        }

        BlockPos pos = this.portal.get(0);
        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(pos).add(0, 0.5, 0),
            Direction.UP, pos, false
        );

        if (!Hotbar.swap(slot)) return false;

        try {
            this.mc.interactionManager.interactBlock(
                this.mc.player, Hand.MAIN_HAND, hit
            );

            this.mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        } finally {
            Hotbar.restore();
        }
    }

    /**
     * Attempts to ignite the completed portal and disables the module.
     */
    private void done() {
        if (this.ignite()) this.info("Portal complete.");
        else this.error("Portal complete but unable to ignite.");

        this.toggle();
    }

    //endregion
}
