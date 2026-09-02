package hyperglide.utilities;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;

/**
 * Handles Baritone pathing, elytra flight and movement inputs.
 */
public final class Baritone {
    private Baritone() {}

    /**
     * Checks whether Baritone is controlling elytra flight.
     *
     * @return true while Baritone controls elytra flight
     */
    public static boolean elytra() {
        return instance().getElytraProcess().isActive();
    }

    /**
     * Checks whether Baritone is currently pathing.
     *
     * @return true while a walking goal or path is active
     */
    public static boolean moving() {
        IBaritone baritone = instance();
        return baritone.getCustomGoalProcess().isActive()
            || baritone.getPathingBehavior().isPathing();
    }

    /**
     * Checks whether Baritone elytra flight is ready.
     *
     * @return true when elytra pathing can be started
     */
    public static boolean loaded() {
        return instance().getElytraProcess().isLoaded();
    }

    /**
     * Returns the current Baritone elytra destination.
     *
     * @return current destination, or null when unavailable
     */
    public static BlockPos destination() {
        return instance().getElytraProcess().currentDestination();
    }

    /**
     * Changes the Baritone elytra flight settings.
     *
     * @param speed firework speed
     * @param avoid minimum avoidance
     * @param predict whether terrain prediction is enabled
     */
    public static void settings(double speed, double avoid, boolean predict) {
        Settings config = BaritoneAPI.getSettings();

        config.allowPlace.value = true;
        config.allowInventory.value = true;
        config.elytraTermsAccepted.value = true;

        config.elytraFireworkSpeed.value = speed;
        config.elytraMinimumAvoidance.value = avoid;
        config.elytraPredictTerrain.value = predict;
    }

    /**
     * Starts elytra pathing to a destination.
     *
     * @param pos destination position
     * @param exact whether Y is part of the goal
     */
    public static void fly(BlockPos pos, boolean exact) {
        instance().getElytraProcess().pathTo(goal(pos, exact));
    }

    /**
     * Starts walking to an exact block.
     *
     * @param pos destination position
     */
    public static void walk(BlockPos pos) {
        walk(pos, true);
    }

    /**
     * Starts walking to a destination.
     *
     * @param pos destination position
     * @param exact whether Y is part of the goal
     */
    public static void walk(BlockPos pos, boolean exact) {
        instance().getCustomGoalProcess().setGoalAndPath(goal(pos, exact));
    }

    /**
     * Cancels the current walking path.
     */
    public static void cancel() {
        instance().getPathingBehavior().cancelEverything();
    }

    /**
     * Stops Baritone flight and walking immediately.
     */
    public static void stop() {
        IBaritone baritone = instance();

        baritone.getElytraProcess().onLostControl();
        baritone.getCustomGoalProcess().onLostControl();
        baritone.getPathingBehavior().forceCancel();
    }

    /**
     * Releases movement inputs forced by Baritone.
     */
    public static void clear() {
        instance().getInputOverrideHandler().clearAllKeys();
    }

    /**
     * Changes forced forward movement.
     *
     * @param state whether forward movement is pressed
     */
    public static void forward(boolean state) {
        input(Input.MOVE_FORWARD, state);
    }

    /**
     * Changes forced backward movement.
     *
     * @param state whether backward movement is pressed
     */
    public static void back(boolean state) {
        input(Input.MOVE_BACK, state);
    }

    /**
     * Changes forced left movement.
     *
     * @param state whether left movement is pressed
     */
    public static void left(boolean state) {
        input(Input.MOVE_LEFT, state);
    }

    /**
     * Changes forced right movement.
     *
     * @param state whether right movement is pressed
     */
    public static void right(boolean state) {
        input(Input.MOVE_RIGHT, state);
    }

    /**
     * Changes forced sprinting.
     *
     * @param state whether sprint is pressed
     */
    public static void sprint(boolean state) {
        input(Input.SPRINT, state);
    }

    /**
     * Creates a Baritone goal for a destination.
     *
     * @param pos destination position
     * @param exact whether Y is part of the goal
     * @return matching Baritone goal
     */
    private static Goal goal(BlockPos pos, boolean exact) {
        return exact ? new GoalBlock(pos) : new GoalXZ(pos.getX(), pos.getZ());
    }

    /**
     * Changes a forced Baritone input.
     *
     * @param input input to change
     * @param state whether the input is pressed
     */
    private static void input(Input input, boolean state) {
        instance().getInputOverrideHandler().setInputForceState(input, state);
    }

    /**
     * Returns the primary Baritone instance.
     *
     * @return Baritone instance
     */
    private static IBaritone instance() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }
}
