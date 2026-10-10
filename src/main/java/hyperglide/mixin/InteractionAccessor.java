package hyperglide.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionAccessor {
    /**
     * Sends an interaction packet through the vanilla sequencing path.
     *
     * @param world client world
     * @param creator packet creator
     */
    @Invoker("sendSequencedPacket")
    void hyperglide$sequence(ClientWorld world, SequencedPacketCreator creator);
}
