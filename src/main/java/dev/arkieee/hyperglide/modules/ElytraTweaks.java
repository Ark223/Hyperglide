package dev.arkieee.hyperglide.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class ElytraTweaks extends Module {
    private static final double epsilon = 1.0E-6;
    private static final double rise = 0.30;

    private static final int chest = 6;
    private static final int xaxis = 1;
    private static final int zaxis = 2;

    private final SettingGroup equipment = this.settings.createGroup("Equipment");
    private final SettingGroup recovery = this.settings.createGroup("Recovery");
    private final SettingGroup safety = this.settings.createGroup("Safety");
    private final SettingGroup takeoff = this.settings.createGroup("Takeoff");

    private final Setting<Boolean> swap = this.equipment.add(new BoolSetting.Builder()
        .name("auto-swap")
        .description("Equips an elytra on double jump and a chestplate after landing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> window = this.equipment.add(new IntSetting.Builder()
        .name("jump-window")
        .description("Maximum ticks allowed between double-jump presses.")
        .defaultValue(5)
        .min(3)
        .sliderMax(10)
        .visible(this.swap::get)
        .build()
    );

    private final Setting<Boolean> replace = this.equipment.add(new BoolSetting.Builder()
        .name("swap-broken")
        .description("Replaces a worn elytra with a healthier one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimum = this.equipment.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Remaining durability before replacing the elytra.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(this.replace::get)
        .build()
    );

    private final Setting<Boolean> redeploy = this.recovery.add(new BoolSetting.Builder()
        .name("redeploy")
        .description("Restarts Baritone elytra flight when gliding stops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> escape = this.recovery.add(new BoolSetting.Builder()
        .name("liquid-escape")
        .description("Moves upward when Baritone cannot redeploy inside liquid.")
        .defaultValue(true)
        .visible(this.redeploy::get)
        .build()
    );

    private final Setting<Integer> cooldown = this.recovery.add(new IntSetting.Builder()
        .name("retry-cooldown")
        .description("Ticks to wait before retrying Baritone elytra flight.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .visible(this.redeploy::get)
        .build()
    );

    private final Setting<Boolean> avoid = this.safety.add(new BoolSetting.Builder()
        .name("avoid-collisions")
        .description("Stops flight before damaging predicted collisions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> ticks = this.safety.add(new IntSetting.Builder()
        .name("collision-ticks")
        .description("How many movement ticks ahead to scan for collisions.")
        .defaultValue(2)
        .min(1)
        .sliderMax(5)
        .visible(this.avoid::get)
        .build()
    );

    private final Setting<Double> expand = this.safety.add(new DoubleSetting.Builder()
        .name("hitbox-expand")
        .description("Expands the hitbox used for collision prediction.")
        .defaultValue(0.15)
        .min(0.0)
        .sliderMax(0.2)
        .decimalPlaces(2)
        .visible(this.avoid::get)
        .build()
    );

    private final Setting<Integer> release = this.safety.add(new IntSetting.Builder()
        .name("release-delay")
        .description("Ticks to wait after rocket boost ends before releasing.")
        .defaultValue(8)
        .min(0)
        .sliderMax(10)
        .visible(this.avoid::get)
        .build()
    );

    private final Setting<Boolean> starter = this.takeoff.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Starts gliding after holding jump while airborne.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> timer = this.takeoff.add(new IntSetting.Builder()
        .name("takeoff-ticks")
        .description("Jump hold ticks required before starting flight.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .visible(this.starter::get)
        .build()
    );

    private int tap;
    private int jump;
    private int deploy;
    private int retry;
    private int hold;

    private int phase;
    private int slot;

    private boolean pressed;
    private boolean opened;
    private boolean escaping;
    private boolean halt;
    private boolean shift;
    private boolean fresh;

    private double speed;

    private FireworkRocketEntity rocket;

    public ElytraTweaks() {
        super(Hyperglide.CATEGORY, "elytra-tweaks",
            "Provides useful tweaks and automation for elytra flight."
        );
    }

    /**
     * Clears all runtime state.
     */
    @Override
    public void onActivate() {
        this.reset();
    }

    /**
     * Aborts active inventory swapping and clears runtime state.
     */
    @Override
    public void onDeactivate() {
        this.abort();
        this.reset();
    }

    //region Event handlers

    /**
     * Updates equipment swapping, takeoff and Baritone recovery.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!this.valid()) return;

        if (this.shift) {
            this.mc.options.sneakKey.setPressed(true);
        }

        if (this.phase > 0) {
            this.strict();
            return;
        }

        this.doublejump();
        this.ground();

        if (this.replacement()) return;

        if (this.bounce()) {
            this.jump = 0;
            this.deploy = 0;
        } else {
            this.takeoff();
            this.deploy();
        }

        this.recover();
    }

    /**
     * Stops dangerous flight movement until collision release is allowed.
     *
     * @param event player movement event
     */
    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        if (!this.valid() || event.type != MovementType.SELF ||
            !this.mc.player.isGliding() || !this.avoid.get() ||
            this.baritone() || this.bounce()) {

            this.halt = false;
            this.hold = 0;
            this.speed = 0.0;

            if (this.shift) this.sneak(false);
            return;
        }

        if (this.halt) {
            Vec3d safe = this.safe();

            if (safe.lengthSquared() > epsilon && !this.danger(safe)) {
                this.halt = false;
                this.hold = 0;
                this.speed = 0.0;

                ((IVec3d) event.movement).meteor$set(safe.x, safe.y, safe.z);
                this.mc.player.setVelocity(safe);

                if (this.fresh) this.sneak(false);
                return;
            }

            if (this.boosted()) {
                this.hold = 0;
                this.stop(event);
                return;
            }

            if (this.hold < this.release.get()) {
                this.hold++;
                this.stop(event);
                return;
            }

            this.halt = false;
            this.hold = 0;
            this.speed = 0.0;

            if (this.fresh) this.sneak(false);
            return;
        }

        if (!this.danger(event.movement)) return;

        this.speed = Math.max(event.movement.length(),
            this.mc.player.getVelocity().length()
        );

        this.halt = true;
        this.hold = 0;
        this.fresh = false;
        this.sneak(true);
        this.stop(event);
    }

    //endregion

    //region State management

    /**
     * Clears all runtime state.
     */
    private void reset() {
        if (this.shift) {
            this.mc.options.sneakKey.setPressed(false);
        }

        this.tap = 0;
        this.jump = 0;
        this.deploy = 0;
        this.retry = 0;
        this.hold = 0;

        this.phase = 0;
        this.slot = -1;

        this.pressed = false;
        this.opened = false;
        this.escaping = false;
        this.halt = false;
        this.shift = false;
        this.fresh = false;

        this.speed = 0.0;
        this.rocket = null;
    }

    //endregion

    //region Equipment control

    /**
     * Detects double jump and equips an elytra from the hotbar.
     */
    private void doublejump() {
        boolean current = this.mc.options.jumpKey.isPressed();

        if (current && !this.pressed) {
            if (this.tap > 0 && !this.mc.player.isOnGround()) {
                this.tap = 0;
                if (this.swap.get()) this.launch();
            } else {
                this.tap = this.window.get();
            }
        }

        this.pressed = current;
        if (this.tap > 0) this.tap--;
    }

    /**
     * Equips a hotbar elytra and prepares it for takeoff.
     */
    private void launch() {
        if (this.elytra()) {
            this.deploy = 2;
            return;
        }

        int slot = this.hotbar(true);
        if (slot < 0 || !this.wear(slot)) return;

        this.deploy = 2;
    }

    /**
     * Equips a Baritone elytra or restores a chestplate after landing.
     */
    private void ground() {
        if (!this.swap.get()) return;

        if (this.baritone()) {
            if (this.elytra()) return;

            int slot = this.hotbar(true);
            if (slot >= 0) this.wear(slot);
            return;
        }

        if (!this.mc.player.isOnGround() ||
            this.mc.options.jumpKey.isPressed() ||
            !this.elytra()) {
            return;
        }

        int slot = this.hotbar(false);
        if (slot >= 0) this.wear(slot);
    }

    /**
     * Equips a hotbar armor item using normal interaction.
     *
     * @param slot hotbar slot containing the armor item
     * @return true when the equip interaction was sent
     */
    private boolean wear(int slot) {
        if (this.mc.interactionManager == null ||
            this.mc.currentScreen != null) {
            return false;
        }

        PlayerInventory inventory = this.mc.player.getInventory();
        int selected = inventory.selectedSlot;

        if (selected != slot) {
            inventory.setSelectedSlot(slot);
            this.select(slot);
        }

        this.mc.interactionManager.interactItem(
            this.mc.player, Hand.MAIN_HAND
        );

        if (selected != slot) {
            inventory.setSelectedSlot(selected);
            this.select(selected);
        }

        return true;
    }

    /**
     * Finds an elytra or chestplate in the hotbar.
     *
     * @param elytra whether an elytra or chestplate is requested
     * @return matching hotbar slot, or -1 when unavailable
     */
    private int hotbar(boolean elytra) {
        PlayerInventory inventory = this.mc.player.getInventory();

        int best = -1;
        int durability = -1;

        for (int idx = 0; idx < 9; idx++) {
            ItemStack stack = inventory.getStack(idx);

            if (elytra) {
                if (!stack.isOf(Items.ELYTRA)) continue;

                int current = this.remaining(stack);
                if (current <= durability) continue;

                durability = current;
                best = idx;
                continue;
            }

            if (this.chestplate(stack)) return idx;
        }

        return best;
    }

    //endregion

    //region Durability replacement

    /**
     * Starts inventory replacement when the equipped elytra is worn.
     *
     * @return true when a strict replacement is active or has just started
     */
    private boolean replacement() {
        if (!this.replace.get() || !this.elytra()) return false;

        ItemStack stack = this.mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (this.remaining(stack) > this.minimum.get()) return false;

        int slot = this.spare();
        if (slot < 0) return false;

        if (this.mc.currentScreen != null &&
            !(this.mc.currentScreen instanceof InventoryScreen)) {
            return false;
        }

        this.slot = slot;
        this.opened = this.mc.currentScreen == null;
        this.phase = 1;

        if (this.opened) {
            this.mc.setScreen(new InventoryScreen(this.mc.player));
        }

        return true;
    }

    /**
     * Advances the strict elytra replacement one action at a time.
     */
    private void strict() {
        if (!(this.mc.currentScreen instanceof InventoryScreen)) {
            this.mc.setScreen(new InventoryScreen(this.mc.player));
            return;
        }

        switch (this.phase) {
            case 1 -> {
                this.click(this.screen(this.slot));
                this.phase = 2;
            }

            case 2 -> {
                this.click(chest);
                this.phase = 3;
            }

            case 3 -> {
                this.click(this.screen(this.slot));
                this.phase = 4;
            }

            case 4 -> this.finish();
        }
    }

    /**
     * Finds the healthiest replacement elytra in the inventory.
     *
     * @return player inventory slot, or -1 when no safe spare exists
     */
    private int spare() {
        PlayerInventory inventory = this.mc.player.getInventory();

        int best = -1;
        int durability = this.minimum.get();

        for (int idx = 0; idx < 36; idx++) {
            ItemStack stack = inventory.getStack(idx);
            if (!stack.isOf(Items.ELYTRA)) continue;

            int current = this.remaining(stack);
            if (current <= durability) continue;

            durability = current;
            best = idx;
        }

        return best;
    }

    /**
     * Converts a player inventory slot to its screen slot.
     *
     * @param slot player inventory slot
     * @return matching inventory screen slot
     */
    private int screen(int slot) {
        return slot < 9 ? slot + 36 : slot;
    }

    /**
     * Performs a normal pickup click in the player inventory.
     *
     * @param slot player screen slot to click
     */
    private void click(int slot) {
        if (this.mc.interactionManager == null) return;

        this.mc.interactionManager.clickSlot(
            this.mc.player.currentScreenHandler.syncId,
            slot, 0, SlotActionType.PICKUP, this.mc.player
        );
    }

    /**
     * Finishes the active elytra replacement and closes the inventory.
     */
    private void finish() {
        if (this.opened &&
            this.mc.currentScreen instanceof InventoryScreen) {
            this.mc.setScreen(null);
        }

        this.phase = 0;
        this.slot = -1;
        this.opened = false;
    }

    /**
     * Returns the held item and aborts the active replacement.
     */
    private void abort() {
        if (!this.valid() || this.phase <= 0) return;

        if (!(this.mc.currentScreen instanceof InventoryScreen)) {
            this.mc.setScreen(new InventoryScreen(this.mc.player));
        }

        if (this.phase == 2 || this.phase == 3) {
            this.click(this.screen(this.slot));
        }

        this.finish();
    }

    //endregion

    //region Takeoff control

    /**
     * Starts elytra flight after jump is held for the configured duration.
     */
    private void takeoff() {
        if (!this.starter.get() || this.mc.player.isGliding()) {
            this.jump = 0;
            return;
        }

        if (!this.mc.options.jumpKey.isPressed()) {
            this.jump = 0;
            return;
        }

        this.jump++;
        if (this.jump < this.timer.get()) return;

        this.jump = 0;

        if (!this.elytra()) {
            int slot = this.hotbar(true);
            if (slot < 0 || !this.wear(slot)) return;

            this.deploy = 2;
            return;
        }

        this.start();
    }

    /**
     * Starts flight after a newly equipped elytra is synchronized.
     */
    private void deploy() {
        if (this.deploy <= 0) return;

        if (this.elytra() && this.start()) {
            this.deploy = 0;
            return;
        }

        this.deploy--;
    }

    /**
     * Starts elytra flight when vanilla allows deployment.
     *
     * @return true when flight was started
     */
    private boolean start() {
        if (!this.elytra() ||
            this.mc.player.isGliding() ||
            !this.mc.player.checkGliding()) {
            return false;
        }

        this.mc.getNetworkHandler().sendPacket(
            new ClientCommandC2SPacket(this.mc.player,
                ClientCommandC2SPacket.Mode.START_FALL_FLYING
            )
        );

        return true;
    }

    //endregion

    //region Baritone recovery

    /**
     * Restarts interrupted flight and escapes liquid when required.
     */
    private void recover() {
        if (!this.redeploy.get() || !this.baritone() || !this.elytra()) {
            this.retry = 0;
            this.escaping = false;
            return;
        }

        if (this.mc.player.isGliding()) {
            this.retry = 0;
            this.escaping = false;
            return;
        }

        if (this.liquid()) {
            this.retry = 0;
            if (!this.escape.get()) {
                this.escaping = false;
                return;
            }

            this.escaping = true;
            Vec3d velocity = this.mc.player.getVelocity();

            this.mc.player.setVelocity(
                velocity.x, Math.max(velocity.y, rise), velocity.z
            );

            return;
        }

        if (this.mc.player.isOnGround()) {
            this.retry = 0;
            this.escaping = false;
            this.mc.player.jump();
            return;
        }

        if (this.escaping) {
            this.retry = 0;
            this.escaping = false;
            this.restart();
            return;
        }

        if (++this.retry < this.cooldown.get()) return;

        this.retry = 0;
        this.restart();
    }

    /**
     * Retries Baritone elytra deployment when vanilla allows it.
     */
    private void restart() {
        this.start();
    }

    /**
     * Checks whether Baritone elytra flight is active.
     *
     * @return true while Baritone elytra flight is active
     */
    private boolean baritone() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        return baritone.getElytraProcess().isActive();
    }

    //endregion

    //region Boost tracking

    /**
     * Tracks the firework currently boosting the player.
     *
     * @param rocket player-owned firework rocket
     */
    public void track(FireworkRocketEntity rocket) {
        if (!this.isActive()) return;

        if (this.shift && this.rocket != rocket) {
            this.fresh = true;
            if (!this.halt) this.sneak(false);
        }

        this.rocket = rocket;
    }

    /**
     * Checks whether the tracked firework boost is still active.
     *
     * @return true while the tracked rocket is alive
     */
    private boolean boosted() {
        return this.rocket != null && this.rocket.isAlive();
    }

    //endregion

    //region Collision detection

    /**
     * Predicts whether a horizontal collision would cause gliding damage.
     *
     * @param motion current movement vector
     * @return true when predicted horizontal speed loss causes damage
     */
    private boolean danger(Vec3d motion) {
        if (motion.lengthSquared() < epsilon) return false;

        int axis = this.collision(motion);
        return axis != 0 && this.damage(motion, axis) > 0.0;
    }

    /**
     * Checks five projected hitbox rays for horizontal collisions.
     *
     * @param motion movement vector to project
     * @return combined X and Z collision axes
     */
    private int collision(Vec3d motion) {
        Vec3d flat = new Vec3d(motion.x, 0.0, motion.z);
        if (flat.lengthSquared() < epsilon) return 0;

        Vec3d offset = flat.multiply(this.ticks.get());
        Vec3d front = flat.normalize();
        Vec3d side = new Vec3d(-front.z, 0.0, front.x);

        Box box = this.mc.player.getBoundingBox();

        Double expand = this.expand.get();
        box = box.expand(expand, 0.0, expand);

        double x = (box.minX + box.maxX) / 2.0;
        double z = (box.minZ + box.maxZ) / 2.0;

        double halfx = box.getLengthX() / 2.0;
        double halfz = box.getLengthZ() / 2.0;

        double forward = Math.abs(front.x) *
            halfx + Math.abs(front.z) * halfz;

        double width = Math.abs(side.x) *
            halfx + Math.abs(side.z) * halfz;

        double basex = x + front.x * forward;
        double basez = z + front.z * forward;

        double low = box.minY + 0.05;
        double high = box.maxY - 0.05;

        int axis = 0;

        Vec3d center = box.getCenter();
        axis |= this.hit(center, center.add(offset));

        for (int idx = -1; idx <= 1; idx += 2) {
            Vec3d edge = side.multiply(width * idx);

            Vec3d bottom = new Vec3d(
                basex + edge.x, low, basez + edge.z
            );

            Vec3d top = new Vec3d(
                basex + edge.x, high, basez + edge.z
            );

            axis |= this.hit(bottom, bottom.add(offset));
            axis |= this.hit(top, top.add(offset));

            if (axis == (xaxis | zaxis)) return axis;
        }

        return axis;
    }

    /**
     * Returns the horizontal collision axis reached by one ray.
     *
     * @param start ray origin
     * @param end ray destination
     * @return X or Z collision axis, or zero
     */
    private int hit(Vec3d start, Vec3d end) {
        BlockHitResult hit = this.mc.world.raycast(
            new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                this.mc.player
            )
        );

        if (hit.getType() != HitResult.Type.BLOCK) return 0;

        Direction.Axis axis = hit.getSide().getAxis();

        if (axis == Direction.Axis.X) return xaxis;
        if (axis == Direction.Axis.Z) return zaxis;

        return 0;
    }

    /**
     * Calculates raw gliding collision damage from speed loss.
     *
     * @param motion current movement vector
     * @param axis predicted collision axes
     * @return predicted raw collision damage
     */
    private double damage(Vec3d motion, int axis) {
        double old = Math.hypot(motion.x, motion.z);

        double x = (axis & xaxis) != 0 ? 0.0 : motion.x;
        double z = (axis & zaxis) != 0 ? 0.0 : motion.z;

        double speed = Math.hypot(x, z);
        return Math.max(0.0, (old - speed) * 10.0 - 3.0);
    }

    /**
     * Calculates full-speed movement in the current look direction.
     *
     * @return candidate movement used to leave collision stop state
     */
    private Vec3d safe() {
        if (this.speed <= epsilon) return Vec3d.ZERO;

        return this.mc.player.getRotationVec(1.0F)
            .normalize().multiply(this.speed);
    }

    /**
     * Stops movement while collision avoidance is latched.
     *
     * @param event player movement event to stop
     */
    private void stop(PlayerMoveEvent event) {
        this.sneak(true);

        ((IVec3d) event.movement).meteor$set(0.0, 0.0, 0.0);
        this.mc.player.setVelocity(Vec3d.ZERO);
    }

    /**
     * Holds or releases sneak for collision recovery.
     *
     * @param pressed whether sneak should remain held
     */
    private void sneak(boolean pressed) {
        this.shift = pressed;

        if (!pressed) this.fresh = false;
        this.mc.options.sneakKey.setPressed(pressed);
    }

    //endregion

    //region Validation and utilities

    /**
     * Checks whether Bounce Fly is currently active.
     *
     * @return true while Bounce Fly is active
     */
    private boolean bounce() {
        BounceFly module = Modules.get().get(BounceFly.class);
        return module != null && module.isActive();
    }

    /**
     * Checks whether the player has an elytra equipped.
     *
     * @return true when an elytra is equipped in the chest slot
     */
    private boolean elytra() {
        return this.mc.player.getEquippedStack(
            EquipmentSlot.CHEST
        ).isOf(Items.ELYTRA);
    }

    /**
     * Checks whether an item can be equipped in the chest slot.
     *
     * @param stack item stack to inspect
     * @return true when the item is a chestplate
     */
    private boolean chestplate(ItemStack stack) {
        if (stack.isEmpty() || stack.isOf(Items.ELYTRA)) {
            return false;
        }

        EquippableComponent equipment =
            stack.get(DataComponentTypes.EQUIPPABLE);

        return equipment != null &&
            equipment.slot() == EquipmentSlot.CHEST;
    }

    /**
     * Calculates the remaining durability of an item.
     *
     * @param stack damageable item stack
     * @return remaining durability, or maximum integer
     */
    private int remaining(ItemStack stack) {
        if (!stack.isDamageable()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamage();
    }

    /**
     * Checks whether the player is touching water or lava.
     *
     * @return true while inside a liquid that can interrupt flight
     */
    private boolean liquid() {
        return this.mc.player.isTouchingWater()
            || this.mc.player.isInLava();
    }

    /**
     * Synchronizes a selected hotbar slot with the server.
     *
     * @param slot hotbar slot selected on the server
     */
    private void select(int slot) {
        this.mc.getNetworkHandler().sendPacket(
            new UpdateSelectedSlotC2SPacket(slot)
        );
    }

    /**
     * Checks whether the required client and world state is available.
     *
     * @return true when all required client state is available
     */
    private boolean valid() {
        return this.mc.player != null &&
            this.mc.world != null &&
            this.mc.getNetworkHandler() != null;
    }

    //endregion
}
