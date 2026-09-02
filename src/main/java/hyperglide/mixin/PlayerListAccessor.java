package hyperglide.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerListHud.class)
public interface PlayerListAccessor {
    /**
     * Returns the text shown below the player list.
     *
     * @return player list footer, or null when absent
     */
    @Accessor("footer")
    Text hyperglide$getFooter();
}
