package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.utilities.Client;
import dev.arkieee.hyperglide.utilities.Inventory;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;

public class FD3Crafter extends Module {
    private static final int output = PlayerScreenHandler.CRAFTING_RESULT_ID;
    private static final int paper = PlayerScreenHandler.CRAFTING_INPUT_START;

    private static final int first = paper + 1;
    private static final int second = paper + 2;
    private static final int third = paper + 3;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Integer> delay = this.general.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks to wait between inventory actions.")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private Phase phase;
    private int source;
    private int target;
    private int tick;

    private int oldpaper;
    private int oldpowder;
    private int start;
    private int idle;

    private boolean bulk;
    private boolean close;
    private String note;

    /**
     * Defines the current crafting workflow stage.
     */
    private enum Phase {
        Clear,
        Work,
        Paper,
        Stack,
        Powder,
        Return,
        Merge,
        Check,
        Clean
    }

    public FD3Crafter() {
        super(Hyperglide.CATEGORY, "fd3-crafter",
            "Crafts Fd3 rockets using ingredients from the inventory."
        );
    }

    /**
     * Validates the player state and prepares the crafting workflow.
     */
    @Override
    public void onActivate() {
        if (!Client.ready() || !Client.interaction()) {
            this.toggle();
            return;
        }

        if (this.mc.player.isCreative()) {
            this.error("Crafter does not support creative mode.");
            this.toggle();
            return;
        }

        if (!Inventory.ready()) {
            this.mc.player.closeHandledScreen();
        }

        this.phase = Phase.Clear;
        this.source = -1;
        this.target = -1;
        this.tick = 0;

        this.oldpaper = 0;
        this.oldpowder = 0;
        this.start = this.rockets();
        this.idle = 0;

        this.bulk = false;
        this.close = false;
        this.note = null;

        this.mc.setScreen(new InventoryScreen(this.mc.player));
    }

    /**
     * Closes the inventory when required and clears runtime state.
     */
    @Override
    public void onDeactivate() {
        if (this.close && this.mc.currentScreen instanceof InventoryScreen) {
            this.mc.setScreen(null);
        }

        this.phase = null;

        this.source = -1;
        this.target = -1;
        this.tick = 0;
        this.idle = 0;

        this.bulk = false;
        this.close = false;
        this.note = null;
    }

    //region Event handlers

    /**
     * Advances the crafting workflow after the configured action delay.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Client.ready() || !Client.interaction()) {
            return;
        }

        if (!Inventory.ready() ||
            !(this.mc.currentScreen instanceof InventoryScreen)) {
            this.error("Inventory was closed.");
            this.toggle();
            return;
        }

        if (this.tick < this.delay.get()) {
            this.tick++;
            return;
        }

        this.tick = 0;
        this.step();
    }

    //endregion

    //region Crafting control

    /**
     * Executes the action assigned to the current crafting phase.
     */
    private void step() {
        switch (this.phase) {
            case Clear -> this.clear();
            case Work -> this.work();
            case Paper -> this.paper();
            case Stack -> this.stack();
            case Powder -> this.powder();
            case Return -> this.back();
            case Merge -> this.merge();
            case Check -> this.check();
            case Clean -> this.clean();
        }
    }

    /**
     * Clears the cursor and crafting grid before crafting begins.
     */
    private void clear() {
        if (!Inventory.cursor().isEmpty()) {
            this.error("Clear cursor before enabling crafter.");
            this.toggle();
            return;
        }

        for (int idx = paper; idx <= third; idx++) {
            if (!Inventory.stack(idx).isEmpty()) {
                Inventory.move(idx);
                return;
            }
        }

        this.phase = Phase.Work;
    }

