package dev.arkieee.hyperglide.mixin;

import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerInteractEntityC2SPacket.class)
public interface AttackAccessor {
    /**
     * Returns the attacked entity ID.
     *
     * @return attacked entity ID
     */
    @Accessor("entityId")
    int hyperglide$getEntityId();
}
