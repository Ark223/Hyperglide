package hyperglide.utilities;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import java.util.List;

/**
 * Handles Baritone pathing, elytra flight and movement inputs.
 */
public final class Baritone {
    private Baritone() {}

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

    //region Main interface

    /**
     * Checks whether Baritone elytra flight is ready.
     *
     * @return true when elytra pathing can be started
     */
    public static boolean loaded() {
        return instance().getElytraProcess().isLoaded();
    }

    /**
     * Checks whether Baritone is controlling elytra flight.
     *
     * @return true while Baritone controls elytra flight
     */
    public static boolean elytra() {
        return destination() != null;
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
     * Checks whether Baritone is currently pathing.
     *
     * @return true while a walking goal or path is active
     */
    public static boolean pathing() {
        IBaritone baritone = instance();
        return baritone.getCustomGoalProcess().isActive()
            || baritone.getPathingBehavior().isPathing();
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
     * Starts walking to a destination within a given radius.
     *
     * @param pos destination position
     * @param radius goal radius
     */
    public static void near(BlockPos pos, int radius) {
        GoalNear goal = new GoalNear(pos, radius);
        instance().getCustomGoalProcess().setGoalAndPath(goal);
    }

    /**
     * Cancels the current pathing process.
     */
    public static void cancel() {
        instance().getPathingBehavior().cancelEverything();
    }

    /**
     * Stops all active processes immediately.
     */
    public static void stop() {
        IBaritone baritone = instance();

        baritone.getElytraProcess().onLostControl();
        baritone.getCustomGoalProcess().onLostControl();
        baritone.getBuilderProcess().onLostControl();
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

    //endregion

    //region Baritone assistance

    /**
     * Helps Baritone step onto blocks it needs to place.
     */
    public static void assist() {
        IBaritone baritone = instance();

        IMovement movement = movement(baritone);
        if (movement == null || !rising(movement)) {
            return;
        }

        IPlayerContext context = baritone.getPlayerContext();
        if (context.player() == null || context.world() == null) {
            return;
        }

        BlockPos pos = movement.getDest().down();
        if (!context.world().getBlockState(pos).isReplaceable()) {
            return;
        }

        BlockPos source = movement.getSrc();
        if (!aligned(context, source)) {
            move(baritone, source);
        }
    }

    /**
     * Returns the movement Baritone is currently following.
     *
     * @param baritone Baritone instance
     * @return current movement, or null when unavailable
     */
    private static IMovement movement(IBaritone baritone) {
        IPathingBehavior behaviour = baritone.getPathingBehavior();

        IPathExecutor current = behaviour.getCurrent();
        if (current == null) return null;

        IPath path = current.getPath();
        int index = current.getPosition();

        List<IMovement> movements = path.movements();
        if (index >= 0 && index < movements.size()) {
            return movements.get(index);
        } else {
            return null;
        }
    }

    /**
     * Moves the player toward the current movement source.
     *
     * @param baritone Baritone instance
     * @param pos target block position
     */
    private static void move(IBaritone baritone, BlockPos pos) {
        IPlayerContext context = baritone.getPlayerContext();
        var handler = baritone.getInputOverrideHandler();

        double dx = pos.getX() + 0.5 - context.player().getX();
        double dz = pos.getZ() + 0.5 - context.player().getZ();
        double yaw = Math.toRadians(context.player().getYaw());

        double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double sideway = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;

        handler.setInputForceState(Input.MOVE_FORWARD, false);
        handler.setInputForceState(Input.MOVE_BACK, false);
        handler.setInputForceState(Input.MOVE_LEFT, false);
        handler.setInputForceState(Input.MOVE_RIGHT, false);

        Input input = sideway * sideway >= forward * forward ?
            sideway >= 0.0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT :
            forward >= 0.0 ? Input.MOVE_FORWARD : Input.MOVE_BACK;

        handler.setInputForceState(input, true);
    }

    /**
     * Checks whether the player is fully positioned on a block.
     *
     * @param context Baritone player context
     * @param pos source block position
     * @return true when the player is above the block
     */
    private static boolean aligned(IPlayerContext context, BlockPos pos) {
        if (context.player().getBlockY() != pos.getY()) return false;

        Box box = context.player().getBoundingBox();
        return box.minX >= pos.getX() && box.maxX <= pos.getX() + 1.0
            && box.minZ >= pos.getZ() && box.maxZ <= pos.getZ() + 1.0;
    }

    /**
     * Checks whether a movement climbs by one block.
     *
     * @param movement current movement
     * @return true when the destination is higher
     */
    private static boolean rising(IMovement movement) {
        int target = movement.getDest().getY();
        int source = movement.getSrc().getY();
        return target == source + 1;
    }

    //endregion
}
