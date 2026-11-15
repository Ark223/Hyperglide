package hyperglide.scanner;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.math.ChunkPos;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Calculates portal skip scores from nether terrain.
 */
public final class Skipper {
    private static final int radius = 33;
    private static final int spacing = 11;

    private static final int legacy = Xaero.Type.Legacy.mask;
    private static final int modern = Xaero.Type.Modern.mask;
    private static final int portal = Xaero.Type.Portal.mask;
    private static final int flowing = Xaero.Type.Flowing.mask;

    private static final int terrain = legacy | modern;
    private static final int signal = flowing | portal;

    private static final long[] forward = {
        ChunkPos.toLong(-1, -1), ChunkPos.toLong(0, -1),
        ChunkPos.toLong(1, -1), ChunkPos.toLong(-1, 0)
    };

    private static final long[] backward = {
        ChunkPos.toLong(1, 1), ChunkPos.toLong(0, 1),
        ChunkPos.toLong(-1, 1), ChunkPos.toLong(1, 0)
    };

    private static final long[] neighbors = offsets(1, false);
    private static final long[] circle = offsets(spacing, true);

    private final Long2ByteOpenHashMap signals = new Long2ByteOpenHashMap();
    private final Long2IntOpenHashMap influence = new Long2IntOpenHashMap();
    private final Long2ByteOpenHashMap distances = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap scores = new Long2ByteOpenHashMap();

    private Long2ByteOpenHashMap delta = new Long2ByteOpenHashMap();
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

    private int mask = terrain;
    private boolean prepared;
    private boolean updated;

    //region Public API

    /**
     * Sets the default squared distance limit.
     */
    public Skipper() {
        this.distances.defaultReturnValue((byte) radius);
    }

    /**
     * Clears saved terrain and calculated scores.
     */
    public void clear() {
        this.signals.clear();
        this.influence.clear();
        this.distances.clear();
        this.scores.clear();

        this.delta.clear();
        this.chunks.clear();
        this.dirty.clear();
        this.queue.clear();
        this.queued.clear();

        this.prepared = false;
        this.updated = false;
    }

    /**
     * Calculates initial scores from saved chunks.
     *
     * @param source original chunk flags
     * @param mask allowed terrain flags
     */
    public void filter(ObjectSet<Entry> source, int mask) {
        this.clear();
        this.mask = mask & terrain;

        for (Entry entry : source) {
            this.check();
            long key = entry.getLongKey();
            byte value = entry.getByteValue();
            this.classify(key, value);
        }

        this.populate();
        this.distance();

        for (long key : this.chunks) {
            this.check();
            this.refresh(key);
        }

        this.prepared = true;
    }

    /**
     * Processes changes to the tracked chunks.
     *
     * @param changes latest flags for changed chunks
     * @return true when any published score changed
     */
    public boolean update(Long2ByteMap changes) {
        if (!this.prepared) return false;

        this.updated = false;
        boolean changed = false;

        for (Entry entry : changes.long2ByteEntrySet()) {
            this.check();
            long key = entry.getLongKey();
            byte value = entry.getByteValue();
            changed |= this.change(key, value);
        }

        if (changed) this.finish();
        return this.updated;
    }

    /**
     * Returns a copy of the scores from portal skips.
     *
     * @return copied scores
     */
    public Long2ByteOpenHashMap scores() {
        return this.scores.clone();
    }

    /**
     * Returns the pending score changes from portal skips.
     *
     * @return changed scores, with zero indicating removal
     */
    public Long2ByteOpenHashMap drain() {
        Long2ByteOpenHashMap result = this.delta;
        this.delta = new Long2ByteOpenHashMap();
        return result;
    }

    //endregion

    //region Chunk tracking

    /**
     * Records terrain and nearby signal sources.
     *
     * @param key packed chunk position
     * @param flags original chunk flags
     */
    private void classify(long key, int flags) {
        if (this.selected(flags)) this.chunks.add(key);

        int bits = flags & signal;
        if (bits != 0) this.signals.put(key, (byte) bits);
    }

    /**
     * Checks whether a chunk is usable.
     *
     * @param flags original chunk flags
     * @return true when terrain is eligible
     */
    private boolean selected(int flags) {
        int mask = flags & Xaero.Type.Recent.mask;
        return mask == 0 && (flags & this.mask) != 0;
    }

    /**
     * Applies updated terrain and signal flags.
     *
     * @param key packed chunk position
     * @param flags latest classification
     * @return true when terrain or signal state changed
     */
    private boolean change(long key, int flags) {
        boolean changed = this.signal(key, flags);
        boolean selected = this.selected(flags);

        boolean contains = this.chunks.contains(key);
        if (contains == selected) return changed;

        if (selected) this.add(key);
        else this.remove(key);
        return true;
    }

