package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.consume.UseAction;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.Set;

public class TriggerBot extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Set<EntityType<?>>> entities = this.general.add(
        new EntityTypeListSetting.Builder()
            .name("entities")
            .description("Selected entities to attack.")
            .onlyAttackable()
            .defaultValue(EntityType.PLAYER)
            .build()
    );

    private final Setting<Double> range = this.general.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("Maximum distance Trigger Bot can attack.")
        .defaultValue(3.0)
        .min(0.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Boolean> wall = this.general.add(new BoolSetting.Builder()
        .name("wall-check")
        .description("Prevents attacking entities through walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the current attack target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How the target box is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> side = this.visuals.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color of the target box.")
        .defaultValue(new SettingColor(255, 255, 255, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> line = this.visuals.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline color of the target box.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(this.render::get)
        .build()
    );

    private Entity target;

    public TriggerBot() {
        super(Hyperglide.CATEGORY, "trigger-bot",
            "Attacks selected entities while looking at them."
        );
    }

    /**
     * Clears the current target.
     */
    @Override
    public void onActivate() {
        this.target = null;
    }

    /**
     * Clears the current target.
     */
    @Override
    public void onDeactivate() {
        this.target = null;
    }

    //region Event handlers

    /**
     * Updates the current target and attacks when cooldown is ready.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (this.mc.player == null ||
            this.mc.world == null ||
            this.mc.interactionManager == null ||
            this.mc.getCameraEntity() == null) {
            this.target = null;
            return;
        }

        if (this.consuming()) {
            this.target = null;
            return;
        }

        this.target = this.target();

        if (this.target == null ||
            this.mc.player.getAttackCooldownProgress(0.5F) < 1.0F) {
            return;
        }

        this.mc.interactionManager.attackEntity(
            this.mc.player, this.target
        );

        this.mc.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * Renders the current attack target.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get() || !this.valid(this.target)) return;

        event.renderer.box(this.target.getBoundingBox(),
            this.side.get(), this.line.get(), this.shape.get(), 0
        );
    }

    //endregion

    //region Targeting

    /**
     * Finds the selected entity whose hitbox is under the crosshair.
     *
     * @return targeted entity, or null when no valid entity is targeted
     */
    private Entity target() {
        Entity camera = this.mc.getCameraEntity();
        double range = this.range.get();

        Vec3d start = camera.getCameraPosVec(1.0F);
        Vec3d direction = camera.getRotationVec(1.0F);
        Vec3d end = start.add(direction.multiply(range));

        Box box = camera.getBoundingBox();
        box = box.stretch(direction.multiply(range));
        box = box.expand(1.0);

        EntityHitResult hit = ProjectileUtil.raycast(
            camera, start, end, box,
            this::valid, range * range
        );

        if (hit == null) return null;

        if (this.wall.get()) {
            HitResult block = camera.raycast(range, 0.0F, false);

            if (block.getType() != HitResult.Type.MISS
                && block.squaredDistanceTo(camera) <
                    hit.squaredDistanceTo(camera)) {
                return null;
            }
        }

        return hit.getEntity();
    }

    //endregion

    //region Validation

    /**
     * Checks whether the player is eating or drinking.
     *
     * @return true while consuming an item
     */
    private boolean consuming() {
        if (!this.mc.player.isUsingItem()) return false;

        UseAction action = this.mc.player.getActiveItem().getUseAction();
        return action == UseAction.DRINK || action == UseAction.EAT;
    }

    /**
     * Checks whether an entity can be targeted by Trigger Bot.
     *
     * @param entity entity to check
     * @return true when the entity is a valid selected target
     */
    private boolean valid(Entity entity) {
        return entity != null &&
            entity != this.mc.player &&
            entity != this.mc.getCameraEntity() &&
            entity.isAlive() &&
            !entity.isSpectator() &&
            entity.canHit() &&
            this.entities.get().contains(entity.getType());
    }

    //endregion
}
