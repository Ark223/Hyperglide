package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Handles common player inventory searches and slot actions.
 */
public final class Inventory {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static final int first = PlayerScreenHandler.INVENTORY_START;
    private static final int last = PlayerScreenHandler.HOTBAR_END - 1;
    private static final int size = 36;

    private Inventory() {}

    /**
     * Checks whether the normal player inventory is active.
     *
     * @return true when player inventory slots can be used
     */
    public static boolean ready() {
        return client.player.currentScreenHandler
            == client.player.playerScreenHandler;
    }

    /**
     * Returns an item stack from a player screen slot.
     *
     * @param slot screen slot index
     * @return item stack stored in the slot
     */
    public static ItemStack stack(int slot) {
        return handler().getSlot(slot).getStack();
    }

    /**
     * Returns the current cursor stack.
     *
     * @return cursor item stack
     */
    public static ItemStack cursor() {
        return handler().getCursorStack();
    }

    /**
     * Converts an inventory slot to the matching screen slot.
     *
     * @param slot player inventory slot
     * @return matching player screen slot
     */
    public static int slot(int slot) {
        return slot < 9 ? slot + 36 : slot;
    }

    /**
     * Finds an item in the player inventory.
     *
     * @param item item to find
     * @return matching player inventory slot, or -1 when unavailable
     */
    public static int find(Item item) {
        return find(stack -> stack.isOf(item));
    }

    /**
     * Finds the first matching stack in the player inventory.
     *
     * @param predicate stack condition
     * @return matching player inventory slot, or -1 when unavailable
     */
    public static int find(Predicate<ItemStack> predicate) {
        return find(0, size - 1, predicate);
    }

    /**
     * Finds the first matching stack inside an inventory range.
     *
     * @param first first player inventory slot
     * @param last last player inventory slot
     * @param predicate stack condition
     * @return matching player inventory slot, or -1 when unavailable
     */
    public static int find(int first, int last, Predicate<ItemStack> predicate) {
        int start = Math.min(first, last);
        int end = Math.max(first, last);

        for (int slot = start; slot <= end; slot++) {
            if (predicate.test(item(slot))) return slot;
        }

        return -1;
    }

    /**
     * Finds the highest scoring stack in the player inventory.
     *
     * @param predicate stack condition
     * @param score stack score
     * @return matching player inventory slot, or -1 when unavailable
     */
    public static int best(
        Predicate<ItemStack> predicate,
        ToIntFunction<ItemStack> score) {

        int slot = -1;
        int best = Integer.MIN_VALUE;

        for (int idx = 0; idx < size; idx++) {
            ItemStack stack = item(idx);
            if (!predicate.test(stack)) continue;

            int current = score.applyAsInt(stack);
            if (current <= best) continue;

            best = current;
            slot = idx;
        }

        return slot;
    }

    /**
     * Finds the first matching player screen slot.
     *
     * @param predicate stack condition
     * @return matching screen slot, or -1 when unavailable
     */
    public static int search(Predicate<ItemStack> predicate) {
        for (int slot = first; slot <= last; slot++) {
            if (predicate.test(stack(slot))) return slot;
        }
        return -1;
    }

    /**
     * Finds the highest scoring player screen stack.
     *
     * @param predicate stack condition
     * @param score stack score
     * @return matching screen slot, or -1 when unavailable
     */
    public static int search(
        Predicate<ItemStack> predicate,
        ToIntFunction<ItemStack> score) {

        int slot = -1;
        int best = Integer.MIN_VALUE;

        for (int idx = first; idx <= last; idx++) {
            ItemStack stack = stack(idx);
            if (!predicate.test(stack)) continue;

            int current = score.applyAsInt(stack);
            if (current <= best) continue;

            best = current;
            slot = idx;
        }

        return slot;
    }

    /**
     * Counts an item across the player inventory.
     *
     * @param item item to count
     * @return total item count
     */
    public static int count(Item item) {
        return count(stack -> stack.isOf(item));
    }

    /**
     * Counts units from matching inventory stacks.
     *
     * @param predicate stack condition
     * @return total matching item count
     */
    public static int count(Predicate<ItemStack> predicate) {
        int count = 0;

        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = item(slot);

            if (predicate.test(stack)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    /**
     * Counts matching stacks in the player inventory.
     *
     * @param predicate stack condition
     * @return number of matching stacks
     */
    public static int stacks(Predicate<ItemStack> predicate) {
        int count = 0;

        for (int slot = 0; slot < size; slot++) {
            if (predicate.test(item(slot))) count++;
        }

        return count;
    }

    /**
     * Finds the first empty player screen slot.
     *
     * @return empty screen slot, or -1 when unavailable
     */
    public static int empty() {
        return search(ItemStack::isEmpty);
    }

    /**
     * Finds the first empty slot from the supplied screen slots.
     *
     * @param slots screen slots to check
     * @return empty screen slot, or -1 when unavailable
     */
    public static int empty(int... slots) {
        for (int slot : slots) {
            if (stack(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /**
     * Finds the first two matching player screen slots.
     *
     * @param predicate stack condition
     * @return matching screen slots, or null when fewer than two exist
     */
    public static int[] pair(Predicate<ItemStack> predicate) {
        int found = -1;

        for (int slot = first; slot <= last; slot++) {
            if (!predicate.test(stack(slot))) continue;

            if (found == -1) {
                found = slot;
            } else {
                return new int[] { found, slot };
            }
        }

        return null;
    }

    /**
     * Performs an action on a player inventory slot.
     *
     * @param slot screen slot index
     * @param button mouse button or action data
     * @param action slot action type
     */
    public static void click(int slot, int button, SlotActionType action) {
        client.interactionManager.clickSlot(
            handler().syncId, slot, button, action, client.player
        );
    }

    /**
     * Picks up or places the stack in a player screen slot.
     *
     * @param slot screen slot index
     */
    public static void pick(int slot) {
        click(slot, 0, SlotActionType.PICKUP);
    }

    /**
     * Quick-moves a stack between inventory sections.
     *
     * @param slot screen slot index
     */
    public static void move(int slot) {
        click(slot, 0, SlotActionType.QUICK_MOVE);
    }

    /**
     * Swaps a player screen slot with a hotbar slot.
     *
     * @param slot screen slot index
     * @param hotbar hotbar slot
     */
    public static void swap(int slot, int hotbar) {
        click(slot, hotbar, SlotActionType.SWAP);
    }

    /**
     * Distributes the cursor stack evenly across selected screen slots.
     *
     * @param slots screen slots receiving the stack
     */
    public static void drag(int... slots) {
        click(ScreenHandler.EMPTY_SPACE_SLOT_INDEX,
            ScreenHandler.packQuickCraftData(0, 0),
            SlotActionType.QUICK_CRAFT
        );

        for (int slot : slots) {
            click(slot, ScreenHandler.packQuickCraftData(1, 0),
                SlotActionType.QUICK_CRAFT
            );
        }

        click(ScreenHandler.EMPTY_SPACE_SLOT_INDEX,
            ScreenHandler.packQuickCraftData(2, 0),
            SlotActionType.QUICK_CRAFT
        );
    }

    /**
     * Returns an item stack from the player inventory.
     *
     * @param slot player inventory slot
     * @return item stack stored in the slot
     */
    private static ItemStack item(int slot) {
        return client.player.getInventory().getStack(slot);
    }

    /**
     * Returns the normal player inventory handler.
     *
     * @return player inventory screen handler
     */
    private static PlayerScreenHandler handler() {
        return client.player.playerScreenHandler;
    }
}
