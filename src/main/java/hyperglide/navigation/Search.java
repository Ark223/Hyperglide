package hyperglide.navigation;

import hyperglide.navigation.Route.Leg;
import net.minecraft.util.math.Vec2f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

public final class Search {
    private static final float epsilon = 0.05F;

    private final Graph graph;

    //region Public API

    /**
     * Builds a search over the complete highway network.
     */
    public Search() {
        this(Highways.roads());
    }

    /**
     * Builds a search over the supplied highway network.
     *
     * @param roads roads available for routing
     */
    public Search(List<Highways.Road> roads) {
        this.graph = new Graph(roads);
    }

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
        Route undefined = new Route(List.of(), 0.0F, 0.0F);

        if (this.distance(start, end) <= epsilon) return undefined;
        if (highway <= normal) return this.direct(start, end, normal);

        Network network = this.build(start, end, highway, normal);
        Map<Graph.Node, Link> previous = this.search(network, highway, normal);
        return this.route(network.start, network.end, previous, highway, normal);
    }

    //endregion

    //region Graph preparation

    /**
     * Adds optimal entry and exit points to the highway graph.
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

            this.attach(links, points, source, this.candidates(
                nodes, segment, start, highway, normal
            ));

            this.attach(links, points, target, this.candidates(
                nodes, segment, end, highway, normal
            ));

            this.highway(links, points, segment, edge.road());
        }

        return new Network(source, target, links);
    }

    /**
     * Calculates both approach points on a highway edge.
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

        float divisor = highway * highway - normal * normal;
        divisor = (float) Math.sqrt(divisor);

        float height = this.distance(point, projection);
        float shift = height * normal / divisor / length;

        Graph.Node first = this.node(nodes, segment.point(ratio - shift));
        Graph.Node second = this.node(nodes, segment.point(ratio + shift));

        return first.equals(second) ? List.of(first) : List.of(first, second);
    }

    /**
     * Connects approach points to an off-highway position.
     *
     * @param links temporary graph connections
     * @param points highway segment points
     * @param source off-highway position
     * @param candidates calculated approach points
     */
    private void attach(Map<Graph.Node, List<Link>> links, List<Graph.Node>
        points, Graph.Node source, List<Graph.Node> candidates) {

        for (Graph.Node point : candidates) {
            this.add(points, point);
            this.connect(links, source, point, null, Route.Type.Normal);
        }
    }

    /**
     * Connects ordered points along a highway segment.
     *
     * @param links temporary graph connections
     * @param points points belonging to the segment
     * @param segment original highway segment
     * @param road highway containing the segment
     */
    private void highway(Map<Graph.Node, List<Link>> links,
        List<Graph.Node> points, Segment segment, Highways.Road road) {

        points.sort(Comparator.comparingDouble(
            point -> segment.projection(point.pos())
        ));

        for (int idx = 1; idx < points.size(); idx++) {
            this.connect(links, points.get(idx - 1),
                points.get(idx), road, Route.Type.Highway
            );
        }
    }

    //endregion

    //region Graph representation

    /**
     * Returns a nearby node or creates a temporary one.
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
     * Adds a candidate node once to a temporary point list.
     *
     * @param nodes destination node list
     * @param candidate node to add
     */
    private void add(List<Graph.Node> nodes, Graph.Node candidate) {
        if (nodes.stream().noneMatch(node -> node ==
            candidate || node.equals(candidate))) {
            nodes.add(candidate);
        }
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

    /**
     * Adds a route link unless an equivalent link already exists.
     *
     * @param links destination adjacency map
     * @param link route link to add
     */
    private void add(Map<Graph.Node, List<Link>> links, Link link) {
        Function<Graph.Node, List<Link>> create = key -> new ArrayList<>();

        List<Link> list = links.computeIfAbsent(link.start, create);
        if (!this.duplicate(list, link)) list.add(link);
    }

    /**
     * Checks whether an equivalent connection already exists.
     *
     * @param links existing connections
     * @param link connection to check
     * @return true if an equivalent connection exists
     */
    private boolean duplicate(List<Link> links, Link link) {
        if (link.type == Route.Type.Normal) return false;

        for (Link current : links) {
            if (current.end.equals(link.end) &&
                current.road == link.road && current.type == link.type &&
                Math.abs(current.distance - link.distance) <= epsilon) {
                return true;
            }
        }

        return false;
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

    //region Route pathfinding

    /**
     * Finds the fastest connections through the network.
     *
     * @param network temporary routing graph
     * @param highway highway travel speed
     * @param normal standard travel speed
     * @return predecessor links for the fastest route
     */
    private Map<Graph.Node, Link> search(
        Network network, float highway, float normal) {

        Map<Graph.Node, Float> costs = new HashMap<>();
        Map<Graph.Node, Link> previous = new HashMap<>();

        Graph.Node start = network.start, goal = network.end;
        float time = this.estimate(start, goal, highway);

        PriorityQueue<State> queue = new PriorityQueue<>(
            Comparator.comparingDouble(State::priority)
        );

        queue.add(new State(start, 0.0F, time));
        costs.put(start, 0.0F);

        while (!queue.isEmpty()) {
            State state = queue.poll();
            float known = costs.getOrDefault(state.node, Float.POSITIVE_INFINITY);

            if (state.cost > known + epsilon) continue;
            if (state.node.equals(goal)) break;

            for (Link link : network.links.getOrDefault(state.node, List.of())) {
                float speed = link.type == Route.Type.Highway ? highway : normal;
                float cost = state.cost + link.distance / speed;

                float old = costs.getOrDefault(link.end, Float.POSITIVE_INFINITY);
                if (cost >= old - epsilon) continue;

                costs.put(link.end, cost);
                previous.put(link.end, link);

                time = this.estimate(link.end, goal, highway);
                queue.add(new State(link.end, cost, cost + time));
            }
        }

        return previous;
    }

    /**
     * Estimates the minimum remaining travel time to a goal.
     *
     * @param node current graph node
     * @param end destination graph node
     * @param speed maximum travel speed
     * @return estimated remaining travel time
     */
    private float estimate(Graph.Node node, Graph.Node end, float speed) {
        return this.distance(node.pos(), end.pos()) / speed;
    }

    //endregion

    //region Route reconstruction

    /**
     * Reconstructs the selected route from search predecessors.
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

        List<Link> path = null;
        if ((path = this.path(start, end, previous)) == null) {
            return this.direct(start.pos(), end.pos(), normal);
        }

        float distance = 0.0F, time = 0.0F;
        List<Route.Leg> legs = new ArrayList<>();

        for (Link link : path) {
            distance += link.distance;

            boolean faster = link.type == Route.Type.Highway;
            time += link.distance / (faster ? highway : normal);

            legs.add(new Route.Leg(link.start.pos(),
                link.end.pos(), link.road, link.type
            ));
        }

        return new Route(legs, distance, time);
    }

    /**
     * Traces the fastest route through predecessor links.
     *
     * @param start route starting node
     * @param end route destination node
     * @param previous predecessor links
     * @return ordered links, or null if unreachable
     */
    private List<Link> path(Graph.Node start,
        Graph.Node end, Map<Graph.Node, Link> previous) {

        List<Link> path = new ArrayList<>();
        Graph.Node node = end;

        while (!node.equals(start)) {
            Link link = previous.get(node);
            if (link == null) return null;

            path.add(link);
            node = link.start;
        }

        Collections.reverse(path);
        return path;
    }

    /**
     * Creates a direct standard route using a normal speed.
     *
     * @param start starting position
     * @param end destination position
     * @param speed standard speed
     * @return direct route
     */
    private Route direct(Vec2f start, Vec2f end, float speed) {
        float distance = this.distance(start, end);
        Leg leg = new Route.Leg(start, end, null, Route.Type.Normal);
        return new Route(List.of(leg), distance, distance / speed);
    }

    //endregion

    //region Data structures

    /**
     * Represents a connection used during route calculation.
     *
     * @param start starting node
     * @param end ending node
     * @param road highway, or null for free flight
     * @param type travel type
     * @param distance connection distance
     */
    private record Link(Graph.Node start, Graph.Node end,
        Highways.Road road, Route.Type type, float distance
    ) {}

    /**
     * Represents the graph used for a route calculation.
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
     * @param priority estimated total travel time
     */
    private record State(
        Graph.Node node, float cost, float priority
    ) {}

    //endregion
}
