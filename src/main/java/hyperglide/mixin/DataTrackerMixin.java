package hyperglide.mixin;

import hyperglide.utilities.Flight;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracked;
import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.util.ArrayList;
import java.util.List;

@Mixin(DataTracker.class)
public abstract class DataTrackerMixin {
    private static final int flags = 0;

    @Shadow
    @Final
    private DataTracked trackedEntity;

    /**
     * Preserves local gliding flag while scheduling the server-side restart.
     *
     * @param entries incoming data updates
     * @return adjusted data updates
     */
    @ModifyVariable(
        method = "writeUpdatedEntries", at = @At("HEAD"), argsOnly = true
    )
    private List<DataTracker.SerializedEntry<?>> hyperglide$entries(
        List<DataTracker.SerializedEntry<?>> entries) {

        MinecraftClient client = MinecraftClient.getInstance();

        if (!(this.trackedEntity instanceof Entity entity) ||
            entity != client.player || !Flight.get().active()) {
            return entries;
        }

        List<DataTracker.SerializedEntry<?>> changed = null;

        for (int idx = 0; idx < entries.size(); idx++) {
            DataTracker.SerializedEntry<?> entry = entries.get(idx);

            if (entry.id() != flags ||
                !(entry.value() instanceof Byte value)) {
                continue;
            }

            byte synced = Flight.get().sync(value);
            if (synced == value) continue;

            if (changed == null) changed = new ArrayList<>(entries);
            changed.set(idx, hyperglide$entry(entry, synced));
        }

        return changed != null ? changed : entries;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DataTracker.SerializedEntry<?> hyperglide$entry(
        DataTracker.SerializedEntry<?> entry, byte value) {

        return new DataTracker.SerializedEntry(
            entry.id(), entry.handler(), value
        );
    }
}
