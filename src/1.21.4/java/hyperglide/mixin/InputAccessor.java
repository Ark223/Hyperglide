package hyperglide.mixin;

import net.minecraft.client.input.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Input.class)
public interface InputAccessor {
    /**
     * Updates the processed forward movement value.
     *
     * @param value forward movement amount
     */
    @Accessor("movementForward")
    void hyperglide$forward(float value);

    /**
     * Updates the processed sideways movement value.
     *
     * @param value sideways movement amount
     */
    @Accessor("movementSideways")
    void hyperglide$sideways(float value);
}
