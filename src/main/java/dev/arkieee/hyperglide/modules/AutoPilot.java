package dev.arkieee.hyperglide.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.navigation.Route;
import dev.arkieee.hyperglide.navigation.Segment;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec2f;
import net.minecraft.world.World;

public class AutoPilot extends Module {
    private static final double approach = 128.0;
    private static final double proximity = 3.0;

    private static final int halt = 20;
    private static final int lava = 3;
    private static final int level = 120;

    private static final int width = 12;
    private static final int height = 8;
    private static final int depth = 12;

    private static final int floor = 32;
    private static final int search = 32;
    private static final int retry = 10;

    private State state = State.Idle;
    private Route route;

    private BlockPos target;
    private BlockPos goal;
    private BlockPos point;
    private BlockPos block;

    private int leg = -1;
    private int timer;

    private boolean seen;
    private boolean join;
    private boolean ready;
    private boolean mining;

    /**
     * Defines the current travel stage.
     */
    private enum State {
        Idle,
        Flight,
        Land,
        Entry,
        Highway,
        Exit,
        Final,
        Done
    }

    public AutoPilot() {
        super(Hyperglide.CATEGORY, "auto-pilot",
            "Automatically travels toward the navigation goal."
        );
    }

    /**
     * Captures the current route and prepares required modules.
     */
    @Override
    public void onActivate() {
        if (this.netherrack() < 0) {
            this.error("No netherrack in hotbar.");
            this.toggle();
            return;
        }

        BaritoneAPI.getSettings().elytraFireworkSpeed.value = 1.43;
        BaritoneAPI.getSettings().elytraPredictTerrain.value = false;

        this.reset();
        this.enable();
        this.load();
    }

    /**
     * Stops active movement and clears runtime state.
     */
    @Override
    public void onDeactivate() {
        this.bounce(false);
        this.abort();

        if (this.mc.interactionManager != null) {
            this.mc.interactionManager.cancelBlockBreaking();
        }

        this.reset();
    }

    //region Event handlers

    /**
     * Advances travel along the saved route.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!this.valid()) return;

        this.enable();

        if (this.route == null || this.target == null) {
            this.load();
        }

        if (this.route == null || this.target == null) {
            return;
        }

        switch (this.state) {
            case Idle -> this.idle();
            case Flight -> this.flight();
            case Land -> this.land();
            case Entry -> this.entry();
            case Highway -> this.highway();
            case Exit -> this.exit();
            case Final -> this.finish();
            case Done -> this.toggle();
        }
    }

    //endregion

    //region State management

    /**
     * Clears the saved route and runtime state.
     */
    private void reset() {
        this.route = null;
        this.state = State.Idle;

        this.target = null;
        this.goal = null;
        this.point = null;
        this.block = null;

        this.leg = -1;
        this.timer = 0;

        this.seen = false;
        this.join = false;
        this.ready = false;
        this.mining = false;
    }

    /**
     * Keeps the required travel modules enabled.
     */
    private void enable() {
        Navigation navigation = this.navigation();
        if (navigation != null && !navigation.isActive()) {
            navigation.toggle();
        }

        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        if (tweaks != null && !tweaks.isActive()) tweaks.toggle();
    }

    /**
     * Captures the current route and destination from Navigation.
     */
    private void load() {
        Navigation navigation = this.navigation();
        if (navigation == null) return;

        navigation.refresh();

        Route route = navigation.route();
        if (route == null) return;

        this.route = route;
        this.target = navigation.point().toImmutable();
    }

    /**
     * Updates the route from the current position.
     *
     * @return true when route progress is available
     */
    private boolean refresh() {
        Navigation navigation = this.navigation();
        if (navigation == null) return false;

        navigation.refresh();

        Route route = navigation.route();
        if (route == null) return false;

        this.route = route;
        this.leg = -1;

        int progress = this.progress();
        if (progress < 0) return false;

        this.leg = progress;
        return true;
    }

