package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Provides common elytra checks, takeoff and flight calculations.
 */
public final class Elytra {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private Elytra() {}

    /**
     * Checks whether the player has an elytra equipped.
     *
     * @return true when an elytra is equipped in the chest slot
     */
    public static boolean equipped() {
        return client.player.getEquippedStack(
            EquipmentSlot.CHEST
        ).isOf(Items.ELYTRA);
    }

    /**
     * Checks whether the player is wearing a chestplate.
     *
     * @return true when a chestplate is equipped
     */
    public static boolean chestplate() {
        return chestplate(client.player.getEquippedStack(
            EquipmentSlot.CHEST
        ));
    }

    /**
     * Checks whether a stack can be worn as a chestplate.
     *
     * @param stack item stack to inspect
     * @return true when the stack uses the chest slot
     */
    public static boolean chestplate(ItemStack stack) {
        if (stack.isEmpty() || stack.isOf(Items.ELYTRA)) {
            return false;
        }

        EquippableComponent equipment = stack.get(
            DataComponentTypes.EQUIPPABLE
        );

        if (equipment == null) return false;
        return equipment.slot() == EquipmentSlot.CHEST;
    }

    /**
     * Finds the healthiest elytra in the hotbar.
     *
     * @return matching hotbar slot, or -1 when unavailable
     */
    public static int hotbar() {
        return Hotbar.best(
            stack -> stack.isOf(Items.ELYTRA),
            Elytra::remaining
        );
    }

    /**
     * Returns the remaining durability of an item stack.
     *
     * @param stack item stack to check
     * @return remaining durability
     */
    public static int remaining(ItemStack stack) {
        if (!stack.isDamageable()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamage();
    }

    /**
     * Sends a direct request to start elytra flight.
     */
    public static void start() {
        Packets.command(ClientCommandC2SPacket.Mode.START_FALL_FLYING);
    }

    /**
     * Uses a firework with the current player rotation.
     *
     * @return true when the firework packet was sent
     */
    public static boolean firework() {
        if (client.player == null) return false;

        float yaw = client.player.getYaw();
        float pitch = client.player.getPitch();

        return firework(yaw, pitch);
    }

    /**
     * Uses a firework without changing the selected hotbar slot.
     *
     * @param yaw interaction yaw
     * @param pitch interaction pitch
     * @return true when the firework packet was sent
     */
    public static boolean firework(float yaw, float pitch) {
        if (!Client.interaction() || !Inventory.ready()) {
            return false;
        }

        if (client.player.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) {
            Packets.item(Hand.MAIN_HAND, yaw, pitch);
            return true;
        }

        if (client.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            Packets.item(Hand.OFF_HAND, yaw, pitch);
            return true;
        }

        int slot = Hotbar.find(Items.FIREWORK_ROCKET);
        if (slot < 0) return false;

        Inventory.swap(PlayerScreenHandler.OFFHAND_ID, slot);

        try {
            if (!client.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
                return false;
            }

            Packets.item(Hand.OFF_HAND, yaw, pitch);
            return true;
        } finally {
            Inventory.swap(PlayerScreenHandler.OFFHAND_ID, slot);
        }
    }

    /**
     * Calculates elytra movement for the next tick.
     *
     * @param velocity current velocity
     * @param rotation current flight direction
     * @return calculated gliding velocity
     */
    public static Vec3d glide(Vec3d velocity, Vec3d rotation) {
        return glide(velocity, rotation, 0.08);
    }

    /**
     * Calculates elytra movement using the supplied gravity.
     *
     * @param velocity current velocity
     * @param rotation current flight direction
     * @param gravity current player gravity
     * @return calculated gliding velocity
     */
    public static Vec3d glide(Vec3d velocity, Vec3d rotation, double gravity) {
        double length = rotation.horizontalLength();
        double speed = velocity.horizontalLength();

        double cosine = length * length * Math.min(1.0, rotation.length() / 0.4);
        velocity = velocity.add(0.0, gravity * (cosine * 0.75 - 1.0), 0.0);

        return glide(velocity, rotation, length, speed, cosine);
    }

    /**
     * Applies the remaining elytra movement for the current tick.
     *
     * @param velocity current velocity
     * @param rotation current flight direction
     * @param length horizontal rotation length
     * @param speed horizontal movement speed
     * @param cosine pitch-derived lift factor
     * @return simulated gliding velocity
     */
    private static Vec3d glide(Vec3d velocity, Vec3d rotation,
        double length, double speed, double cosine) {

        velocity = fall(velocity, rotation, length, cosine);
        velocity = rise(velocity, rotation, length, speed);
        velocity = align(velocity, rotation, length, speed);

        return velocity.multiply(0.99, 0.98, 0.99);
    }

    /**
     * Applies movement while descending.
     *
     * @param velocity current velocity
     * @param rotation current flight direction
     * @param length horizontal rotation length
     * @param cosine pitch-derived lift factor
     * @return adjusted velocity
     */
    private static Vec3d fall(Vec3d velocity,
        Vec3d rotation, double length, double cosine) {

        if (velocity.y < 0.0 && length > 0.0) {
            double lift = velocity.y * -0.1 * cosine;

            velocity = velocity.add(
                rotation.x * lift / length,
                lift,
                rotation.z * lift / length
            );
        }

        return velocity;
    }

    /**
     * Applies movement while climbing.
     *
     * @param velocity current velocity
     * @param rotation current flight direction
     * @param length horizontal rotation length
     * @param speed horizontal movement speed
     * @return adjusted velocity
     */
    private static Vec3d rise(Vec3d velocity,
        Vec3d rotation, double length, double speed) {

        double angle = MathHelper.clamp(-rotation.y, -1.0, 1.0);
        angle = Math.asin(angle);

        if (angle < 0.0 && length > 0.0) {
            double lift = speed * -Math.sin(angle) * 0.04;

            velocity = velocity.add(
                -rotation.x * lift / length,
                lift * 3.2,
                -rotation.z * lift / length
            );
        }

        return velocity;
    }

    /**
     * Steers horizontal velocity toward the flight direction.
     *
     * @param velocity current velocity
     * @param rotation current flight direction
     * @param length horizontal rotation length
     * @param speed horizontal movement speed
     * @return adjusted velocity
     */
    private static Vec3d align(Vec3d velocity,
        Vec3d rotation, double length, double speed) {

        if (length > 0.0) {
            velocity = velocity.add(
                (rotation.x / length * speed - velocity.x) * 0.1,
                0.0,
                (rotation.z / length * speed - velocity.z) * 0.1
            );
        }

        return velocity;
    }
}
