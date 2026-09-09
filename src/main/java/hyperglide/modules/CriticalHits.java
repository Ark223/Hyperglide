package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.API;
import hyperglide.utilities.Client;
import hyperglide.utilities.Hotbar;
import hyperglide.mixin.AttackAccessor;
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

    private final Setting<Boolean> lock = this.general.add(new BoolSetting.Builder()
        .name("lock-weapon")
        .description("Preserves weapon used when the attack starts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> resync = this.general.add(new BoolSetting.Builder()
        .name("resync-mode")
        .description("Resynchronizes movement to prevent rubberbanding.")
        .defaultValue(true)
        .build()
    );

    private PlayerInteractEntityC2SPacket cache;
    private ItemStack stack;

    private int walk;
    private int ticks;
    private int freeze;
    private int wait;

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
     * Handles delayed attacks and prevents conflicting movement.
     *
     * @param event outgoing packet event
     */
    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (this.own || !Client.ready()) return;
        if (this.pending(event)) return;

        if (event.packet instanceof PlayerInteractEntityC2SPacket packet) {
            this.capture(event, packet);
        }
    }

    /**
     * Starts the delayed attack after movement has stopped.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!this.ready || this.cache == null) return;

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
        this.wait = 0;
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

    //region Movement handling

    /**
     * Updates player input during delayed attacks and corrections.
     *
     * @param input player input after keyboard processing
     */
    public void input(Input input) {
        if (!this.isActive() || !Client.ready() ||
            input.playerInput == null) return;

        PlayerInput state = input.playerInput;

        if (this.cache != null && !this.setback) {
            state = this.hold(input);
        }

        if (this.ticks > 0) {
            state = this.resync(input, state);
        }

        if (this.freeze > 0) {
            state = this.restrict(input, state);
        }

        input.playerInput = state;
    }

    /**
     * Holds horizontal movement while the delayed attack is prepared.
     *
     * @param input player input to clear
     * @return cleared player input
     */
    private PlayerInput hold(Input input) {
        API.move(input, 0.0F, 0.0F);

        Vec3d velocity = this.mc.player.getVelocity();
        this.mc.player.setVelocity(0.0, velocity.y, 0.0);
        this.mc.player.setSprinting(false);

        if (this.wait > 0) {
            this.send(new PlayerMoveC2SPacket.PositionAndOnGround(
                this.mc.player.getX(),
                this.mc.player.getY(),
                this.mc.player.getZ(),
                true, false
            ));

            if (--this.wait == 0) this.ready = true;
        }

        return PlayerInput.DEFAULT;
    }

    /**
     * Adds a short sideways input after a server movement correction.
     *
     * @param input player input to update
     * @param state current key state
     * @return adjusted key state
     */
    private PlayerInput resync(Input input, PlayerInput state) {
        boolean moving =
            state.forward() || state.backward() ||
            state.left() || state.right();

        if (!moving) {
            boolean left = (++this.walk & 1) == 0;

            state = new PlayerInput(
                false, false, left, !left,
                state.jump(), state.sneak(), false
            );

            API.move(input, 0.0F, left ? 1.0F : -1.0F);
        }

        this.ticks--;
        return state;
    }

    /**
     * Prevents forward movement during the critical hit sequence.
     *
     * @param input player input to update
     * @param state current key state
     * @return adjusted key state
     */
    private PlayerInput restrict(Input input, PlayerInput state) {
        state = new PlayerInput(false,
            state.backward(), state.left(), state.right(),
            state.jump(), state.sneak(), false
        );

        API.move(input,
            state.backward() ? -1.0F : 0.0F,
            input.getMovementInput().x
        );

        this.mc.player.setSprinting(false);
        this.freeze--;
        return state;
    }

    /**
     * Adjusts a movement packet to face the delayed attack target.
     *
     * @param packet movement packet to adjust
     * @return adjusted movement packet
     */
    private PlayerMoveC2SPacket correct(PlayerMoveC2SPacket packet) {
        if (this.cache == null) return packet;

        int id = ((AttackAccessor) this.cache).hyperglide$getEntityId();
        if (!(this.mc.world.getEntityById(id) instanceof LivingEntity target)) {
            return packet;
        }

        Vec3d direction = target.getEyePos().subtract(this.mc.player.getEyePos());
        double horizontal = Math.hypot(direction.x, direction.z);

        float yaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));

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

    //endregion

    //region Attack handling

    /**
     * Cancels movement and swing packets while an attack is pending.
     *
     * @param event outgoing packet event
     * @return true when the packet was handled
     */
    private boolean pending(PacketEvent.Send event) {
        if (this.cache == null) return false;

        if (event.packet instanceof PlayerMoveC2SPacket packet) {
            event.cancel();

            if (this.setback) {
                this.setback = false;

                this.send(this.correct(packet));
                this.release();
                this.clear();
            }

            return true;
        }

        if (event.packet instanceof HandSwingC2SPacket) {
            event.cancel();
            return true;
        }

        return false;
    }

    /**
     * Saves a valid grounded attack for the critical hit sequence.
     *
     * @param event outgoing packet event
     * @param packet entity interaction packet
     */
    private void capture(PacketEvent.Send event,
        PlayerInteractEntityC2SPacket packet) {

        if (!this.attack(packet) || this.blocked()) return;

        if (this.cache != null) {
            event.cancel();
            return;
        }

        int id = ((AttackAccessor) packet).hyperglide$getEntityId();
        if (!(this.mc.world.getEntityById(id) instanceof LivingEntity) ||
            !this.mc.player.isOnGround()) {
            return;
        }

        event.cancel();

        this.cache = packet;
        this.stack = this.lock.get()
            ? this.mc.player.getMainHandStack().copy()
            : ItemStack.EMPTY;

        if (this.moving()) {
            this.stop();
            this.wait = 2;
            return;
        }

        this.trigger();
    }

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
        if (this.cache == null || !Client.ready()) {
            return;
        }

        this.stop();

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
     * Sends the delayed attack using the original weapon.
     */
    private void release() {
        if (this.cache == null || !Client.ready()) {
            return;
        }

        int current = Hotbar.selected();
        int weapon = this.weapon(current);

        if (weapon != current) this.sync(weapon);

        this.send(this.cache);
        this.send(new HandSwingC2SPacket(Hand.MAIN_HAND));

        if (weapon != current) this.sync(current);
    }

    /**
     * Finds the hotbar slot containing the saved attack weapon.
     *
     * @param fallback slot to use when the saved weapon is unavailable
     * @return hotbar slot used for the delayed attack
     */
    private int weapon(int fallback) {
        if (!this.lock.get() ||
            this.stack == null || this.stack.isEmpty() ||
            ItemStack.areItemsAndComponentsEqual(
                this.mc.player.getMainHandStack(), this.stack
            )) {
            return fallback;
        }

        int slot = Hotbar.find(candidate ->
            ItemStack.areItemsAndComponentsEqual(candidate, this.stack)
        );

        return slot >= 0 ? slot : fallback;
    }

    /**
     * Stops sprinting locally and on the server.
     */
    private void stop() {
        if (!this.mc.player.isSprinting()) return;

        this.mc.player.setSprinting(false);
        this.send(new ClientCommandC2SPacket(
            this.mc.player, Mode.STOP_SPRINTING
        ));
    }

    /**
     * Synchronizes a hotbar slot with the server.
     *
     * @param slot hotbar slot to synchronize
     */
    private void sync(int slot) {
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

    //region Utilities and validation

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
        public void interact(Hand hand) {}

        /**
         * Ignores interaction at a specific entity position.
         *
         * @param hand interaction hand
         * @param pos interaction position
         */
        @Override
        public void interactAt(Hand hand, Vec3d pos) {}

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