    /**
     * Selects the closest route leg and resumes travel.
     */
    private void idle() {
        if (this.mc.player.isOnGround() &&
            this.close(this.target, approach)) {
            this.finish();
            return;
        }

        int progress = this.progress();
        if (progress < 0) return;

        this.leg = progress;
        this.flight();
    }

    //endregion

    //region Route management

    /**
     * Finds the closest remaining route leg.
     *
     * @return route leg index, or -1 when unavailable
     */
    private int progress() {
        int size = this.route.legs().size();
        if (size == 0) return -1;

        int start = Math.max(0, this.leg);
        if (start >= size) return -1;

        Vec2f position = this.position();
        float distance = Float.MAX_VALUE;
        int best = -1;

        for (int index = start; index < size; index++) {
            Route.Leg leg = this.route.legs().get(index);
            Vec2f point = this.project(leg, position);

            float current = position.distanceSquared(point);
            if (current >= distance) continue;

            distance = current;
            best = index;
        }

        return best;
    }

    /**
     * Projects a point onto the nearest position of a route leg.
     *
     * @param leg route leg
     * @param point point to project
     * @return closest point on the leg
     */
    private Vec2f project(Route.Leg leg, Vec2f point) {
        Segment segment = new Segment(leg.start(), leg.end());
        return segment.point(segment.projection(point));
    }

    /**
     * Finds a nearby point for entering a highway.
     *
     * @param leg highway route leg
     * @return closest point shifted forward along the leg
     */
    private Vec2f entry(Route.Leg leg) {
        Vec2f position = this.position();
        Segment segment = new Segment(leg.start(), leg.end());

        Vec2f point = segment.point(segment.projection(position));
        return point.add(segment.unit().multiply(3.0F));
    }

    /**
     * Converts a route point to a block position at highway level.
     *
     * @param point route point
     * @return block position at highway level
     */
    private BlockPos waypoint(Vec2f point) {
        return new BlockPos(
            Math.round(point.x), level,
            Math.round(point.y)
        );
    }

    /**
     * Checks whether the player is aligned with a highway leg.
     *
     * @param leg highway route leg
     * @return true when at highway level and within proximity
     */
    private boolean aligned(Route.Leg leg) {
        if (this.mc.player.getY() < level - 1.0) {
            return false;
        }

        Vec2f position = this.position();
        Vec2f point = this.project(leg, position);

        float distance = position.distanceSquared(point);
        return distance <= proximity * proximity;
    }

    //endregion

    //region Standard travel

    /**
     * Follows standard route legs with Baritone Elytra.
     */
    private void flight() {
        if (this.goal != null) {
            if (this.join && this.landing()) {
                if (++this.timer <= 5) return;

                this.cancel();
                this.timer = 0;
                this.block = null;

                this.mc.player.setPitch(-90.0F);
                this.state = State.Land;
                return;
            }

            if (this.join) this.timer = 0;

            if (this.waiting() || !this.mc.player.isOnGround()) {
                return;
            }

            this.goal = null;
            this.seen = false;
            this.join = false;

            if (this.close(this.target, approach)) {
                this.finish();
                return;
            }

            int progress = this.progress();
            if (progress < 0) return;

            this.leg = progress;
        }

        if (this.leg < 0 || this.leg >= this.route.legs().size()) {
            return;
        }

        Route.Leg leg = this.route.legs().get(this.leg);
        boolean highway = leg.type() == Route.Type.Highway;
        if (highway && this.aligned(leg)) {
            this.start(this.leg);
            return;
        }

        int next = this.leg + 1;
        boolean joining = highway || next < this.route.legs().size() &&
            this.route.legs().get(next).type() == Route.Type.Highway;

        Vec2f point = highway ? this.entry(leg) : leg.end();
        if (joining && this.mc.player.isOnGround() &&
            this.close(point, approach)) {
            this.state = State.Entry;
            return;
        }

        if (this.fly(this.waypoint(point), joining)) {
            this.state = State.Flight;
        }
    }

