package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.Baritone;
import hyperglide.utilities.Client;
import hyperglide.utilities.Elytra;
import hyperglide.utilities.Flight;
import hyperglide.utilities.Hotbar;
import hyperglide.utilities.Placement;
import hyperglide.utilities.Player;
import hyperglide.navigation.Route;
import hyperglide.navigation.Route.Leg;
import hyperglide.navigation.Segment;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import java.util.ArrayList;
import java.util.List;

public class AutoPilot extends Module {
    private static final double approach = 128.0;
    private static final double proximity = 3.0;

    private static final int halt = 25;
    private static final int lava = 3;
    private static final int level = 120;

    private static final int width = 24;
    private static final int height = 8;
    private static final int depth = 24;

    private static final int floor = 32;
    private static final int search = 64;
    private static final int retry = 10;
    private static final int settle = 5;

    private final Flight flight = Flight.get();

    private State state = State.Idle;
    private Route route;

    private BlockPos target;
    private BlockPos goal;
    private BlockPos point;

    private List<BlockPos> blocks;
    private MiningTweaks mining;

    private int leg = -1;
    private int timer;

    private boolean join;
    private boolean enabled;
    private boolean emergency;
    private boolean raised;

    /**
     * Defines the current travel stage.
     */
    private enum State {
        Idle,
        Seek,
        Flight,
        Land,
        Entry,
        Highway,
        Exit,
        Drop,
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

        this.reset();
        Baritone.settings(1.43, 0.4, false);

        this.mining = Modules.get().get(MiningTweaks.class);

        if (this.mining != null) {
            this.enabled = this.mining.isActive();
            if (!this.enabled) this.mining.toggle();
        }

        this.setup();
        this.load();
    }

    /**
     * Stops active movement and clears runtime state.
     */
    @Override
    public void onDeactivate() {
        this.bounce(false);

        if (this.emergency) {
            this.release();
        } else {
            this.abort();
        }

        this.restore();
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

        if (!Elytra.equipped() && Elytra.hotbar() < 0) {
            this.error("Elytra flight is unavailable.");
            this.toggle();
            return;
        }

        this.setup();

        if (this.route == null || this.target == null) {
            this.load();
        }

        if (this.route == null || this.target == null) {
            return;
        }

        switch (this.state) {
            case Idle -> this.idle();
            case Seek -> this.seek();
            case Flight -> this.flight();
            case Land -> this.land();
            case Entry -> this.entry();
            case Highway -> this.highway();
            case Exit -> this.exit();
            case Drop -> this.drop();
            case Final -> this.finish();
            case Done -> this.toggle();
        }
    }

    /**
     * Stops Auto Pilot when Baritone starts an emergency landing.
     *
     * @param event received chat message event
     */
    @EventHandler
    private void message(ReceiveMessageEvent event) {
        if (!event.getMessage().getString().contains(
            "Emergency landing - almost out of " +
            "elytra durability or fireworks")) {
            return;
        }

        this.emergency = true;
        this.toggle();
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
        this.blocks = null;
        this.mining = null;

        this.leg = -1;
        this.timer = 0;
        this.join = false;
        this.enabled = false;
        this.emergency = false;
        this.raised = false;
    }

    /**
     * Keeps the required travel modules enabled.
     */
    private void setup() {
        Navigation navigation = this.navigation();
        if (navigation != null && !navigation.isActive()) {
            navigation.toggle();
        }

        ElytraTweaks tweaks = Modules.get().get(ElytraTweaks.class);
        if (tweaks != null) {
            if (!tweaks.isActive()) tweaks.toggle();
            tweaks.deploy(true);
        }

        if (this.mining != null && !this.mining.isActive()) {
            this.mining.toggle();
        }
    }

    /**
     * Restores the Mining Tweaks state from before activation.
     */
    private void restore() {
        if (this.mining != null && !this.enabled &&
            this.mining.isActive()) {
            this.mining.toggle();
        }
    }

    //endregion