    /**
     * Adds a chunk to the distance calculation.
     *
     * @param key added chunk position
     */
    private void add(long key) {
        this.chunks.add(key);
        this.count(key);

        if (!this.open(key)) {
            this.put(key, 1);
            return;
        }

        this.dirty.add(key);
        this.enqueue(key);
        this.adjacent(key);
    }

    /**
     * Removes a chunk and its calculated scores.
     *
     * @param key removed chunk position
     */
    private void remove(long key) {
        boolean open = this.open(key);

        this.influence.remove(key);
        this.distances.remove(key);

        this.chunks.remove(key);
        this.dirty.remove(key);

        this.erase(key);
        if (open) this.adjacent(key);
    }

    /**
     * Updates distances and pending scores.
     */
    private void finish() {
        this.propagate();

        for (long key : this.dirty) {
            this.check();
            this.refresh(key);
        }

        this.dirty.clear();
    }
    //endregion

    //region Signals and scoring

    /**
     * Counts signal sources around tracked terrain.
     */
    private void populate() {
        for (Entry entry : this.signals.long2ByteEntrySet()) {
            this.check();
            this.adjust(entry.getLongKey(), entry.getByteValue(), 1);
        }
    }

    /**
     * Updates the flow and portal flags for a chunk.
     *
     * @param key packed chunk position
     * @param flags latest chunk flags
     * @return true when signal membership changed
     */
    private boolean signal(long key, int flags) {
        int old = this.signals.get(key);
        int next = flags & signal;
        if (old == next) return false;

        if (next == 0) this.signals.remove(key);
        else this.signals.put(key, (byte) next);

        this.adjust(key, old & ~next, -1);
        this.adjust(key, next & ~old, 1);
        return true;
    }

    /**
     * Updates signal influence on nearby chunks.
     *
     * @param key signal position
     * @param bits changed signal bits
     * @param change addition or removal direction
     */
    private void adjust(long key, int bits, int change) {
        if (bits == 0) return;

        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);

        int delta = ((bits & flowing) != 0 ? change : 0)
            + ((bits & portal) != 0 ? change << 16 : 0);

