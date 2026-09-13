package hyperglide.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftClient.class)
public interface ClientAccessor {
    /**
     * Updates vanilla use cooldown of the held item.
     *
     * @param value cooldown in ticks
     */
    @Accessor("itemUseCooldown")
    void hyperglide$setUse(int value);
}