    //region Route management

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
     * Finds the closest remaining route leg.
     *
     * @return route leg index, or -1 when unavailable
     */
    private int progress() {
        List<Leg> legs = this.route.legs();

        int size = legs.size();
        if (size == 0) return -1;

        int start = Math.max(0, this.leg);
        if (start >= size) return -1;

        Vec2f position = Player.position();
        float distance = Float.MAX_VALUE;
        int best = -1;

        for (int index = start; index < size; index++) {
            Route.Leg leg = legs.get(index);
            Vec2f point = this.project(leg, position);

            float current = position.distanceSquared(point);
            if (current >= distance) continue;

            distance = current;
            best = index;
        }

        return best;
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

        Vec2f position = Player.position();
        Vec2f point = this.project(leg, position);

        float distance = position.distanceSquared(point);
        return distance <= proximity * proximity;
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
        Vec2f position = Player.position();
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

    //endregion

    //region Initial launch

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
        if (this.access()) return;

        if (!this.open()) {
            if (this.timer > 0 && --this.timer > 0) {
                return;
            }

            this.point = this.space(true);
            if (this.point == null) {
                this.timer = retry;
                return;
            }

            this.timer = settle;
            this.state = State.Seek;
            return;
        }

        this.timer = 0;
        this.flight();
    }

    /**
     * Moves to a clear launch area before starting elytra travel.
     */
    private void seek() {
        if (this.point == null) {
            this.state = State.Idle;
            return;
        }

        if (this.reach(this.point, lava)) {
            this.fill();
        }

        if (this.goal == null ||
            !this.goal.equals(this.point)) {

            if (this.pathing()) this.abort();
            this.walk(this.point, true);
            return;
        }

        if (this.pathing()) return;
        this.prepare();
    }

    /**
     * Prepares the selected clear space for launch.
     */
    private void prepare() {
        if (!this.mc.player.isOnGround() || this.open()) {
            this.launch();
            return;
        }

        if (!this.close(this.point, 1.5)) {
            this.goal = null;
            this.timer = settle;
            return;
        }

        if (this.timer > 0) {
            this.timer--;
            return;
        }

        if (!this.raised) {
            this.launch();
            return;
        }

        this.toward(this.point);

        if (this.mining != null) {
            BlockPos pos = this.point.down();
            this.mining.mine(pos, Direction.UP);
        }
    }

    /**
     * Resumes normal route flight after finding clear launch space.
     */
    private void launch() {
        this.abort();

        this.point = null;
        this.blocks = null;
        this.timer = 0;
        this.raised = false;

        this.state = State.Flight;
    }

    //endregion

    //region Standard travel

    /**
     * Follows standard route legs with Baritone Elytra.
     */
    private void flight() {
        if (this.goal == null || this.resume()) {
            this.follow();
        }
    }

    /**
     * Waits for the flight to finish before choosing another leg.
     *
     * @return true when travel can continue
     */
    private boolean resume() {
        if (this.join && this.landing()) {
            this.cancel();
            this.glide();

            this.blocks = null;
            this.timer = this.rocket() ? 3 : 0;
            this.mc.player.setPitch(-90.0F);

            this.state = State.Land;
            return false;
        }

        if (this.pathing() ||
            !this.mc.player.isOnGround()) {
            return false;
        }

        this.goal = null;
        this.join = false;

        if (this.close(this.target, approach)) {
            this.finish();
            return false;
        }

        int progress = this.progress();
        if (progress < 0) return false;

        this.leg = progress;
        return true;
    }

    /**
     * Moves toward the current route leg or enters its highway.
     */
    private void follow() {
        List<Leg> legs = this.route.legs();
        if (this.leg >= legs.size() ||
            this.leg < 0 || this.access()) {
            return;
        }

        Route.Leg leg = legs.get(this.leg);
        boolean highway = leg.type() == Route.Type.Highway;

        int next = this.leg + 1;
        boolean joining = highway || next < legs.size() &&
            legs.get(next).type() == Route.Type.Highway;

        Vec2f point = highway ? this.entry(leg) : leg.end();
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

        if (this.goal != null && this.pathing()) {
            return;
        }

        this.goal = null;

        if (this.done()) return;
        this.walk(this.target, false);
    }