    /**
     * Finishes the route with normal Baritone pathing.
     */
    private void finish() {
        if (this.state != State.Final) {
            if (!this.mc.player.isOnGround() || this.done()) {
                return;
            }

            this.bounce(false);
            this.walk(this.target, false);
            this.state = State.Final;
            return;
        }

        if (this.goal != null && this.waiting()) {
            return;
        }

        this.goal = null;
        this.seen = false;

        if (this.done()) return;

        this.walk(this.target, false);
    }

    /**
     * Completes travel when the player is close enough to the target.
     *
     * @return true when the route is complete
     */
    private boolean done() {
        if (!this.close(this.target, 2.0)) {
            return false;
        }

        this.release();
        this.state = State.Done;
        return true;
    }

    //endregion

    //region Highway entry

    /**
     * Finishes the normal flight and creates a landing block.
     */
    private void land() {
        this.mc.player.setPitch(-90.0F);

        if (this.mc.player.getVelocity().y > 0.0) return;

        if (this.block == null) {
            this.block = this.mc.player.getBlockPos().down(2);
        }

        if (this.mc.world.getBlockState(this.block).isReplaceable() &&
            !this.place(this.block)) {
            return;
        }

        if (!this.mc.player.isOnGround()) return;

        this.mc.player.stopGliding();

        this.block = null;
        this.state = State.Entry;
    }

    /**
     * Moves onto the next highway leg before bouncing.
     */
    private void entry() {
        if (this.goal != null) {
            if (this.waiting()) return;

            this.goal = null;
            this.seen = false;
        }

        if (!this.refresh()) return;

        int next = this.leg;
        Route.Leg road = this.route.legs().get(next);

        if (road.type() != Route.Type.Highway) {
            if (++next >= this.route.legs().size()) {
                this.state = State.Flight;
                return;
            }

            road = this.route.legs().get(next);
            if (road.type() != Route.Type.Highway) {
                this.state = State.Flight;
                return;
            }
        }

        if (!this.aligned(road)) {
            this.walk(this.waypoint(this.entry(road)), true);
            return;
        }

        this.start(next);
    }

    //endregion

    //region Highway travel

    /**
     * Starts Bounce Fly toward the end of a highway leg.
     *
     * @param leg highway route leg index
     */
    private void start(int leg) {
        if (leg < 0 || leg >= this.route.legs().size()) {
            return;
        }

        Route.Leg road = this.route.legs().get(leg);
        if (road.type() != Route.Type.Highway) return;

        this.leg = leg;

        if (this.state != State.Highway) {
            this.point = null;
            this.block = null;
            this.ready = false;
            this.mining = false;
        }

        this.rotate(road.end());
        this.bounce(true);

        this.state = State.Highway;
    }

    /**
     * Follows the current highway leg to its endpoint.
     */
    private void highway() {
        if (this.leg < 0 || this.leg >= this.route.legs().size()) {
            return;
        }

        Route.Leg road = this.route.legs().get(this.leg);
        if (road.type() != Route.Type.Highway) return;

        int next = this.leg + 1;
        boolean exiting = next < this.route.legs().size() &&
            this.route.legs().get(next).type() != Route.Type.Highway;

        if (exiting && this.point == null && this.timer > 0) {
            if (--this.timer > 0) return;
        }

        if (this.timer <= 0) {
            BounceFly bounce = Modules.get().get(BounceFly.class);
            if (bounce != null && !bounce.isActive()) {
                this.rotate(road.end());
                bounce.toggle();
            }

            if (!this.aligned(road) ||
                !this.close(road.end(), proximity)) {
                return;
            }

            if (exiting && this.point == null) {
                this.point = this.space();
                if (this.point == null) {
                    this.timer = retry;
                    return;
                }
            }

            this.bounce(false);
            this.cancel();

            this.timer = halt;
        }

        this.stop();

        if (--this.timer > 0) return;

        this.cross();
    }

