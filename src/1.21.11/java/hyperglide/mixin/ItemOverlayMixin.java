package hyperglide.mixin;

import hyperglide.modules.Overview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DrawContext.class)
public abstract class ItemOverlayMixin {
    /**
     * Renders Overview over an item stack.
     *
     * @param entity entity rendering the item
     * @param world current world
     * @param stack rendered item stack
     * @param px item X coordinate
     * @param py item Y coordinate
     * @param seed item render seed
     * @param info injection callback
     */
    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;" +
        "Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;" +
        "III)V",
        at = @At("TAIL")
    )
    private void onDrawItem(LivingEntity entity, World world,
        ItemStack stack, int px, int py, int seed, CallbackInfo info) {

        Overview module = Modules.get().get(Overview.class);
        if (module != null) {
            module.render((DrawContext) (Object) this, stack, px, py);
        }
    }
}
