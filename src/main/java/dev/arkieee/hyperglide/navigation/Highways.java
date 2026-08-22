package dev.arkieee.hyperglide.navigation;

import net.minecraft.util.math.Vec2f;
import java.util.ArrayList;
import java.util.List;

public final class Highways {
    private static final float border = 3750000.0F;
    private static final float spacing = 5000.0F;
    private static final float grid = 50000.0F;

    private static final float[] squares = {
        1000.0F, 2000.0F, 2500.0F, 3131.0F, 5000.0F, 10000.0F,
        15000.0F, 20000.0F, 25000.0F, 30000.0F, 50000.0F, 75000.0F,
        125000.0F, 250000.0F, 325000.0F, 500000.0F, 750000.0F,
        1000000.0F, 1875000.0F, 2500000.0F, 3750000.0F
    };

    private static final float[] diamonds = {
        50000.0F, 125000.0F, 250000.0F, 500000.0F
    };

    private static final List<Road> roads = create();

    private Highways() {}

    /**
     * Defines the type of highway.
     */
    public enum Kind {
        Axis,
        Diagonal,
        Square,
        Diamond,
        Grid,
        Special
    }

    /**
     * Returns the complete known highway network.
     *
     * @return immutable highway road list
     */
    public static List<Road> roads() {
        return roads;
    }

    //region Highway generation

    /**
     * Builds the known Nether highway network.
     *
     * @return generated highway road list
     */
    private static List<Road> create() {
        List<Road> roads = new ArrayList<>();
        axis(roads);
        diagonal(roads);
        square(roads);
        diamond(roads);
        grid(roads);
        return List.copyOf(roads);
    }

    /**
     * Adds the four cardinal axis highways.
     *
     * @param roads destination road list
     */
    private static void axis(List<Road> roads) {
        Vec2f center = Vec2f.ZERO;

        roads.add(new Road("Axis +X", Kind.Axis, List.of(
            new Segment(center, new Vec2f(border, 0.0F))
        )));

        roads.add(new Road("Axis -X", Kind.Axis, List.of(
            new Segment(center, new Vec2f(-border, 0.0F))
        )));

        roads.add(new Road("Axis +Z", Kind.Axis, List.of(
            new Segment(center, new Vec2f(0.0F, border))
        )));

        roads.add(new Road("Axis -Z", Kind.Axis, List.of(
            new Segment(center, new Vec2f(0.0F, -border))
        )));
    }

    /**
     * Adds the four diagonal highways.
     *
     * @param roads destination road list
     */
    private static void diagonal(List<Road> roads) {
        Vec2f center = Vec2f.ZERO;

        roads.add(new Road("Diagonal +X +Z", Kind.Diagonal, List.of(
            new Segment(center, new Vec2f(border, border))
        )));

        roads.add(new Road("Diagonal +X -Z", Kind.Diagonal, List.of(
            new Segment(center, new Vec2f(border, -border))
        )));

        roads.add(new Road("Diagonal -X +Z", Kind.Diagonal, List.of(
            new Segment(center, new Vec2f(-border, border))
        )));

        roads.add(new Road("Diagonal -X -Z", Kind.Diagonal, List.of(
            new Segment(center, new Vec2f(-border, -border))
        )));
    }

    /**
     * Adds every configured square ring road.
     *
     * @param roads destination road list
     */
    private static void square(List<Road> roads) {
        for (float radius : squares) {
            Vec2f nw = new Vec2f(-radius, -radius);
            Vec2f ne = new Vec2f(radius, -radius);
            Vec2f se = new Vec2f(radius, radius);
            Vec2f sw = new Vec2f(-radius, radius);

            roads.add(new Road("Square " + (int) radius,
                Kind.Square, List.of(
                new Segment(nw, ne), new Segment(ne, se),
                new Segment(se, sw), new Segment(sw, nw)
            )));
        }
    }

    /**
     * Adds every configured diamond ring road.
     *
     * @param roads destination road list
     */
    private static void diamond(List<Road> roads) {
        for (float radius : diamonds) {
            Vec2f north = new Vec2f(0.0F, -radius);
            Vec2f east = new Vec2f(radius, 0.0F);
            Vec2f south = new Vec2f(0.0F, radius);
            Vec2f west = new Vec2f(-radius, 0.0F);

            roads.add(new Road("Diamond " + (int) radius,
                Kind.Diamond, List.of(
                new Segment(north, east), new Segment(east, south),
                new Segment(south, west), new Segment(west, north)
            )));
        }
    }

    /**
     * Adds the 100k by 100k highway grids around spawn.
     *
     * @param roads destination road list
     */
    private static void grid(List<Road> roads) {
        for (float offset = spacing; offset <= grid; offset += spacing) {
            grid(roads, offset);
            grid(roads, -offset);
        }
    }

    /**
     * Adds one horizontal and vertical grid road at an offset.
     *
     * @param roads destination road list
     * @param offset grid coordinate offset
     */
    private static void grid(List<Road> roads, float offset) {
        List<Segment> sx = segments(offset, true);
        List<Segment> sz = segments(offset, false);

        if (!sx.isEmpty()) {
            roads.add(new Road("Grid X " + (int) offset, Kind.Grid, sx));
        }

        if (!sz.isEmpty()) {
            roads.add(new Road("Grid Z " + (int) offset, Kind.Grid, sz));
        }
    }

    /**
     * Builds a grid road without sections already owned by a square.
     *
     * @param offset grid coordinate offset
     * @param cx true for a constant X road, false for constant Z
     * @return non-overlapping grid road segments
     */
    private static List<Segment> segments(float offset, boolean cx) {
        float radius = Math.abs(offset);

        if (!exists(radius)) {
            return cx ? List.of(
                new Segment(new Vec2f(offset, -grid), new Vec2f(offset, grid))
            ) : List.of(
                new Segment(new Vec2f(-grid, offset), new Vec2f(grid, offset))
            );
        }

        if (radius >= grid) return List.of();

        return cx ? List.of(
            new Segment(new Vec2f(offset, -grid), new Vec2f(offset, -radius)),
            new Segment(new Vec2f(offset, radius), new Vec2f(offset, grid))
        ) : List.of(
            new Segment(new Vec2f(-grid, offset), new Vec2f(-radius, offset)),
            new Segment(new Vec2f(radius, offset), new Vec2f(grid, offset))
        );
    }

    /**
     * Checks whether a configured square exists at a radius.
     *
     * @param radius square radius to check
     * @return true when the square exists
     */
    private static boolean exists(float radius) {
        for (float square : squares) {
            if (square == radius) return true;
        }
        return false;
    }

    //endregion

    //region Data structures

    /**
     * Represents a highway composed of one or more segments.
     *
     * @param name highway name
     * @param kind highway type
     * @param segments highway segments
     */
    public record Road(String name, Kind kind, List<Segment> segments) {
        public Road {
            segments = List.copyOf(segments);
        }
    }

    //endregion
}