    /**
     * Advances after reaching a highway endpoint.
     */
    private void cross() {
        int next = this.leg + 1;
        if (next >= this.route.legs().size()) {
            this.state = State.Done;
            return;
        }

        Route.Leg following = this.route.legs().get(next);
        if (following.type() == Route.Type.Highway) {
            this.walk(this.waypoint(this.entry(following)), true);
            this.state = State.Entry;
            return;
        }

        this.leg = next;
        this.timer = 0;

        this.goal = null;
        this.block = null;

        this.seen = false;
        this.join = false;
        this.ready = false;
        this.mining = false;

        this.state = State.Exit;
    }

    //endregion

    //region Clear space search

    /**
     * Finds the nearest clear space below the player.
     *
     * @return closest point in a clear space, or null when unavailable
     */
    private BlockPos space() {
        BlockPos origin = this.mc.player.getBlockPos();

        int limit = origin.getY() - floor - height + 1;
        if (limit < 0) return null;

        int reach = Math.max(width / 2, depth / 2);
        int upper = (height - 1) / 2;

        double distance = Double.MAX_VALUE;
        BlockPos best = null;

        for (int range = 0; range <= search; range++) {
            for (int px = -range; px <= range; px++) {
                for (int pz = -range; pz <= range; pz++) {
                    if (Math.max(Math.abs(px), Math.abs(pz)) != range) {
                        continue;
                    }

                    for (int offset = 0; offset <= limit; offset++) {
                        int py = -upper - offset;

                        BlockPos center = origin.add(px, py, pz);
                        BlockPos point = this.closest(center, origin);

                        double current = point.getSquaredDistance(origin);
                        if (current >= distance || !this.clear(center)) {
                            continue;
                        }

                        best = point.add(0, 1, 0);
                        distance = current;
                    }
                }
            }

            if (best == null) continue;

            double next = Math.max(0.0, range + 1 - reach);
            if (distance <= next * next) break;
        }

        return best == null ? null : best.toImmutable();
    }