    /**
     * Completes travel when the player is close to the target.
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
     * Uses the highway directly when it is already within reach.
     *
     * @return true when highway entry has taken over
     */
    private boolean access() {
        List<Leg> legs = this.route.legs();
        if (this.leg < 0 || this.leg >= legs.size()) {
            return false;
        }

        Route.Leg leg = legs.get(this.leg);
        boolean highway = leg.type() == Route.Type.Highway;

        if (highway && this.aligned(leg)) {
            this.start(this.leg);
            return true;
        }

        int next = this.leg + 1;
        boolean joining = highway || next < legs.size() &&
            legs.get(next).type() == Route.Type.Highway;

        if (!joining || !this.mc.player.isOnGround()) {
            return false;
        }

        Vec2f point = highway ? this.entry(leg) : leg.end();
        if (!this.close(point, approach)) return false;

        this.state = State.Entry;
        return true;
    }

    /**
     * Finishes the normal flight and creates a landing block.
     */
    private void land() {
        this.mc.player.setPitch(-90.0F);
        this.glide();

        if (this.timer > 0) {
            this.timer--;
            return;
        }

        if (this.blocks == null) {
            if (this.mc.player.isOnGround() ||
                !this.mc.player.verticalCollision) {
                return;
            }
            this.blocks = this.platform();
        }

        if (!this.support()) return;

        if (this.mc.player.isOnGround()) {
            this.mc.player.stopGliding();

            this.blocks = null;
            this.state = State.Entry;
        }
    }

    /**
     * Moves onto the next highway leg before bouncing.
     */
    private void entry() {
        if (this.goal != null) {
            if (this.pathing()) return;
            this.goal = null;
        }

        if (!this.refresh()) return;

        int next = this.leg;
        List<Leg> legs = this.route.legs();
        Route.Leg road = legs.get(next);

        if (road.type() != Route.Type.Highway) {
            if (++next >= legs.size()) {
                this.state = State.Flight;
                return;
            }

            road = legs.get(next);
            if (road.type() != Route.Type.Highway) {
                this.state = State.Flight;
                return;
            }
        }

        if (!this.aligned(road)) {
            Vec2f entry = this.entry(road);
            this.walk(this.waypoint(entry), true);
            return;
        }

        this.start(next);
    }

    /**
     * Keeps flight active during the upward landing climb.
     */
    private void glide() {
        if (this.blocks != null ||
            !Elytra.equipped() ||
            this.mc.player.isOnGround() ||
            this.mc.player.isGliding() ||
            this.mc.player.verticalCollision) {
            return;
        }

        Elytra.start();
        this.mc.player.startGliding();
    }

    //endregion

    //region Highway travel

    /**
     * Starts Bounce Fly toward the end of a highway leg.
     *
     * @param leg highway route leg index
     */
    private void start(int leg) {
        List<Leg> legs = this.route.legs();
        if (leg < 0 || leg >= legs.size()) {
            return;
        }

        Route.Leg road = legs.get(leg);
        if (road.type() != Route.Type.Highway) {
            return;
        }

        this.leg = leg;

        if (this.state != State.Highway) {
            this.point = null;
            this.blocks = null;
        }

        this.rotate(road.end());
        this.bounce(true);

        this.state = State.Highway;
    }

    /**
     * Follows the current highway leg to its endpoint.
     */
    private void highway() {
        List<Leg> legs = this.route.legs();
        if (this.leg < 0 || this.leg >= legs.size()) {
            return;
        }

        Route.Leg road = legs.get(this.leg);
        if (road.type() != Route.Type.Highway) {
            return;
        }

        int next = this.leg + 1;
        boolean exiting = next < legs.size() &&
            legs.get(next).type() != Route.Type.Highway;

        if (this.delay(exiting) || this.timer <= 0 &&
            !this.arrive(road, exiting)) {
            return;
        }

        this.stop();
        if (--this.timer <= 0) this.cross();
    }