    /**
     * Evaluates the crafting grid and schedules the next action.
     */
    private void work() {
        int one = Inventory.stack(first).getCount();
        int two = Inventory.stack(second).getCount();
        int three = Inventory.stack(third).getCount();

        if (!this.same(first, Items.GUNPOWDER) ||
            !this.same(second, Items.GUNPOWDER) ||
            !this.same(third, Items.GUNPOWDER)) {
            this.fail("A gunpowder slot contains another item.");
            return;
        }

        if (Inventory.stack(paper).isEmpty()) {
            this.source = this.largest(Items.PAPER, 1);

            if (this.source == -1) {
                this.phase = Phase.Clean;
                return;
            }

            Inventory.pick(this.source);
            this.phase = Phase.Paper;
            return;
        } else if (!Inventory.stack(paper).isOf(Items.PAPER)) {
            this.fail("The paper slot contains another item.");
            return;
        }

        if (one == two && two == three && one > 0) {
            ItemStack stack = Inventory.stack(output);

            if (stack.isEmpty()) {
                if (++this.idle > 5) {
                    this.fail("Fd3 rocket recipe did not appear.");
                }
                return;
            }

            this.idle = 0;

            if (!this.rocket(stack)) {
                this.fail("Crafting output was not Fd3 rocket.");
                return;
            }

            this.oldpaper = Inventory.stack(paper).getCount();
            this.oldpowder = one;

            Inventory.move(output);
            this.phase = Phase.Check;
            return;
        }

        if (this.bulk) {
            if ((one != 0 && one != 64) ||
                (two != 0 && two != 64) ||
                (three != 0 && three != 64)) {
                this.fail("A full gunpowder stack changed size.");
                return;
            }

            this.target = Inventory.empty(first, second, third);

            if (this.target == -1) {
                this.fail("Full gunpowder stacks became uneven.");
                return;
            }

            this.source = this.full(Items.GUNPOWDER);

            if (this.source == -1) {
                this.fail("A full gunpowder stack is missing.");
                return;
            }

            Inventory.pick(this.source);
            this.phase = Phase.Stack;
            return;
        }

        if (one != 0 || two != 0 || three != 0) {
            this.fail("Gunpowder slots became uneven.");
            return;
        }

        if (Inventory.stacks(stack -> stack.isOf(Items.GUNPOWDER) &&
            stack.getCount() == stack.getMaxCount()) >= 3) {
            this.bulk = true;
            this.target = first;
            this.source = this.full(Items.GUNPOWDER);

            Inventory.pick(this.source);
            this.phase = Phase.Stack;
            return;
        }

        this.source = this.largest(Items.GUNPOWDER, 3);

        if (this.source != -1) {
            Inventory.pick(this.source);
            this.phase = Phase.Powder;
            return;
        }

        if (Inventory.count(Items.GUNPOWDER) >= 3) {
            int[] pair = Inventory.pair(stack ->
                stack.isOf(Items.GUNPOWDER) && stack.getCount() < 3
            );

            if (pair != null) {
                this.source = pair[1];
                this.target = pair[0];

                Inventory.pick(this.source);
                this.phase = Phase.Merge;
                return;
            }
        }

        this.phase = Phase.Clean;
    }

    //endregion

    //region Ingredient placement

    /**
     * Moves the picked paper stack into the selected crafting slot.
     */
    private void paper() {
        if (!Inventory.cursor().isOf(Items.PAPER)) {
            this.fail("Paper pickup failed.");
            return;
        }

        Inventory.pick(paper);
        this.phase = Phase.Work;
    }

    /**
     * Moves a full gunpowder stack into the selected crafting slot.
     */
    private void stack() {
        if (!Inventory.cursor().isOf(Items.GUNPOWDER) ||
            Inventory.cursor().getCount() != 64) {
            this.fail("Full gunpowder pickup failed.");
            return;
        }

        Inventory.pick(this.target);
        this.phase = Phase.Work;
    }

    //endregion

    //region Gunpowder handling

    /**
     * Distributes the picked gunpowder stack across 3 crafting slots.
     */
    private void powder() {
        if (!Inventory.cursor().isOf(Items.GUNPOWDER)) {
            this.fail("Gunpowder pickup failed.");
            return;
        }

        Inventory.drag(first, second, third);

        this.phase = Phase.Return;
    }

