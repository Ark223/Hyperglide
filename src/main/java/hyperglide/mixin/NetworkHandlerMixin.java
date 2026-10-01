package hyperglide.mixin;

import hyperglide.utilities.Flight;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class NetworkHandlerMixin {
    /**
     * Delays ping responses while the spoofed gliding state is preserved.
     *
     * @param packet incoming common ping
     * @param info injection callback
     */
    @Inject(method = "onPing", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/network/ClientCommonNetworkHandler;sendPacket(" +
        "Lnet/minecraft/network/packet/Packet;)V"), cancellable = true
    )
    private void hyperglide$ping(CommonPingS2CPacket packet, CallbackInfo info) {
        if (Flight.get().ping(packet.getParameter())) {
            info.cancel();
        }
    }
}