    /**
     * Advances after reaching a highway endpoint.
     */
    private void cross() {
        List<Leg> legs = this.route.legs();

        int next = this.leg + 1;
        if (next >= legs.size()) {
            this.state = State.Done;
            return;
        }

        Route.Leg following = legs.get(next);
        if (following.type() == Route.Type.Highway) {
            Vec2f entry = this.entry(following);
            this.walk(this.waypoint(entry), true);
            this.state = State.Entry;
            return;
        }

        this.leg = next;
        this.timer = 0;

        this.goal = null;
        this.blocks = null;
        this.join = false;

        this.state = State.Exit;
    }

    /**
     * Prepares the transition after reaching a highway endpoint.
     *
     * @param road current highway leg
     * @param exiting whether the route leaves the highway next
     * @return true when the player can stop for the transition
     */
    private boolean arrive(Route.Leg road, boolean exiting) {
        BounceFly bounce = Modules.get().get(BounceFly.class);
        if (bounce != null && !bounce.isActive()) {
            this.rotate(road.end());
            bounce.toggle();
        }

        if (!this.aligned(road) ||
            !this.close(road.end(), proximity)) {
            return false;
        }

        if (exiting && this.point == null) {
            this.point = this.space(false);
            if (this.point == null) {
                this.timer = retry;
                return false;
            }
        }

        this.bounce(false);
        this.cancel();

        this.timer = halt;
        return true;
    }

    /**
     * Waits before searching again for a highway exit.
     *
     * @param exiting whether the route leaves the highway next
     * @return true while the retry delay is still active
     */
    private boolean delay(boolean exiting) {
        return exiting && this.point == null
            && this.timer > 0 && --this.timer > 0;
    }

    //endregion

    //region Clear space search

    /**
     * Adjusts a clear space point for launch or exit use.
     *
     * @param point clear space point
     * @param start whether selecting a launch point
     * @return adjusted point to traverse to
     */
    private BlockPos adjust(BlockPos point, boolean start) {
        if (!start) return point.up();

        this.raised = !this.solid(point.down());
        return this.raised ? point.up() : point;
    }

    /**
     * Finds the nearest clear space below the player.
     *
     * @param start whether the first box starts from player
     * @return closest point in a clear space, or null
     */
    private BlockPos space(boolean start) {
        BlockPos origin = this.mc.player.getBlockPos();
        if (start) this.raised = false;

        int limit = origin.getY() - floor;
        limit -= start ? 0 : height - 1;
        if (limit < 0) return null;

        int base = start ? height / 2 : -(height - 1) / 2;
        int reach = Math.max(width / 2, depth / 2);

        BlockPos best = null;
        double distance = Double.MAX_VALUE;

        for (int range = 0; range <= search; range++) {
            BlockPos point = this.scan(
                origin, range, base, limit, distance
            );

            if (point != null) {
                best = this.adjust(point, start);
                distance = point.getSquaredDistance(origin);
            }

            if (best != null) {
                int next = Math.max(0, range + 1 - reach);
                if (distance <= (double) next * next) break;
            }
        }

        return best != null ? best.toImmutable() : null;
    }

    /**
     * Searches one horizontal ring for the nearest clear space.
     *
     * @param origin player position
     * @param range horizontal search range
     * @param base initial vertical center offset
     * @param limit lowest vertical search offset
     * @param distance nearest distance already found
     * @return a closer usable point, or null when none is found
     */
    private BlockPos scan(BlockPos origin, int range,
        int base, int limit, double distance) {

        BlockPos best = null;

        for (int px = -range; px <= range; px++) {
            for (int pz = -range; pz <= range; pz++) {
                if (Math.max(Math.abs(px), Math.abs(pz)) != range) {
                    continue;
                }

                for (int offset = 0; offset <= limit; offset++) {
                    int py = base - offset;

                    BlockPos center = origin.add(px, py, pz);
                    BlockPos point = this.closest(center, origin);

                    double current = point.getSquaredDistance(origin);
                    if (current >= distance || !this.clear(center)) {
                        continue;
                    }

                    best = point;
                    distance = current;
                }
            }
        }

        return best;
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

        return new BlockPos(
            MathHelper.clamp(origin.getX(), minx, maxx),
            MathHelper.clamp(origin.getY(), miny, maxy),
            MathHelper.clamp(origin.getZ(), minz, maxz)
        );
    }

