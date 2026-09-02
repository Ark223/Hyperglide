package hyperglide.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {
    /**
     * Returns the previous X coordinate.
     */
    @Accessor("lastX")
    double hyperglide$getX();

    /**
     * Returns the previous Y coordinate.
     */
    @Accessor("lastY")
    double hyperglide$getY();

    /**
     * Returns the previous Z coordinate.
     */
    @Accessor("lastZ")
    double hyperglide$getZ();
}
