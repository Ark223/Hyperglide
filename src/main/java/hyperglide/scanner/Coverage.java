package hyperglide.scanner;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.ChunkPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.LongPredicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Selects portal locations that cover nearby valid skips.
 */
public final class Coverage {
    private static final int maximum = 25;
    private static final int minimum = 2;

    private static final long[] space = offsets(5, 9);

    private final LongOpenHashSet spots = new LongOpenHashSet();

    private final PriorityQueue<Spot> queue = new PriorityQueue<>((a, b) -> {
        int order = Integer.compare(b.gain(), a.gain());
        return order != 0 ? order : Long.compare(a.key(), b.key());
    });

    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "portal-coverage");
        thread.setDaemon(true);
        return thread;
    });

    private CompletableFuture<Result> task;
    private Result result;

    //region Task processing

    /**
     * Returns the latest completed coverage result.
     *
     * @return selected spots, or null before completion
     */
    public Result result() {
        return this.result;
    }

    /**
     * Clears pending work and previously selected spots.
     */
    public void reset() {
        this.task = null;
        this.result = null;
    }

    /**
     * Schedules coverage using nearby valid skip positions.
     *
     * @param scores published skip scores
     * @param center center chunk of the search area
     */
    public void update(Long2ByteMap scores, ChunkPos center) {
        if (this.task != null && !this.task.isDone()) {
            return;
        }

        if (this.task != null) {
            Result done = this.task.join();
            if (done != null) this.result = done;
        }

        this.task = CompletableFuture.supplyAsync(() ->
            this.build(scores, center), this.worker
        ).exceptionally(error -> { return null; });
    }

    //endregion

    //region Area construction

    /**
     * Creates the square search area around a chunk.
     *
     * @param center center chunk of the search area
     * @return local square search area
     */
    private Area area(ChunkPos center) {
        return new Area(
            center.x - maximum, center.z - maximum,
            center.x + maximum, center.z + maximum
        );
    }

    /**
     * Finds portal spots near the selected chunk.
     *
     * @param skips valid portal skip positions
     * @param center center chunk of the search area
     * @return selected portal spots and coverage statistics
     */
    public Result build(LongSet skips, ChunkPos center) {
        return this.build(skips, this.area(center));
    }

    /**
     * Finds portal spots from nearby skip scores.
     *
     * @param scores published skip scores
     * @param center center chunk of the search area
     * @return selected spots and coverage statistics
     */
    public Result build(Long2ByteMap scores, ChunkPos center) {
        LongPredicate func = key -> scores.get(key) >= minimum;
        return this.build(func, this.area(center));
    }

    /**
     * Finds portal spots within the specified area.
     *
     * @param skips valid portal skip positions
     * @param area search area for candidate spots
     * @return selected portal spots and coverage statistics
     */
    public Result build(LongSet skips, Area area) {
        return this.build(skips::contains, area);
    }

    /**
     * Collects and selects valid skips within the area.
     *
     * @param valid candidate membership check
     * @param area search area for candidate spots
     * @return selected spots and coverage statistics
     */
    private Result build(LongPredicate valid, Area area) {
        this.spots.clear();
        this.queue.clear();

        for (int cx = area.x1(); cx <= area.x2(); cx++) {
            for (int cz = area.z1(); cz <= area.z2(); cz++) {
                long key = ChunkPos.toLong(cx, cz);
                if (valid.test(key)) this.spots.add(key);
            }
        }

        this.insert();
        return this.select();
    }

    /**
     * Queues candidates by their initial coverage gains.
     */
    private void insert() {
        for (long key : this.spots) {
            this.queue.add(new Spot(key, this.count(key)));
        }
    }

    //endregion

    //region Chunk selection

    /**
     * Chooses portal spots until no skips remain.
     *
     * @return selected spots and coverage statistics
     */
    private Result select() {
        List<Spot> spots = new ArrayList<>();

        int total = this.spots.size();
        int covered = 0;

        while (!this.spots.isEmpty()) {
            Spot spot = this.next();
            if (spot == null) break;

            int gain = this.cover(spot.key());
            spots.add(new Spot(spot.key(), gain));
            covered += gain;
        }

        List<Spot> candidates = List.copyOf(spots);
        return new Result(candidates, total, covered);
    }

    /**
     * Finds the strongest candidate and refreshes its gain.
     *
     * @return next useful location, or null if none remains
     */
    private Spot next() {
        while (!this.queue.isEmpty()) {
            Spot spot = this.queue.poll();
            if (!this.spots.contains(spot.key())) {
                continue;
            }

            int gain = this.count(spot.key());
            if (gain < spot.gain()) {
                this.queue.add(new Spot(spot.key(), gain));
                continue;
            }

            if (gain <= 0) return null;
            return new Spot(spot.key(), gain);
        }

        return null;
    }

    //endregion

    //region Portal coverage

    /**
     * Counts nearby portal skips not yet covered.
     *
     * @param key proposed portal location
     * @return number of uncovered skips nearby
     */
    private int count(long key) {
        int px = ChunkPos.getPackedX(key);
        int pz = ChunkPos.getPackedZ(key);
        int gain = 0;

        for (long offset : space) {
            int cx = px + (int) offset;
            int cz = pz + (int) (offset >> 32);

            long pos = ChunkPos.toLong(cx, cz);
            if (this.spots.contains(pos)) gain++;
        }

        return gain;
    }

    /**
     * Removes skips covered by the selected portal.
     *
     * @param key selected portal location
     * @return number of newly covered skips
     */
    private int cover(long key) {
        int px = ChunkPos.getPackedX(key);
        int pz = ChunkPos.getPackedZ(key);
        int gain = 0;

        for (long offset : space) {
            int cx = px + (int) offset;
            int cz = pz + (int) (offset >> 32);

            long pos = ChunkPos.toLong(cx, cz);
            if (this.spots.remove(pos)) gain++;
        }

        return gain;
    }

    /**
     * Creates the coverage area around each portal.
     *
     * @param radius maximum offset along either axis
     * @param limit maximum distance from the center
     * @return packed relative chunk positions
     */
    private static long[] offsets(int radius, int limit) {
        int width = radius * 2 + 1, index = 0;
        long[] result = new long[width * width];

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int length = Math.abs(dx) + Math.abs(dz);
                if (length > limit) continue;

                long key = ChunkPos.toLong(dx, dz);
                result[index++] = key;
            }
        }

        return Arrays.copyOf(result, index);
    }

    //endregion

    //region Search results

    /**
     * Stores a portal position and its coverage gain.
     *
     * @param key packed portal position
     * @param gain number of newly covered skips
     */
    public record Spot(long key, int gain) {}

    /**
     * Stores selected spots and their coverage statistics.
     *
     * @param spots selected portal spots in greedy order
     * @param skips number of skips inside the search area
     * @param covered number of represented skips
     */
    public record Result(List<Spot> spots, int skips, int covered) {

        /**
         * Calculates the percentage of covered skip positions.
         *
         * @return coverage percentage, or zero when no skips
         */
        public double percent() {
            if (this.skips == 0) return 0.0;
            return this.covered * 100.0 / this.skips;
        }
    }

    //endregion
}
