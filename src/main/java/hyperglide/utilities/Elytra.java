package hyperglide.utilities;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Handles common elytra checks, takeoff and flight calculations.
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
        return client.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
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
     * Starts elytra flight.
     */
    public static void start() {
        client.getNetworkHandler().sendPacket(
            new ClientCommandC2SPacket(client.player,
                ClientCommandC2SPacket.Mode.START_FALL_FLYING
            )
        );
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
