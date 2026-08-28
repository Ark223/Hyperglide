package dev.arkieee.hyperglide.utilities;

import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.block.Block;
import java.util.List;

/**
 * Handles block whitelist and blacklist settings.
 */
public final class BlockFilter {
    private final Setting<List<Block>> blocks;
    private final Setting<Mode> mode;

    /**
     * Controls how the selected block list is used.
     */
    public enum Mode {
        Whitelist,
        Blacklist
    }

    /**
     * Creates block list and list mode settings.
     *
     * @param group setting group receiving the settings
     * @param description block list description
     */
    public BlockFilter(SettingGroup group, String description) {
        this.blocks = group.add(new BlockListSetting.Builder()
            .name("blocks")
            .description(description)
            .build()
        );

        this.mode = group.add(new EnumSetting.Builder<Mode>()
            .name("list-mode")
            .description("How the block list is used.")
            .defaultValue(Mode.Whitelist)
            .build()
        );
    }

    /**
     * Checks whether a block is allowed by the list.
     *
     * @param block block to check
     * @return true when the block is allowed
     */
    public boolean allowed(Block block) {
        return this.mode.get() == Mode.Blacklist
            ? !this.blocks.get().contains(block)
            : this.blocks.get().contains(block);
    }
}
