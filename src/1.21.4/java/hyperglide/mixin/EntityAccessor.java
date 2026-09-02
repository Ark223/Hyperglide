package hyperglide.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {
    /**
     * Returns the previous X coordinate.
     */
    @Accessor("prevX")
    double hyperglide$getX();

    /**
     * Returns the previous Y coordinate.
     */
    @Accessor("prevY")
    double hyperglide$getY();

    /**
     * Returns the previous Z coordinate.
     */
    @Accessor("prevZ")
    double hyperglide$getZ();
}
