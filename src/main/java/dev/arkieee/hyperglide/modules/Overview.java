package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public class Overview extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Integer> size = this.general.add(
        new IntSetting.Builder()
            .name("icon-size")
            .description("Size of the content icon.")
            .defaultValue(10)
            .min(5)
            .sliderMax(15)
            .build()
    );

    private final Setting<Integer> xoffset = this.general.add(
        new IntSetting.Builder()
            .name("x-offset")
            .description("Horizontal content icon offset.")
            .defaultValue(3)
            .min(-10)
            .sliderMax(5)
            .build()
    );

    private final Setting<Integer> yoffset = this.general.add(
        new IntSetting.Builder()
            .name("y-offset")
            .description("Vertical content icon offset.")
            .defaultValue(3)
            .min(-10)
            .sliderMax(5)
            .build()
    );

    private boolean drawing;
    private final WeakHashMap<ItemStack, Cached> cache = new WeakHashMap<>();

    public Overview() {
        super(Hyperglide.CATEGORY, "overview",
            "Displays the most common item over shulkers and bundles."
        );
    }

    /**
     * Clears cached content and resets rendering state.
     */
    @Override
    public void onActivate() {
        this.cache.clear();
        this.drawing = false;
    }

    /**
     * Clears cached content and resets rendering state.
     */
    @Override
    public void onDeactivate() {
        this.cache.clear();
        this.drawing = false;
    }

    //region Rendering

    /**
     * Renders the most common contained item over a shulker box or bundle.
     *
     * @param context draw context
     * @param stack container item stack
     * @param x item X coordinate
     * @param y item Y coordinate
     */
    public void render(DrawContext context, ItemStack stack, int x, int y) {
        if (!this.isActive() || this.drawing || stack.isEmpty()) {
            return;
        }

        Object data = this.data(stack);
        if (data == null) return;

        Cached cached = this.cache.get(stack);
        if (cached == null || !Objects.equals(cached.data, data)) {
            cached = new Cached(data, this.common(data));
            this.cache.put(stack, cached);
        }

        if (cached.stack.isEmpty()) return;

        int size = this.size.get();
        int ox = x + (16 - size) / 2 + this.xoffset.get();
        int oy = y + (16 - size) / 2 + this.yoffset.get();
        float scale = size / 16.0F;

        MatrixStack matrices = context.getMatrices();

        this.drawing = true;
        matrices.push();

        try {
            matrices.translate(ox, oy, 100.0F);
            matrices.scale(scale, scale, 1.0F);
            context.drawItem(cached.stack, 0, 0);
        } finally {
            matrices.pop();
            this.drawing = false;
        }
    }

    //endregion

    //region Content analysis

    /**
     * Retrieves container or bundle content data from an item stack.
     *
     * @param stack item stack to inspect
     * @return container data, bundle data or null when unavailable
     */
    private Object data(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem item &&
            item.getBlock() instanceof ShulkerBoxBlock) {
            return stack.get(DataComponentTypes.CONTAINER);
        }
        return stack.get(DataComponentTypes.BUNDLE_CONTENTS);
    }

    /**
     * Finds the item occupying the greatest number of content slots.
     *
     * @param data container or bundle content data
     * @return dominant item stack, or an empty stack when no item exists
     */
    private ItemStack common(Object data) {
        Iterable<ItemStack> stacks;

        if (data instanceof ContainerComponent container) {
            stacks = container.iterateNonEmpty();
        } else if (data instanceof BundleContentsComponent bundle) {
            stacks = bundle.iterate();
        } else {
            return ItemStack.EMPTY;
        }

        Map<Item, Count> counts = new LinkedHashMap<>();

        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;

            Count count = counts.computeIfAbsent(
                stack.getItem(),
                item -> new Count(stack.copyWithCount(1))
            );

            count.slots++;
        }

        Count best = null;

        for (Count count : counts.values()) {
            if (best == null || count.slots > best.slots) {
                best = count;
            }
        }

        return best == null ? ItemStack.EMPTY : best.stack;
    }

    //endregion

    //region Data structures

    /**
     * Tracks occurrences of an item in container contents.
     */
    private static class Count {
        private final ItemStack stack;
        private int slots;

        /**
         * Creates an item occurrence counter.
         *
         * @param stack representative item stack
         */
        private Count(ItemStack stack) {
            this.stack = stack;
        }
    }

    /**
     * Stores a cached container content result.
     */
    private static class Cached {
        private final Object data;
        private final ItemStack stack;

        /**
         * Creates a cached content result.
         *
         * @param data source content data
         * @param stack dominant item stack
         */
        private Cached(Object data, ItemStack stack) {
            this.data = data;
            this.stack = stack;
        }
    }

    //endregion
}
