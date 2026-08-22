package dev.arkieee.hyperglide.navigation;

import net.minecraft.util.math.Vec2f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class Search {
    private static final float epsilon = 0.05F;

    private static final Graph graph = new Graph(Highways.roads());

    /**
     * Calculates the fastest route between two positions.
     *
     * @param start starting X/Z position
     * @param end destination X/Z position
     * @param highway highway travel speed
     * @param normal standard travel speed
     * @return fastest available route
     */
    public Route find(Vec2f start, Vec2f end, float highway, float normal) {
        float direct = this.distance(start, end);

        if (direct <= epsilon) return new Route(List.of(), 0.0F, 0.0F);
        if (highway <= normal) return this.direct(start, end, normal);

        Network network = this.build(start, end, highway, normal);

        Map<Graph.Node, Float> costs = new HashMap<>();
        Map<Graph.Node, Link> previous = new HashMap<>();

        PriorityQueue<State> queue = new PriorityQueue<>(
            Comparator.comparingDouble(State::cost)
        );

        costs.put(network.start, 0.0F);
        queue.add(new State(network.start, 0.0F));

        while (!queue.isEmpty()) {
            State state = queue.poll();
            float known = costs.getOrDefault(state.node, Float.POSITIVE_INFINITY);

            if (state.cost > known + epsilon) continue;
            if (state.node.equals(network.end)) break;

            for (Link link : network.links.getOrDefault(state.node, List.of())) {
                float speed = link.type == Route.Type.Highway ? highway : normal;
                float cost = state.cost + link.distance / speed;

                float old = costs.getOrDefault(link.end, Float.POSITIVE_INFINITY);
                if (cost >= old - epsilon) continue;

                costs.put(link.end, cost);
                previous.put(link.end, link);
                queue.add(new State(link.end, cost));
            }
        }

        return this.route(network.start, network.end, previous, highway, normal);
    }

    //region Graph preparation

    /**
     * Adds time-optimal entry and exit points to the highway graph.
     *
     * @param start starting position
     * @param end destination position
     * @param highway highway travel speed
     * @param normal standard travel speed
     * @return temporary routing graph
     */
    private Network build(Vec2f start, Vec2f end, float highway, float normal) {
        List<Graph.Node> nodes = new ArrayList<>(graph.nodes());
        Map<Graph.Node, List<Link>> links = new HashMap<>();

        Graph.Node source = this.node(nodes, start);
        Graph.Node target = this.node(nodes, end);

        this.connect(links, source, target, null, Route.Type.Normal);

        for (Graph.Edge edge : graph.edges()) {
            Segment segment = new Segment(
                edge.start().pos(), edge.end().pos()
            );

            List<Graph.Node> points = new ArrayList<>();
            this.add(points, edge.start());
            this.add(points, edge.end());

            List<Graph.Node> starts = this.candidates(
                nodes, segment, start, highway, normal
            );

            List<Graph.Node> ends = this.candidates(
                nodes, segment, end, highway, normal
            );

            for (Graph.Node point : starts) {
                this.add(points, point);
                this.connect(links, source, point, null, Route.Type.Normal);
            }

            for (Graph.Node point : ends) {
                this.add(points, point);
                this.connect(links, target, point, null, Route.Type.Normal);
            }

            points.sort(Comparator.comparingDouble(
                point -> segment.projection(point.pos())
            ));

            for (int idx = 1; idx < points.size(); idx++) {
                this.connect(links, points.get(idx - 1),
                    points.get(idx), edge.road(), Route.Type.Highway
                );
            }
        }

        return new Network(source, target, links);
    }

    /**
     * Calculates both time-optimal approach points on a highway edge.
     *
     * @param nodes available route nodes
     * @param segment highway edge
     * @param point off-highway position
     * @param highway highway travel speed
     * @param normal standard travel speed
     * @return optimal candidates for both travel directions
     */
    private List<Graph.Node> candidates(List<Graph.Node> nodes,
        Segment segment, Vec2f point, float highway, float normal) {

        float length = segment.length();
        if (length <= epsilon) return List.of();

        float ratio = segment.projection(point);
        Vec2f projection = segment.line(ratio);

        float height = this.distance(point, projection);
        float divisor = (float) Math.sqrt(highway * highway - normal * normal);

        float shift = height * normal / divisor / length;

        Graph.Node first = this.node(nodes, segment.point(ratio - shift));
        Graph.Node second = this.node(nodes, segment.point(ratio + shift));

        return first.equals(second) ? List.of(first) : List.of(first, second);
    }

    /**
     * Returns an existing nearby node or creates a temporary one.
     *
     * @param nodes available nodes
     * @param point requested position
     * @return canonical route node
     */
    private Graph.Node node(List<Graph.Node> nodes, Vec2f point) {
        for (Graph.Node node : nodes) {
            float dist = node.pos().distanceSquared(point);
            if (dist <= epsilon * epsilon) return node;
        }

        Graph.Node node = new Graph.Node(point);
        nodes.add(node);
        return node;
    }

    /**
     * Adds a node once to a temporary point list.
     *
     * @param nodes destination node list
     * @param node node to add
     */
    private void add(List<Graph.Node> nodes, Graph.Node node) {
        for (Graph.Node current : nodes) {
            if (current == node || current.equals(node)) {
                return;
            }
        }
        nodes.add(node);
    }

    /**
     * Adds a route link unless an equivalent link already exists.
     *
     * @param links destination adjacency map
     * @param link route link to add
     */
    private void add(Map<Graph.Node, List<Link>> links, Link link) {
        List<Link> list = links.computeIfAbsent(
            link.start, key -> new ArrayList<>()
        );

        for (Link current : list) {
            if (current.end.equals(link.end) &&
                current.road == link.road && current.type == link.type &&
                Math.abs(current.distance - link.distance) <= epsilon) return;
        }

        list.add(link);
    }

    /**
     * Adds bidirectional travel between two route nodes.
     *
     * @param links destination adjacency map
     * @param start first node
     * @param end second node
     * @param road owning road, or null for free flight
     * @param type travel type
     */
    private void connect(
        Map<Graph.Node, List<Link>> links, Graph.Node start,
        Graph.Node end, Highways.Road road, Route.Type type) {

        if (start.equals(end)) return;
        float distance = this.distance(start.pos(), end.pos());

        this.add(links, new Link(start, end, road, type, distance));
        this.add(links, new Link(end, start, road, type, distance));
    }

    //endregion

    //region Route reconstruction

    /**
     * Reconstructs the selected route from Dijkstra predecessors.
     *
     * @param start route start node
     * @param end route destination node
     * @param previous predecessor links
     * @param highway highway travel speed
     * @param normal standard travel speed
     * @return reconstructed route
     */
    private Route route(Graph.Node start, Graph.Node end,
        Map<Graph.Node, Link> previous, float highway, float normal) {

        List<Link> path = new ArrayList<>();
        Graph.Node node = end;

        while (!node.equals(start)) {
            Link link = previous.get(node);
            if (link == null) {
                return this.direct(start.pos(), end.pos(), normal);
            }

            path.add(link);
            node = link.start;
        }

        Collections.reverse(path);
        List<Route.Leg> legs = new ArrayList<>();

        float distance = 0.0F;
        float time = 0.0F;

        for (Link link : path) {
            distance += link.distance;
            time += link.distance / (link.type ==
                Route.Type.Highway ? highway : normal
            );

            legs.add(new Route.Leg(link.start.pos(),
                link.end.pos(), link.road, link.type
            ));
        }

        return new Route(legs, distance, time);
    }

    /**
     * Creates a direct standard route.
     *
     * @param start starting position
     * @param end destination position
     * @param speed standard speed
     * @return direct route
     */
    private Route direct(Vec2f start, Vec2f end, float speed) {
        float distance = this.distance(start, end);

        return new Route(List.of(
            new Route.Leg(start, end, null, Route.Type.Normal)
        ), distance, distance / speed);
    }

    /**
     * Calculates distance between two positions.
     *
     * @param first first position
     * @param second second position
     * @return distance between both positions
     */
    private float distance(Vec2f first, Vec2f second) {
        return second.add(first.negate()).length();
    }

    //endregion

    //region Data structures

    /**
     * Represents a directional connection used during route calculation.
     *
     * @param start starting node
     * @param end ending node
     * @param road highway used by the connection, or null for free flight
     * @param type travel type
     * @param distance connection distance
     */
    private record Link(
        Graph.Node start, Graph.Node end,
        Highways.Road road, Route.Type type, float distance
    ) {}

    /**
     * Represents the temporary graph used for a route calculation.
     *
     * @param start starting node
     * @param end destination node
     * @param links graph connections
     */
    private record Network(
        Graph.Node start, Graph.Node end,
        Map<Graph.Node, List<Link>> links
    ) {}

    /**
     * Represents a node queued during pathfinding.
     *
     * @param node graph node
     * @param cost accumulated travel time
     */
    private record State(Graph.Node node, float cost) {}

    //endregion
}
