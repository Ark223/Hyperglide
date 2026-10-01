package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.API;
import hyperglide.utilities.Client;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.Optional;

public class EasyAccess extends Module {
    private static final double edge = 1.0E-3;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Double> range = this.general.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("Maximum container interaction range.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6.0)
        .build()
    );

    private boolean lock;
    private boolean cancel;
    private boolean own;

    public EasyAccess() {
        super(Hyperglide.CATEGORY, "easy-access",
            "Opens hidden containers within interaction range."
        );
    }

    /**
     * Resets click state when the module starts.
     */
    @Override
    public void onActivate() {
        this.lock = this.mc.options.useKey.isPressed();
        this.cancel = false;
        this.own = false;
    }

    /**
     * Clears click state when the module stops.
     */
    @Override
    public void onDeactivate() {
        this.lock = false;
        this.cancel = false;
        this.own = false;
    }

    //region Event handlers

    /**
     * Finds and opens a hidden container.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!this.mc.options.useKey.isPressed()) {
            this.lock = false;
            this.cancel = false;
            return;
        }

        if (!Client.interaction() || this.lock) {
            return;
        }

        this.lock = true;
        this.cancel = false;

        if (this.visible()) return;

        Target target = this.target();
        if (target == null) return;

        this.cancel = true;
        this.own = true;

        try {
            if (target.entity != null) {
                this.entity(target.entity);
            } else {
                this.block(target.block);
            }
        } finally {
            this.own = false;
        }

        this.mc.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * Cancels the normal interaction after a hidden target was used.
     *
     * @param event outgoing packet event
     */
    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (!this.cancel || this.own) return;

        if (event.packet instanceof PlayerInteractBlockC2SPacket ||
            event.packet instanceof PlayerInteractEntityC2SPacket) {
            event.cancel();
        }
    }

    //endregion

    //region Target selection

    /**
     * Checks whether the crosshair target is an accessible container.
     *
     * @return true when the player is directly targeting a container
     */
    private boolean visible() {
        if (this.mc.crosshairTarget instanceof BlockHitResult hit &&
            hit.getType() == HitResult.Type.BLOCK) {
            return this.container(hit.getBlockPos());
        }

        if (this.mc.crosshairTarget instanceof EntityHitResult hit) {
            return this.container(hit.getEntity());
        }

        return false;
    }

    /**
     * Finds the closest hidden container in the view direction.
     *
     * @return closest container target, or null when none is available
     */
    private Target target() {
        double reach = this.range.get();

        Vec3d eye = this.mc.player.getEyePos();
        Vec3d look = this.mc.player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(reach));

        Target block = this.blocks(eye, end, reach);
        Target entity = this.entities(eye, end, reach);

        if (block == null) return entity;
        if (entity == null) return block;

        return block.distance <= entity.distance ? block : entity;
    }

    /**
     * Finds the closest hidden block container in the view direction.
     *
     * @param eye player eye position
     * @param end end of the interaction ray
     * @param reach maximum interaction range
     * @return closest block target, or null when none is available
     */
    private Target blocks(Vec3d eye, Vec3d end, double reach) {
        BlockPos center = BlockPos.ofFloored(eye);
        int radius = (int) Math.ceil(reach);

        Target best = null;
        double distance = Double.MAX_VALUE;
        double limit = reach * reach;

        for (BlockPos scan : BlockPos.iterateOutwards(
            center, radius, radius, radius
        )) {
            BlockPos pos = scan.toImmutable();
            if (!this.container(pos)) continue;

            Box box = new Box(pos).expand(edge);
            Optional<Vec3d> result = box.raycast(eye, end);
            if (result.isEmpty()) continue;

            Vec3d point = result.get();
            double current = eye.squaredDistanceTo(point);
            if (current > limit || current >= distance) {
                continue;
            }

            BlockHitResult hit = new BlockHitResult(
                point, Direction.UP, pos, false
            );

            best = new Target(hit, null, current);
            distance = current;
        }

        return best;
    }

    /**
     * Finds the closest hidden entity container in the view direction.
     *
     * @param eye player eye position
     * @param end end of the interaction ray
     * @param reach maximum interaction range
     * @return closest entity target, or null when none is available
     */
    private Target entities(Vec3d eye, Vec3d end, double reach) {
        Target best = null;
        double distance = Double.MAX_VALUE;
        double limit = reach * reach;

        for (Entity entity : this.mc.world.getEntities()) {
            if (!this.container(entity)) continue;

            Box box = entity.getBoundingBox().expand(edge);
            Optional<Vec3d> result = box.raycast(eye, end);
            if (result.isEmpty()) continue;

            Vec3d point = result.get();
            double current = eye.squaredDistanceTo(point);
            if (current > limit || current >= distance) {
                continue;
            }

            EntityHitResult hit = new EntityHitResult(entity, point);
            best = new Target(null, hit, current);
            distance = current;
        }

        return best;
    }

    //endregion

    //region Container interaction

    /**
     * Checks whether an entity is a container or merchant.
     *
     * @param entity entity to check
     * @return true when the entity is supported
     */
    private boolean container(Entity entity) {
        if (!entity.isAlive() || entity.isSpectator()) {
            return false;
        }

        if (entity instanceof VehicleInventory) return true;
        if (entity instanceof MerchantEntity) return true;

        return entity instanceof AbstractDonkeyEntity donkey
            && donkey.isTame() && donkey.hasChest();
    }

    /**
     * Interacts with a supported block container.
     *
     * @param hit block interaction target
     */
    private void block(BlockHitResult hit) {
        this.mc.interactionManager.interactBlock(
            this.mc.player, Hand.MAIN_HAND, hit
        );
    }

    /**
     * Interacts with a supported container entity.
     *
     * @param hit entity interaction target
     */
    private void entity(EntityHitResult hit) {
        Entity entity = hit.getEntity();

        boolean sneak = !this.mc.player.isSneaking()
            && entity instanceof AbstractDonkeyEntity;
        if (sneak) API.sneak(this.mc.player, true);

        try {
            ActionResult result =
                this.mc.interactionManager.interactEntityAtLocation(
                    this.mc.player, entity, hit, Hand.MAIN_HAND
                );

            if (!result.isAccepted()) {
                this.mc.interactionManager.interactEntity(
                    this.mc.player, entity, Hand.MAIN_HAND
                );
            }
        } finally {
            if (sneak) API.sneak(this.mc.player, false);
        }
    }

    /**
     * Checks whether a block position contains a supported container.
     *
     * @param pos block position to check
     * @return true when the block provides container interaction
     */
    private boolean container(BlockPos pos) {
        BlockState state = this.mc.world.getBlockState(pos);

        return state.isOf(Blocks.ENDER_CHEST)
            || state.createScreenHandlerFactory(this.mc.world, pos) != null;
    }

    //endregion

    //region Data structures

    /**
     * Stores a hidden container target and its squared distance.
     *
     * @param block block interaction target, or null
     * @param entity entity interaction target, or null
     * @param distance squared distance from the player
     */
    private record Target(
        BlockHitResult block, EntityHitResult entity, double distance
    ) {}

    //endregion
}
