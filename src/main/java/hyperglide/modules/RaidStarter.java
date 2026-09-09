package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.mixin.BossBarAccessor;
import hyperglide.utilities.Client;
import hyperglide.utilities.Hotbar;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

public class RaidStarter extends Module {
    private static final int cooldown = 10;

    private int slot = -1;
    private int timer;

    private boolean using;

    public RaidStarter() {
        super(Hyperglide.CATEGORY, "raid-starter",
            "Automatically drinks ominous bottles between raids."
        );
    }

    /**
     * Clears drinking state before the module starts.
     */
    @Override
    public void onActivate() {
        this.slot = -1;
        this.timer = 0;
        this.using = false;
    }

    /**
     * Stops automatic drinking when the module is disabled.
     */
    @Override
    public void onDeactivate() {
        this.stop();
    }

    //region Event handlers

    /**
     * Drinks an ominous bottle when no omen or raid is active.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Client.ready() || !Client.interaction()) {
            this.stop();
            return;
        }

        if (this.using) {
            if (this.omen() || !this.mc.player.isUsingItem()) {
                this.stop();
            }

            return;
        }

        if (this.omen()) {
            this.timer = cooldown;
            return;
        }

        if (this.timer > 0) {
            this.timer--;
            return;
        }

        if (this.raid() || this.mc.player.isUsingItem() ||
            this.mc.options.useKey.isPressed()) {
            return;
        }

        this.drink();
    }

    //endregion

    //region Raid management

    /**
     * Starts drinking an ominous bottle from the hotbar.
     */
    private void drink() {
        if (this.mc.interactionManager == null) {
            return;
        }

        int bottle = Hotbar.find(this::bottle);
        if (bottle < 0) return;

        this.slot = Hotbar.selected();
        if (bottle != this.slot) Hotbar.select(bottle);

        this.using = true;
        this.mc.options.useKey.setPressed(true);

        ActionResult result = this.mc.interactionManager.interactItem(
            this.mc.player, Hand.MAIN_HAND
        );

        if (!result.isAccepted()) this.stop();
    }

    /**
     * Stops drinking and restores the previous hotbar slot.
     */
    private void stop() {
        if (!this.using) return;

        this.mc.options.useKey.setPressed(false);

        if (this.slot >= 0 && this.mc.player != null &&
            Hotbar.selected() != this.slot) {
            Hotbar.select(this.slot);
        }

        this.slot = -1;
        this.timer = cooldown;
        this.using = false;
    }

    //endregion

    //region Utilities and validation

    /**
     * Checks whether a stack can be used as an ominous bottle.
     *
     * @param stack item stack to inspect
     * @return true when the stack represents an ominous bottle
     */
    private boolean bottle(ItemStack stack) {
        if (stack.isOf(Items.OMINOUS_BOTTLE)) return true;
        if (!stack.isOf(Items.HONEY_BOTTLE)) return false;

        String name = stack.getName().getString();
        return name.contains("Ominous Bottle");
    }

    /**
     * Checks whether Bad Omen or Raid Omen is active.
     *
     * @return true while an omen effect is active
     */
    private boolean omen() {
        return this.mc.player.hasStatusEffect(StatusEffects.BAD_OMEN)
            || this.mc.player.hasStatusEffect(StatusEffects.RAID_OMEN);
    }

    /**
     * Checks whether the client currently has an active raid boss bar.
     *
     * @return true while a village raid is active
     */
    private boolean raid() {
        BossBarAccessor access =
            (BossBarAccessor) this.mc.inGameHud.getBossBarHud();

        for (ClientBossBar bar : access.hyperglide$getBars().values()) {
            if (this.raid(bar.getName())) return true;
        }

        return false;
    }

    /**
     * Checks whether a boss bar belongs to a village raid.
     *
     * @param name boss bar name
     * @return true when the bar represents a raid
     */
    private boolean raid(Text name) {
        TextContent content = name.getContent();
        return content instanceof TranslatableTextContent text
            && text.getKey().startsWith("event.minecraft.raid")
            && !text.getKey().contains(".victory");
    }

    //endregion
}