    /**
     * Checks whether the launch box above the player is clear.
     *
     * @return true when flight can start from the current position
     */
    private boolean open() {
        BlockPos origin = this.mc.player.getBlockPos();
        BlockPos center = origin.up(height / 2);
        return this.clear(center);
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

        if (!this.loaded(minx, minz, maxx, maxz)) return false;
        return this.empty(minx, miny, minz, maxx, maxy, maxz);
    }

    /**
     * Checks whether a space contains only air or flowing lava.
     *
     * @param minx minimum box X
     * @param miny minimum box Y
     * @param minz minimum box Z
     * @param maxx maximum box X
     * @param maxy maximum box Y
     * @param maxz maximum box Z
     * @return true when no solid block occupies the space
     */
    private boolean empty(int minx, int miny,
        int minz, int maxx, int maxy, int maxz) {

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
                if (!this.mc.world.isChunkLoaded(px, pz)) {
                    return false;
                }
            }
        }
        return true;
    }

    //endregion

    //region Highway exit

    /**
     * Moves to the selected exit block before starting the drop.
     */
    private void exit() {
        if (this.point == null || this.leg < 0 ||
            this.leg >= this.route.legs().size()) {
            return;
        }

        if (this.reach(this.point, lava)) this.fill();

        if (this.goal == null || !this.goal.equals(this.point)) {
            if (this.pathing()) this.abort();
            this.walk(this.point, true);
            return;
        }

        if (this.pathing()) return;

        if (!this.mc.player.isOnGround()) {
            this.depart();
            return;
        }

        if (!this.close(this.point, 1.5)) {
            this.goal = null;
            return;
        }

        this.blocks = this.column();

        this.abort();
        this.timer = settle;
        this.state = State.Drop;
    }

    /**
     * Mines the selected exit block while moving toward it.
     */
    private void drop() {
        if (this.point == null) return;

        if (this.timer > 0) {
            this.timer--;
            return;
        }

        if (this.blocks == null || this.blocks.isEmpty()) {
            this.depart();
            return;
        }

        this.blocks.removeIf(pos ->
            this.mc.world.getBlockState(pos).isAir()
        );

        if (this.blocks.isEmpty()) {
            this.depart();
            return;
        }

        if (this.mc.player.isOnGround()) {
            this.toward(this.point);
        }

        if (this.mining != null) {
            for (BlockPos pos : this.blocks) {
                this.mining.mine(pos, Direction.UP);
            }
        }
    }

    /**
     * Collects the solid exit column below the selected point.
     *
     * @return contiguous non-air blocks below the exit point
     */
    private List<BlockPos> column() {
        List<BlockPos> blocks = new ArrayList<>();

        int px = this.point.getX();
        int pz = this.point.getZ();

        for (int py = this.point.getY() - 1;
            py >= this.mc.world.getBottomY(); py--) {

            BlockPos pos = new BlockPos(px, py, pz);
            if (this.mc.world.getBlockState(pos).isAir()) {
                break;
            }

            blocks.add(pos);
        }

        return blocks;
    }

    /**
     * Stops exit and starts flying to the next route point.
     */
    private void depart() {
        List<Leg> legs = this.route.legs();
        if (this.leg < 0 || this.leg >= legs.size()) {
            return;
        }

        Route.Leg leg = legs.get(this.leg);
        BlockPos destination = this.waypoint(leg.end());

        this.release();

        if (!this.fly(destination, false)) {
            return;
        }

        this.point = null;
        this.blocks = null;
        this.timer = 0;

        this.state = State.Flight;
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
     * Collects the blocks directly below the player.
     *
     * @return one to four fixed platform blocks
     */
    private List<BlockPos> platform() {
        Box box = this.mc.player.getBoundingBox();

        int minx = MathHelper.floor(box.minX);
        int maxx = MathHelper.floor(Math.nextDown(box.maxX));
        int minz = MathHelper.floor(box.minZ);
        int maxz = MathHelper.floor(Math.nextDown(box.maxZ));

        int py = this.mc.player.getBlockPos().down(2).getY();
        List<BlockPos> blocks = new ArrayList<>(4);

        for (int px = minx; px <= maxx; px++) {
            for (int pz = minz; pz <= maxz; pz++) {
                blocks.add(new BlockPos(px, py, pz));
            }
        }

        return blocks;
    }

    /**
     * Places the saved landing platform.
     *
     * @return true when every required block is present
     */
    private boolean support() {
        if (this.blocks == null ||
            this.blocks.isEmpty()) {
            return false;
        }

        for (BlockPos pos : this.blocks) {
            if (!Placement.open(pos)) continue;
            if (!this.place(pos)) return false;
        }

        return true;
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
        if (!Baritone.loaded() || !this.flight.normal()) {
            return false;
        }

        if (this.goal != null && this.goal.equals(pos) &&
            Baritone.elytra()) {
            return true;
        }

        if (this.pathing()) this.cancel();

        try {
            Baritone.fly(pos, exact);
            this.goal = pos.toImmutable();
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
        if (this.goal != null && this.goal.equals(pos) &&
            this.pathing()) {
            return;
        }

        if (this.pathing()) this.cancel();
        Baritone.walk(pos, exact);

        this.goal = pos.toImmutable();
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
        Baritone.stop();
        this.goal = null;
        this.join = false;
    }

    /**
     * Checks whether Baritone is processing or following a path.
     *
     * @return true while any owned path is active
     */
    private boolean pathing() {
        return Baritone.elytra() || Baritone.pathing();
    }

    /**
     * Checks whether elytra pathing switched to a landing target.
     *
     * @return true when the elytra destination changed internally
     */
    private boolean landing() {
        BlockPos current = Baritone.destination();
        return current != null && this.goal != null
            && !current.equals(this.goal);
    }

    //endregion

    //region Movement control

    /**
     * Moves toward the center of a block.
     *
     * @param pos block position to move toward
     */
    private void toward(BlockPos pos) {
        Vec2f target = new Vec2f(
            pos.getX() + 0.5F, pos.getZ() + 0.5F
        );

        this.release();
        this.rotate(target);

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

        float yaw = MathHelper.wrapDegrees(
            this.mc.player.getYaw() + 180.0F
        );

        this.mc.player.setYaw(yaw);
        this.mc.player.setPitch(0.0F);
    }

    /**
     * Rotates the player toward a highway point.
     *
     * @param point highway target
     */
    private void rotate(Vec2f point) {
        double px = point.x - this.mc.player.getX();
        double pz = point.y - this.mc.player.getZ();

        double yaw = Math.atan2(-px, pz);
        Player.rotate((float) Math.toDegrees(yaw));
    }

    /**
     * Requests a firework for the current flight.
     *
     * @return true when the request was accepted
     */
    private boolean rocket() {
        return this.mc.interactionManager != null
            && this.flight.request(Elytra::firework);
    }

    /**
     * Sets Bounce Fly to the requested state.
     *
     * @param active requested module state
     */
    private void bounce(boolean active) {
        BounceFly bounce = Modules.get().get(BounceFly.class);
        if (bounce != null && bounce.isActive() != active) {
            bounce.toggle();
        }
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

    //region Utilities and validation

    /**
     * Returns the Navigation module.
     *
     * @return Navigation module, or null when unavailable
     */
    private Navigation navigation() {
        return Modules.get().get(Navigation.class);
    }

    /**
     * Finds netherrack in the hotbar.
     *
     * @return hotbar slot, or -1 when unavailable
     */
    private int netherrack() {
        return this.mc.player == null ? -1
            : Hotbar.find(Items.NETHERRACK);
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
     * Checks whether a block has a collision shape.
     *
     * @param pos block position to check
     * @return true when the block can collide with the player
     */
    private boolean solid(BlockPos pos) {
        BlockState state = this.mc.world.getBlockState(pos);
        return !state.getCollisionShape(this.mc.world, pos).isEmpty();
    }

    /**
     * Checks whether the required client state is available.
     *
     * @return true when ready to run the module
     */
    private boolean valid() {
        return Client.ready() && Client.nether();
    }

    //endregion
}
