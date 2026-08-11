package dev.arkieee.hyperglide.mixin;

import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the attacked entity ID from interaction packets.
 */
@Mixin(PlayerInteractEntityC2SPacket.class)
public interface AttackAccessor {
    @Accessor("entityId")
    int hyperglide$getEntityId();
}
