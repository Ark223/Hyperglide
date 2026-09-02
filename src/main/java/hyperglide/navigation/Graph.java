package hyperglide.navigation;

import net.minecraft.util.math.Vec2f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Graph {
    private static final float epsilon = 0.05F;

    private final List<Node> nodes;
    private final List<Edge> edges;

    /**
     * Builds a connected graph from road geometry.
     *
     * @param roads roads used to build the graph
     */
    public Graph(List<Highways.Road> roads) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<Part> parts = this.parts(roads);

        for (int idx = 0; idx < parts.size(); idx++) {
            Part first = parts.get(idx);

            for (int next = idx + 1; next < parts.size(); next++) {
                this.split(first, parts.get(next));
            }
        }

        for (Part part : parts) {
            part.points.sort(Comparator.comparingDouble(
                part.segment::projection
            ));

            Node previous = null;

            for (Vec2f point : part.points) {
                Node current = this.node(nodes, point);
                if (current == previous) continue;

                if (previous != null) {
                    edges.add(new Edge(previous, current, part.road));
                }

                previous = current;
            }
        }

        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
    }

    /**
     * Returns every graph node.
     *
     * @return immutable node list
     */
    public List<Node> nodes() {
        return this.nodes;
    }

    /**
     * Returns every undirected graph edge.
     *
     * @return immutable edge list
     */
    public List<Edge> edges() {
        return this.edges;
    }

    //region Graph construction

    /**
     * Converts roads into mutable graph parts.
     *
     * @param roads roads to convert
     * @return mutable graph parts
     */
    private List<Part> parts(List<Highways.Road> roads) {
        List<Part> parts = new ArrayList<>();

        for (Highways.Road road : roads) {
            for (Segment segment : road.segments()) {
                parts.add(new Part(road, segment));
            }
        }

        return parts;
    }

    /**
     * Splits two graph parts at intersections or overlap boundaries.
     *
     * @param first first graph part
     * @param second second graph part
     */
    private void split(Part first, Part second) {
        Vec2f point = first.segment.intersection(second.segment);

        if (point != null) {
            this.add(first.points, point);
            this.add(second.points, point);
            return;
        }

        if (!first.segment.collinear(second.segment)) return;

        if (first.segment.contains(second.segment.start())) {
            this.add(first.points, second.segment.start());
        }

        if (first.segment.contains(second.segment.end())) {
            this.add(first.points, second.segment.end());
        }

        if (second.segment.contains(first.segment.start())) {
            this.add(second.points, first.segment.start());
        }

        if (second.segment.contains(first.segment.end())) {
            this.add(second.points, first.segment.end());
        }
    }

    /**
     * Adds a unique point to a graph part.
     *
     * @param points destination point list
     * @param point point to add
     */
    private void add(List<Vec2f> points, Vec2f point) {
        for (Vec2f current : points) {
            float dist = current.distanceSquared(point);
            if (dist <= epsilon * epsilon) return;
        }

        points.add(point);
    }

    /**
     * Returns an existing nearby node or creates one.
     *
     * @param nodes destination node list
     * @param point node position
     * @return canonical graph node
     */
    private Node node(List<Node> nodes, Vec2f point) {
        for (Node node : nodes) {
            float dist = node.pos.distanceSquared(point);
            if (dist <= epsilon * epsilon) return node;
        }

        Node node = new Node(point);
        nodes.add(node);
        return node;
    }

    //endregion

    //region Data structures

    /**
     * Represents a graph node at a fixed X/Z position.
     *
     * @param pos node position
     */
    public record Node(Vec2f pos) {
        @Override
        public boolean equals(Object object) {
            return object instanceof Node node
                && this.pos.equals(node.pos);
        }

        @Override
        public int hashCode() {
            return Float.hashCode(this.pos.x) * 31
                 + Float.hashCode(this.pos.y);
        }
    }

    /**
     * Represents an undirected highway connection between two graph nodes.
     *
     * @param start first node
     * @param end second node
     * @param road road containing the edge
     */
    public record Edge(Node start, Node end, Highways.Road road) {}

    /**
     * Represents a mutable road segment while building the graph.
     */
    private static final class Part {
        private final Highways.Road road;
        private final Segment segment;

        private final List<Vec2f> points = new ArrayList<>();

        private Part(Highways.Road road, Segment segment) {
            this.road = road;
            this.segment = segment;
            this.points.add(segment.start());
            this.points.add(segment.end());
        }
    }

    //endregion
}
