package hyperglide.mixin;

import hyperglide.utilities.Flight;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Filters local sounds used by elytra spoofing mode.
 */
@Mixin(SoundSystem.class)
public abstract class SoundMixin {
    /**
     * Mutes armor equip sounds caused by spoofing.
     *
     * @param sound sound being played
     * @param info sound callback
     */
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)" +
        "Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
        at = @At("HEAD"), cancellable = true
    )
    private void hyperglide$play(SoundInstance sound,
        CallbackInfoReturnable<SoundSystem.PlayResult> info) {

        if (!hyperglide$mute(sound)) return;
        info.setReturnValue(SoundSystem.PlayResult.NOT_STARTED);
    }

    /**
     * Mutes delayed armor equip sounds caused by spoofing.
     *
     * @param sound sound being played
     * @param delay playback delay
     * @param info sound callback
     */
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;I)V",
        at = @At("HEAD"), cancellable = true
    )
    private void hyperglide$playDelayed(
        SoundInstance sound, int delay, CallbackInfo info) {
        if (hyperglide$mute(sound)) info.cancel();
    }

    /**
     * Checks whether the sound belongs to an equipment swap.
     *
     * @param sound sound being played
     * @return true when the sound should be muted
     */
    private boolean hyperglide$mute(SoundInstance sound) {
        Flight flight = Flight.get();
        if (!flight.mute()) return false;

        String id = sound.getId().getPath();
        return id.startsWith("item.armor.equip_");
    }
}