    /**
     * Checks whether a space contains only air or flowing lava.
     *
     * @param center center of the space
     * @return true when the entire space is loaded and clear
     */
    private boolean clear(BlockPos center) {
        int minx = center.getX() - width / 2;
        int miny = center.getY() - height / 2;
        int minz = center.getZ() - depth / 2;

        int maxx = minx + width - 1;
        int maxy = miny + height - 1;
        int maxz = minz + depth - 1;

        if (miny < this.mc.world.getBottomY() ||
            maxy > this.mc.world.getTopYInclusive()) {
            return false;
        }

        if (!this.loaded(minx, minz, maxx, maxz)) {
            return false;
        }

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int py = miny; py <= maxy; py++) {
            for (int px = minx; px <= maxx; px++) {
                for (int pz = minz; pz <= maxz; pz++) {
                    pos.set(px, py, pz);

                    BlockState state = this.mc.world.getBlockState(pos);
                    Fluid fluid = state.getFluidState().getFluid();

                    if (!state.isAir() && fluid != Fluids.FLOWING_LAVA) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Checks whether all chunks in an area are loaded.
     *
     * @param minx minimum box X
     * @param minz minimum box Z
     * @param maxx maximum box X
     * @param maxz maximum box Z
     * @return true when every required chunk is loaded
     */
    private boolean loaded(int minx, int minz, int maxx, int maxz) {
        for (int px = minx >> 4; px <= maxx >> 4; px++) {
            for (int pz = minz >> 4; pz <= maxz >> 4; pz++) {
                if (!this.mc.world.isChunkLoaded(px, pz)) return false;
            }
        }
        return true;
    }

    /**
     * Finds the closest point inside a tested box.
     *
     * @param center center of the tested box
     * @param origin reference position
     * @return box point closest to the reference position
     */
    private BlockPos closest(BlockPos center, BlockPos origin) {
        int minx = center.getX() - width / 2;
        int miny = center.getY() - height / 2;
        int minz = center.getZ() - depth / 2;

        int maxx = minx + width - 1;
        int maxy = miny + height - 1;
        int maxz = minz + depth - 1;

        int px = Math.max(minx, Math.min(origin.getX(), maxx));
        int py = Math.max(miny, Math.min(origin.getY(), maxy));
        int pz = Math.max(minz, Math.min(origin.getZ(), maxz));

        return new BlockPos(px, py, pz);
    }

    //endregion

    //region Highway exit

    /**
     * Returns from highway travel through the selected clear space.
     */
    private void exit() {
        if (this.leg < 0 || this.leg >= this.route.legs().size()) {
            return;
        }

        Route.Leg leg = this.route.legs().get(this.leg);
        Vec2f next = leg.end();

        BlockPos destination = new BlockPos(
            Math.round(next.x), level,
            Math.round(next.y)
        );

        if (this.mc.player.isGliding()) {
            this.timer = 0;
            this.point = null;
            this.block = null;
            this.goal = destination.toImmutable();

            this.seen = false;
            this.join = false;
            this.ready = false;
            this.mining = false;

            this.state = State.Flight;
            return;
        }

        if (this.point == null) return;

        this.deploy(destination);
    }

    /**
     * Reaches the clear-space point and starts the next elytra flight.
     *
     * @param destination next standard route point
     */
    private void deploy(BlockPos destination) {
        IBaritone baritone = this.baritone();

        if (!this.ready) {
            if (this.reach(this.point, lava)) this.fill();

            if (this.goal != null && this.goal.equals(this.point)) {
                if (baritone.getCustomGoalProcess().isActive() ||
                    baritone.getPathingBehavior().isPathing()) {
                    this.seen = true;
                    return;
                }

                if (!this.seen) return;

                this.ready = true;
                this.block = this.mc.player.getBlockPos().down();

                this.abort();
                return;
            }

            if (this.pathing()) {
                this.abort();
                return;
            }

            this.walk(this.point, true);
            return;
        }

        if (this.block != null) this.toward(this.block);

        if (!this.mine() || this.mc.player.isOnGround()) {
            return;
        }

        this.block = null;
        this.release();

        if (baritone.getElytraProcess().isActive()) return;

        if (baritone.getCustomGoalProcess().isActive() ||
            baritone.getPathingBehavior().isPathing()) {
            this.abort();
            return;
        }

        this.fly(destination, false);
    }

    //endregion

    //region Block control

    /**
     * Fills nearby lava source blocks with netherrack.
     */
    private void fill() {
        AirPlace air = Modules.get().get(AirPlace.class);

        int slot = this.netherrack();
        if (slot < 0 || air == null) return;

        BlockPos origin = this.mc.player.getBlockPos();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int px = -lava; px <= lava; px++) {
            for (int py = -lava; py <= lava; py++) {
                for (int pz = -lava; pz <= lava; pz++) {
                    if (px * px + py * py + pz * pz > lava * lava) {
                        continue;
                    }

                    pos.set(origin.getX() + px, origin.getY() + py, origin.getZ() + pz);
                    if (this.mc.world.getFluidState(pos).getFluid() != Fluids.LAVA) {
                        continue;
                    }

                    air.place(pos.toImmutable(), slot);
                }
            }
        }
    }

    /**
     * Places netherrack at a specific position.
     *
     * @param pos target block position
     * @return true when the placement packet was sent
     */
    private boolean place(BlockPos pos) {
        int slot = this.netherrack();
        AirPlace air = Modules.get().get(AirPlace.class);
        return air != null && slot >= 0 && air.place(pos, slot);
    }

    /**
     * Breaks the saved block underneath the player.
     *
     * @return true when the block no longer needs breaking
     */
    private boolean mine() {
        if (this.block == null) return true;

        if (this.mc.world.getBlockState(this.block).isReplaceable()) {
            if (this.mc.interactionManager != null) {
                this.mc.interactionManager.cancelBlockBreaking();
            }

            this.mining = false;
            return true;
        }

        if (this.mc.interactionManager == null) return false;

        if (!this.mining) {
            this.mc.interactionManager.attackBlock(this.block, Direction.UP);
            this.mining = true;
        } else {
            this.mc.interactionManager.updateBlockBreakingProgress(
                this.block, Direction.UP
            );
        }

        this.mc.player.swingHand(Hand.MAIN_HAND);
        return false;
    }

    /**
     * Finds netherrack in the hotbar.
     *
     * @return netherrack hotbar slot, or -1 when unavailable
     */
    private int netherrack() {
        if (this.mc.player == null) return -1;

        PlayerInventory inventory = this.mc.player.getInventory();

        for (int idx = 0; idx < 9; idx++) {
            if (inventory.getStack(idx).isOf(Items.NETHERRACK)) {
                return idx;
            }
        }

        return -1;
    }

    //endregion

    //region Baritone control

    /**
     * Starts Baritone elytra pathing toward a destination.
     *
     * @param pos destination position
     * @param exact whether Y must be part of the goal
     * @return true when elytra pathing was started
     */
    private boolean fly(BlockPos pos, boolean exact) {
        IBaritone baritone = this.baritone();
        if (!baritone.getElytraProcess().isLoaded()) {
            return false;
        }

        if (this.goal != null && this.goal.equals(pos) &&
            baritone.getElytraProcess().isActive()) {
            return true;
        }

        if (this.pathing()) this.cancel();

        Goal goal = exact ? new GoalBlock(pos) :
            new GoalXZ(pos.getX(), pos.getZ());

        try {
            baritone.getElytraProcess().pathTo(goal);

            this.goal = pos.toImmutable();
            this.seen = false;
            this.join = exact;

            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Starts normal Baritone pathing toward a destination.
     *
     * @param pos destination position
     * @param exact whether Y must be part of the goal
     */
    private void walk(BlockPos pos, boolean exact) {
        if (this.goal != null && this.goal.equals(pos)) {
            return;
        }

        if (this.pathing()) this.cancel();

        Goal goal = exact ? new GoalBlock(pos) :
            new GoalXZ(pos.getX(), pos.getZ());

        IBaritone baritone = this.baritone();
        baritone.getCustomGoalProcess().setGoalAndPath(goal);

        this.goal = pos.toImmutable();
        this.seen = false;
        this.join = false;
    }

    /**
     * Cancels controlled pathing and releases movement inputs.
     */
    private void abort() {
        this.cancel();
        this.release();
    }

    /**
     * Cancels active Baritone movement.
     */
    private void cancel() {
        IBaritone baritone = this.baritone();

        baritone.getElytraProcess().onLostControl();
        baritone.getCustomGoalProcess().onLostControl();
        baritone.getPathingBehavior().forceCancel();

        this.goal = null;
        this.seen = false;
        this.join = false;
    }

    /**
     * Checks whether elytra pathing switched to a landing target.
     *
     * @return true when the elytra destination changed internally
     */
    private boolean landing() {
        BlockPos current = this.baritone().getElytraProcess().currentDestination();
        return current != null && this.goal != null && !current.equals(this.goal);
    }

    /**
     * Checks whether the current Baritone task is still running.
     *
     * @return true while the task has not completed
     */
    private boolean waiting() {
        if (this.pathing()) {
            this.seen = true;
            return true;
        }
        return !this.seen;
    }

    /**
     * Checks whether Baritone is processing or following a path.
     *
     * @return true while any owned path is active
     */
    private boolean pathing() {
        IBaritone baritone = this.baritone();
        return baritone.getElytraProcess().isActive()
            || baritone.getCustomGoalProcess().isActive()
            || baritone.getPathingBehavior().isPathing();
    }

    //endregion

    //region Movement control

    /**
     * Moves toward the center of a block.
     *
     * @param pos block position to move toward
     */
    private void toward(BlockPos pos) {
        this.release();
        this.rotate(new Vec2f(
            pos.getX() + 0.5F,
            pos.getZ() + 0.5F
        ));

        this.mc.options.forwardKey.setPressed(true);
    }

    /**
     * Holds the player still during a highway transition.
     */
    private void stop() {
        this.release();

        this.mc.player.stopGliding();
        this.mc.player.setVelocity(0.0, 0.0, 0.0);
        this.mc.player.setSprinting(false);
        this.mc.player.setPitch(90.0F);
    }

    /**
     * Rotates the player toward a highway point.
     *
     * @param point highway target
     */
    private void rotate(Vec2f point) {
        double px = point.x - this.mc.player.getX();
        double pz = point.y - this.mc.player.getZ();

        float yaw = (float) Math.toDegrees(
            Math.atan2(-px, pz)
        );

        this.mc.player.setYaw(yaw);
        this.mc.player.setHeadYaw(yaw);
        this.mc.player.setBodyYaw(yaw);
    }

    /**
     * Sets Bounce Fly to the requested state.
     *
     * @param active requested module state
     */
    private void bounce(boolean active) {
        BounceFly bounce = Modules.get().get(BounceFly.class);
        if (bounce == null || bounce.isActive() == active) {
            return;
        }

        bounce.toggle();
    }

    /**
     * Releases forced movement inputs.
     */
    private void release() {
        if (this.mc.options == null) return;

        this.mc.options.forwardKey.setPressed(false);
        this.mc.options.backKey.setPressed(false);
        this.mc.options.leftKey.setPressed(false);
        this.mc.options.rightKey.setPressed(false);
        this.mc.options.jumpKey.setPressed(false);
        this.mc.options.sneakKey.setPressed(false);
    }

    //endregion

    //region Validation and utilities

    /**
     * Returns the primary Baritone instance.
     *
     * @return primary Baritone instance
     */
    private IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /**
     * Returns the Navigation module.
     *
     * @return Navigation module, or null when unavailable
     */
    private Navigation navigation() {
        return Modules.get().get(Navigation.class);
    }

    /**
     * Returns the current player X/Z position.
     *
     * @return player X/Z position
     */
    private Vec2f position() {
        return new Vec2f(
            (float) this.mc.player.getX(),
            (float) this.mc.player.getZ()
        );
    }

    /**
     * Checks horizontal proximity to a route point.
     *
     * @param point route point
     * @param radius maximum distance
     * @return true when the point is within range
     */
    private boolean close(Vec2f point, double radius) {
        double px = point.x - this.mc.player.getX();
        double pz = point.y - this.mc.player.getZ();
        return px * px + pz * pz <= radius * radius;
    }

    /**
     * Checks horizontal proximity to a block position.
     *
     * @param pos block position
     * @param radius maximum distance
     * @return true when the position is within range
     */
    private boolean close(BlockPos pos, double radius) {
        double px = pos.getX() + 0.5 - this.mc.player.getX();
        double pz = pos.getZ() + 0.5 - this.mc.player.getZ();
        return px * px + pz * pz <= radius * radius;
    }

    /**
     * Checks exact proximity to a block position.
     *
     * @param pos block position
     * @param radius maximum distance
     * @return true when the position is within range
     */
    private boolean reach(BlockPos pos, double radius) {
        double px = pos.getX() + 0.5 - this.mc.player.getX();
        double py = pos.getY() - this.mc.player.getY();
        double pz = pos.getZ() + 0.5 - this.mc.player.getZ();
        return px * px + py * py + pz * pz <= radius * radius;
    }

    /**
     * Checks whether the required client state is available.
     *
     * @return true when ready to run the module
     */
    private boolean valid() {
        return this.mc.player != null
            && this.mc.world != null
            && this.mc.getNetworkHandler() != null
            && World.NETHER.equals(this.mc.world.getRegistryKey());
    }

    //endregion
}
