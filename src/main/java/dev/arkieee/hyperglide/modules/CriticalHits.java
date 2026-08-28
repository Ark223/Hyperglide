package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.utilities.Client;
import dev.arkieee.hyperglide.utilities.Hotbar;
import dev.arkieee.hyperglide.mixin.AttackAccessor;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.input.Input;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class CriticalHits extends Module {
    private static final double delta = 1.0E-5;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Boolean> preserve = this.general.add(new BoolSetting.Builder()
        .name("preserve-weapon")
        .description("Preserves the weapon used when the attack starts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> resync = this.general.add(new BoolSetting.Builder()
        .name("resync-movement")
        .description("Resynchronizes movement after the anti-cheat setback.")
        .defaultValue(true)
        .build()
    );

    private PlayerInteractEntityC2SPacket cache;
    private ItemStack stack;

    private int entity;
    private int walk;
    private int ticks;
    private int freeze;
    private int stop;

    private boolean setback;
    private boolean ready;
    private boolean own;

    public CriticalHits() {
        super(Hyperglide.CATEGORY, "critical-hits",
            "Allows dealing critical hits while staying on ground."
        );
    }

    /**
     * Clears pending attack state.
     */
    @Override
    public void onActivate() {
        this.reset();
    }

    /**
     * Sends any pending attack and clears state.
     */
    @Override
    public void onDeactivate() {
        this.release();
        this.reset();
    }

    //region Event handlers

    /**
     * Handles attack, swing and setback movement packets.
     *
     * @param event outgoing packet event
     */
    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (this.own || !Client.ready()) return;

        if (event.packet instanceof PlayerMoveC2SPacket movement && this.cache != null) {
            event.cancel();

            if (!this.setback) return;

            this.setback = false;

            this.send(this.correct(movement));
            this.release();
            this.clear();
            return;
        }

        if (event.packet instanceof HandSwingC2SPacket && this.cache != null) {
            event.cancel();
            return;
        }

        if (!(event.packet instanceof PlayerInteractEntityC2SPacket packet) ||
            !this.attack(packet) || this.blocked()) {
            return;
        }

        if (this.cache != null) {
            event.cancel();
            return;
        }

        int id = ((AttackAccessor) packet).hyperglide$getEntityId();
        if (!(this.mc.world.getEntityById(id) instanceof LivingEntity)) return;

        if (!this.mc.player.isOnGround()) return;

        event.cancel();

        this.cache = packet;
        this.stack = this.preserve.get()
            ? this.mc.player.getMainHandStack().copy()
            : ItemStack.EMPTY;

        this.entity = id;

        if (this.moving()) {
            if (this.mc.player.isSprinting()) {
                this.mc.player.setSprinting(false);

                this.send(new ClientCommandC2SPacket(
                    this.mc.player, Mode.STOP_SPRINTING
                ));
            }

            this.stop = 2;
            return;
        }

        this.trigger();
    }

    /**
     * Starts the delayed attack after movement has stopped.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!this.ready || this.cache == null || this.stop > 0) {
            return;
        }

        this.ready = false;
        this.trigger();
    }

    /**
     * Detects a server movement correction during a delayed attack.
     *
     * @param event incoming packet event
     */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (this.cache == null ||
            !(event.packet instanceof PlayerPositionLookS2CPacket)) {
            return;
        }

        if (this.resync.get()) this.ticks = 1;
        this.setback = true;
    }

    //endregion

    //region State management

    /**
     * Clears only the delayed attack state.
     */
    private void clear() {
        this.cache = null;
        this.stack = null;
        this.entity = -1;

        this.stop = 0;
        this.setback = false;
        this.ready = false;
    }

    /**
     * Clears all runtime state.
     */
    private void reset() {
        this.clear();

        this.walk = 0;
        this.ticks = 0;
        this.freeze = 0;
        this.own = false;
    }

    //endregion

    //region Setback handling

    /**
     * Adjusts a movement packet to face the target.
     *
     * @param packet movement packet to adjust
     * @return adjusted movement packet
     */
    private PlayerMoveC2SPacket correct(PlayerMoveC2SPacket packet) {
        if (!(this.mc.world.getEntityById(this.entity)
            instanceof LivingEntity target)) return packet;

        Vec3d direction = target.getEyePos()
            .subtract(this.mc.player.getEyePos());

        double horizontal = Math.sqrt(
            direction.x * direction.x +
            direction.z * direction.z
        );

        float yaw = (float) Math.toDegrees(
            Math.atan2(direction.z, direction.x)
        ) - 90.0F;

        float pitch = (float) -Math.toDegrees(
            Math.atan2(direction.y, horizontal)
        );

        return new PlayerMoveC2SPacket.Full(
            packet.getX(this.mc.player.getX()),
            packet.getY(this.mc.player.getY()),
            packet.getZ(this.mc.player.getZ()),
            yaw,
            MathHelper.clamp(pitch, -90.0F, 90.0F),
            packet.isOnGround(),
            packet.horizontalCollision()
        );
    }

    /**
     * Handles movement before and after a delayed attack.
     *
     * @param input player input after keyboard processing
     */
    public void input(Input input) {
        if (!this.isActive() || !Client.ready() ||
            input.playerInput == null) return;

        PlayerInput state = input.playerInput;

        if (this.cache != null && !this.setback) {
            state = PlayerInput.DEFAULT;

            input.movementForward = 0.0F;
            input.movementSideways = 0.0F;

            Vec3d velocity = this.mc.player.getVelocity();
            this.mc.player.setVelocity(0.0, velocity.y, 0.0);
            this.mc.player.setSprinting(false);

            if (this.stop > 0) {
                this.send(new PlayerMoveC2SPacket.PositionAndOnGround(
                    this.mc.player.getX(),
                    this.mc.player.getY(),
                    this.mc.player.getZ(),
                    true, false
                ));

                if (--this.stop == 0) this.ready = true;
            }
        }

        if (this.ticks > 0) {
            boolean moving =
                state.forward() || state.backward()
                || state.left() || state.right();

            if (!moving) {
                boolean left = (++this.walk & 1) == 0;

                state = new PlayerInput(
                    false, false, left, !left,
                    state.jump(), state.sneak(), false
                );

                input.movementForward = 0.0F;
                input.movementSideways = left ? 1.0F : -1.0F;
            }

            this.ticks--;
        }

        if (this.freeze > 0) {
            state = new PlayerInput(false,
                state.backward(),
                state.left(), state.right(),
                state.jump(), state.sneak(),
                false
            );

            input.movementForward = state.backward() ? -1.0F : 0.0F;

            this.mc.player.setSprinting(false);
            this.freeze--;
        }

        input.playerInput = state;
    }

    //endregion

    //region Attack handling

    /**
     * Checks whether an interaction packet represents an attack.
     *
     * @param packet interaction packet
     * @return true when the packet is an attack
     */
    private boolean attack(PlayerInteractEntityC2SPacket packet) {
        Attack handler = new Attack();
        packet.handle(handler);
        return handler.attack;
    }

    /**
     * Starts the movement sequence for the delayed attack.
     */
    private void trigger() {
        if (this.cache == null || !Client.ready()) return;

        if (this.mc.player.isSprinting()) {
            this.mc.player.setSprinting(false);

            this.send(new ClientCommandC2SPacket(
                this.mc.player, Mode.STOP_SPRINTING
            ));
        }

        double px = this.mc.player.getX();
        double py = this.mc.player.getY();
        double pz = this.mc.player.getZ();

        this.send(new PlayerMoveC2SPacket.PositionAndOnGround(
            px, py + delta, pz, true, false
        ));

        this.send(new PlayerMoveC2SPacket.PositionAndOnGround(
            px, py + 1.0, pz, false, false
        ));

        this.freeze = 3;
    }

    /**
     * Sends the delayed attack with the preserved weapon.
     */
    private void release() {
        if (this.cache == null || !Client.ready()) return;

        PlayerInteractEntityC2SPacket packet = this.cache;

        int current = Hotbar.selected();
        int weapon = current;

        if (this.preserve.get() &&
            this.stack != null && !this.stack.isEmpty() &&
            !ItemStack.areItemsAndComponentsEqual(
                this.mc.player.getMainHandStack(), this.stack
            )) {

            int found = Hotbar.find(candidate ->
                ItemStack.areItemsAndComponentsEqual(candidate, this.stack)
            );

            if (found >= 0) weapon = found;
        }

        if (weapon != current) this.select(weapon);

        this.send(packet);
        this.send(new HandSwingC2SPacket(Hand.MAIN_HAND));

        if (weapon != current) this.select(current);
    }

    /**
     * Synchronizes a selected hotbar slot with the server.
     *
     * @param slot hotbar slot to select
     */
    private void select(int slot) {
        this.send(new UpdateSelectedSlotC2SPacket(slot));
    }

    /**
     * Sends a packet without handling it again.
     *
     * @param packet packet to send
     */
    private void send(Packet<?> packet) {
        if (!Client.ready()) return;

        boolean own = this.own;
        this.own = true;

        try {
            this.mc.getNetworkHandler().sendPacket(packet);
        } finally {
            this.own = own;
        }
    }

    //endregion

    //region Attack validation

    /**
     * Checks whether movement needs to be stopped before attacking.
     *
     * @return true when the player is currently moving
     */
    private boolean moving() {
        Vec3d velocity = this.mc.player.getVelocity();

        return this.mc.player.isSprinting() ||
            this.mc.options.forwardKey.isPressed() ||
            this.mc.options.backKey.isPressed() ||
            this.mc.options.leftKey.isPressed() ||
            this.mc.options.rightKey.isPressed() ||
            Math.abs(velocity.x) > 1.0E-4 ||
            Math.abs(velocity.z) > 1.0E-4;
    }

    /**
     * Checks whether critical hits should be skipped.
     *
     * @return true when hits should be skipped
     */
    private boolean blocked() {
        return this.mc.player.isTouchingWater() ||
            this.mc.player.hasVehicle() || this.web();
    }

    /**
     * Checks whether the player hitbox intersects a cobweb.
     *
     * @return true while touching a cobweb
     */
    private boolean web() {
        Box box = this.mc.player.getBoundingBox().contract(1.0E-7);

        int minx = MathHelper.floor(box.minX);
        int miny = MathHelper.floor(box.minY);
        int minz = MathHelper.floor(box.minZ);

        int maxx = MathHelper.floor(box.maxX);
        int maxy = MathHelper.floor(box.maxY);
        int maxz = MathHelper.floor(box.maxZ);

        for (BlockPos pos : BlockPos.iterate(minx, miny, minz, maxx, maxy, maxz)) {
            if (this.mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) {
                return true;
            }
        }

        return false;
    }

    //endregion

    //region Data structures

    /**
     * Tracks whether an entity interaction represents an attack.
     */
    private static class Attack implements PlayerInteractEntityC2SPacket.Handler {
        private boolean attack;

        /**
         * Ignores normal entity interaction.
         *
         * @param hand interaction hand
         */
        @Override
        public void interact(Hand hand) {
        }

        /**
         * Ignores interaction at a specific entity position.
         *
         * @param hand interaction hand
         * @param pos interaction position
         */
        @Override
        public void interactAt(Hand hand, Vec3d pos) {
        }

        /**
         * Marks the interaction as an attack.
         */
        @Override
        public void attack() {
            this.attack = true;
        }
    }

    //endregion
}
