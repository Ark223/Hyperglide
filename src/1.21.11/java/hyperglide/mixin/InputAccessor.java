package hyperglide.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Input.class)
public interface InputAccessor {
    /**
     * Updates the processed player movement vector.
     *
     * @param value forward and sideways movement
     */
    @Accessor("movementVector")
    void hyperglide$setMovement(Vec2f value);
}