        for (long offset : circle) {
            long pos = position(cx, cz, offset);
            if (!this.chunks.contains(pos)) continue;

            int old = this.influence.get(pos);
            int next = old + delta;

            if (next == 0) this.influence.remove(pos);
            else this.influence.put(pos, next);

            if (!this.prepared) continue;

            boolean before = (old >>> 16) == 0;
            boolean after = (next >>> 16) == 0;

            if (before != after) {
                this.wall(pos, after);
            } else if (after && changed(old, next)) {
                this.dirty.add(pos);
            }
        }
    }

    /**
     * Counts nearby signals for a new chunk.
     *
     * @param key terrain position
     */
    private void count(long key) {
        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);
        int total = 0;

        for (long offset : circle) {
            long pos = position(cx, cz, offset);
            int bits = this.signals.get(pos);

            if ((bits & flowing) != 0) total++;
            if ((bits & portal) != 0) total += 1 << 16;
        }

        if (total != 0) this.influence.put(key, total);
    }

    /**
     * Updates a chunk's portal coverage.
     *
     * @param key affected chunk position
     * @param open whether this chunk is usable again
     */
    private void wall(long key, boolean open) {
        if (open) {
            this.dirty.add(key);
            this.enqueue(key);
        } else {
            this.distances.remove(key);
            this.dirty.remove(key);
            this.put(key, 1);
        }
        this.adjacent(key);
    }

    /**
     * Checks whether a chunk is uncovered.
     *
     * @param key packed chunk position
     * @return true when the chunk can hold a skip
     */
    private boolean open(long key) {
        if (!this.chunks.contains(key)) return false;
        return (this.influence.get(key) >>> 16) == 0;
    }

    /**
     * Checks whether signal presence has changed.
     *
     * @param old previous influence count
     * @param next updated influence count
     * @return true when the result changes
     */
    private static boolean changed(int old, int next) {
        return (old == 0) != (next == 0);
    }

    /**
     * Recalculates a chunk's portal skip score.
     *
     * @param key terrain position
     */
    private void refresh(long key) {
        if (!this.chunks.contains(key)) return;

        int count = this.influence.get(key);
        int dist = this.distances.get(key);

        int score = Math.max(1, dist - 1);
        if (count != 0) score = 1;

        this.put(key, score);
    }

    /**
     * Saves changed scores for publication.
     *
     * @param key terrain position
     * @param score new score
     */
    private void put(long key, int score) {
        byte value = (byte) score;

        if (this.scores.get(key) != value) {
            this.scores.put(key, value);
            this.updated = true;

            if (!this.prepared) return;
            this.delta.put(key, value);
        }
    }

    /**
     * Removes a score and records the change.
     *
     * @param key terrain position
     */
    private void erase(long key) {
        if (this.scores.remove(key) != 0) {
            this.updated = true;

            if (!this.prepared) return;
            this.delta.put(key, (byte) 0);
        }
    }

    //endregion

    //region Distance transform

    /**
     * Calculates square distances using two passes.
     */
    private void distance() {
        this.distances.clear();
        this.queue.clear();
        this.queued.clear();

        int min = Integer.MIN_VALUE, index = 0;
        long[] order = new long[this.chunks.size()];

        for (long key : this.chunks) {
            int px = ChunkPos.getPackedX(key);
            int pz = ChunkPos.getPackedZ(key);
            order[index++] = ((long) pz << 32) |
                Integer.toUnsignedLong(px ^ min);
        }

        Arrays.sort(order);
        for (long key : order) {
            this.check();
            this.scan(key, forward);
        }

        int size = order.length - 1;
        for (int idx = size; idx >= 0; idx--) {
            this.check();
            this.scan(order[idx], backward);
        }
    }

    /**
     * Updates distances in one sorted pass.
     *
     * @param key sorted chunk position
     * @param offsets neighboring positions to check
     */
    private void scan(long key, long[] offsets) {
        int cx = ((int) key) ^ Integer.MIN_VALUE;
        int cz = (int) (key >> 32);

        long pos = ChunkPos.toLong(cx, cz);
        if (!this.open(pos)) return;

        int next = this.distances.get(pos);
        for (long offset : offsets) {
            long other = position(cx, cz, offset);
            if (!this.open(other)) {next = 0; break;}

            byte length = this.distances.get(other);
            next = Math.min(next, length + 1);
        }

        if (next >= radius) return;
        this.distances.put(pos, (byte) next);
    }

    /**
     * Recalculates distances around changed terrain.
     */
    private void propagate() {
        while (!this.queue.isEmpty()) {
            this.check();

            long key = this.dequeue();
            if (!this.open(key)) continue;

            int old = this.distances.get(key);
            int next = this.measure(key);
            if (old == next) continue;

            if (next == radius) this.distances.remove(key);
            else this.distances.put(key, (byte) next);

            int count = this.influence.get(key);
            if (count == 0) this.dirty.add(key);

            this.adjacent(key);
        }
    }

    /**
     * Measures the square radius around a chunk.
     *
     * @param key terrain position
     * @return new capped square radius
     */
    private int measure(long key) {
        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);
        int smallest = radius;

        for (long offset : neighbors) {
            long pos = position(cx, cz, offset);
            if (!this.open(pos)) return 0;

            int dist = this.distances.get(pos);
            smallest = Math.min(smallest, dist);
        }

        return Math.min(radius, smallest + 1);
    }

    /**
     * Queues adjacent chunks for recalculation.
     *
     * @param key changed chunk position
     */
    private void adjacent(long key) {
        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);

        for (long offset : neighbors) {
            long pos = position(cx, cz, offset);
            if (this.open(pos)) this.enqueue(pos);
        }
    }

    /**
     * Queues a chunk once for recalculation.
     *
     * @param key terrain position
     */
    private void enqueue(long key) {
        if (this.queued.add(key)) this.queue.enqueue(key);
    }

    /**
     * Takes the next chunk waiting to be checked.
     *
     * @return packed chunk position
     */
    private long dequeue() {
        long key = this.queue.dequeueLong();
        this.queued.remove(key);
        return key;
    }

    //endregion

    //region Chunk coordinates

    /**
     * Calculates a chunk position from an offset.
     *
     * @param cx chunk X coordinate
     * @param cz chunk Z coordinate
     * @param offset packed coordinate offset
     * @return shifted chunk position
     */
    private static long position(int cx, int cz, long offset) {
        int px = cx + (int) offset;
        int pz = cz + (int) (offset >> 32);
        return ChunkPos.toLong(px, pz);
    }

    /**
     * Creates reusable chunk offsets for repeated checks.
     *
     * @param radius neighborhood radius
     * @param circular true for a circle
     * @return packed relative offsets
     */
    private static long[] offsets(int radius, boolean circular) {
        LongArrayList data = new LongArrayList();

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (!circular && dx == 0 && dz == 0) {
                    continue;
                }

                int length = dx * dx + dz * dz;
                if (!circular || length <= radius * radius) {
                    data.add(ChunkPos.toLong(dx, dz));
                }
            }
        }

        return data.toLongArray();
    }

    /**
     * Aborts work when the thread is interrupted.
     */
    private void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    //endregion
}