    /**
     * Returns remaining cursor items to their source slot.
     */
    private void back() {
        if (Inventory.cursor().isEmpty()) {
            this.phase = Phase.Work;
            return;
        }

        Inventory.pick(this.source);
        this.phase = Phase.Work;
    }

    /**
     * Merges the picked gunpowder stack into the selected inventory stack.
     */
    private void merge() {
        if (!Inventory.cursor().isOf(Items.GUNPOWDER)) {
            this.fail("Gunpowder merge failed.");
            return;
        }

        Inventory.pick(this.target);
        this.phase = Phase.Work;
    }

    //endregion

    //region Crafting completion

    /**
     * Confirms that ingredients were consumed after collecting rockets.
     */
    private void check() {
        int paper = Inventory.stack(FD3Crafter.paper).getCount();
        int powder = Inventory.stack(first).getCount();

        if (paper < this.oldpaper || powder < this.oldpowder) {
            this.idle = 0;
            this.bulk = false;
            this.phase = Phase.Work;
            this.tick = this.delay.get();
            return;
        }

        if (++this.idle > 3) {
            this.fail("Not enough space for rockets.");
        }
    }

    /**
     * Returns cursor and crafting grid items before finishing.
     */
    private void clean() {
        if (!Inventory.cursor().isEmpty()) {
            int slot = Inventory.empty();

            if (slot == -1) {
                this.error("Not enough space for cursor stack.");
                this.toggle();
                return;
            }

            Inventory.pick(slot);
            return;
        }

        for (int idx = paper; idx <= third; idx++) {
            if (!Inventory.stack(idx).isEmpty()) {
                Inventory.move(idx);
                return;
            }
        }

        this.done();
    }

    /**
     * Records an error and moves the workflow into cleanup.
     *
     * @param note error message to display
     */
    private void fail(String note) {
        this.note = note;
        this.phase = Phase.Clean;
    }

    /**
     * Reports the crafting result and disables the module.
     */
    private void done() {
        int made = Math.max(0, this.rockets() - this.start);
        if (this.note != null) this.error(this.note);

        if (made > 0) {
            this.info("Successfully made " + made + " rockets.");
        } else if (this.note == null) {
            this.error("Need more ingredients for crafting.");
        }

        this.close = true;
        this.toggle();
    }

    //endregion

    //region Ingredient search

    /**
     * Checks whether a slot is empty or contains the requested item.
     *
     * @param slot slot index
     * @param item item to match
     * @return true when the slot is empty or contains the item
     */
    private boolean same(int slot, Item item) {
        ItemStack stack = Inventory.stack(slot);
        return stack.isEmpty() || stack.isOf(item);
    }

    /**
     * Finds the largest stack of an item meeting a minimum size.
     *
     * @param item item to find
     * @param min minimum stack size
     * @return matching slot index, or -1 when none exists
     */
    private int largest(Item item, int min) {
        return Inventory.search(
            stack -> stack.isOf(item) && stack.getCount() >= min,
            ItemStack::getCount
        );
    }

    /**
     * Finds a full inventory stack of an item.
     *
     * @param item item to find
     * @return matching slot index, or -1 when none exists
     */
    private int full(Item item) {
        return Inventory.search(stack -> stack.isOf(item) &&
            stack.getCount() == stack.getMaxCount()
        );
    }

    //endregion

    //region Rocket validation

    /**
     * Checks whether a stack contains Fd3 rockets.
     *
     * @param stack item stack to check
     * @return true when the stack contains Fd3 rockets
     */
    private boolean rocket(ItemStack stack) {
        if (!stack.isOf(Items.FIREWORK_ROCKET)) return false;

        FireworksComponent component = stack.get(DataComponentTypes.FIREWORKS);
        return component != null && component.flightDuration() == 3;
    }

    /**
     * Counts all Fd3 rockets in the player inventory.
     *
     * @return total Fd3 rocket count
     */
    private int rockets() {
        return Inventory.count(this::rocket);
    }

    //endregion
}
