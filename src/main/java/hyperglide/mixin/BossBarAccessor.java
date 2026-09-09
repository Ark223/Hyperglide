package hyperglide.mixin;

import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
import java.util.UUID;

@Mixin(BossBarHud.class)
public interface BossBarAccessor {
    /**
     * Returns the boss bars tracked by the client.
     *
     * @return active boss bars
     */
    @Accessor("bossBars")
    Map<UUID, ClientBossBar> hyperglide$getBars();
}
