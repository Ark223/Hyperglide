package hyperglide.scanner;

import hyperglide.utilities.Client;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluids;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkBlockUpdateEvent;
import xaeroplus.event.ChunkBlocksUpdateEvent;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.event.Phase;
import xaeroplus.feature.highlights.ChunkHighlightCache;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.LavaColumns;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;
import xaeroplus.module.impl.Portals;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongConsumer;

/**
 * Tracks nether chunks and portal skip scores.
 */
public final class Xaero {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static Long2ByteOpenHashMap changes = new Long2ByteOpenHashMap();
    private static Long2ByteOpenHashMap loading = new Long2ByteOpenHashMap();

    private static Long2ByteOpenHashMap chunks = new Long2ByteOpenHashMap();
    private static volatile Long2ByteMap scores = new Long2ByteOpenHashMap();

    private static Xaero tracker;
    private static Skipper skipper;
    private static Future<Update> task;
    private static ExecutorService worker;

    private final Loader loader = new Loader();

    private Runnable completion;
    private LongConsumer listener;
    private RegistryKey<World> world;

    private PaletteNewChunks newchunks;
    private OldChunks oldchunks;
    private LavaColumns columns;
    private Portals portals;

    /**
     * Defines the stored chunk classification flags.
     */
    public enum Type {
        Unknown(0),
        Legacy(1),
        Modern(2),
        Recent(4),
        Flowing(8),
        Column(16),
        Portal(32);

        public final byte mask;

        /**
         * Creates a flag with its bit mask.
         *
         * @param mask flag bit
         */
        Type(int mask) {
            this.mask = (byte) mask;
        }
    }

    //region Event handlers

    /**
     * Records newly processed chunk data.
     *
     * @param event incoming chunk data
     */
    @EventHandler(priority = -1)
    public void chunk(ChunkDataEvent event) {
        this.update(event.chunk().getPos(), false);
    }

    /**
     * Records changes from individual block updates.
     *
     * @param event block update event
     */
    @EventHandler(priority = -1)
    public void block(ChunkBlockUpdateEvent event) {
        if (event.phase() != Phase.POST) return;
        this.update(event.packet());
    }

    /**
     * Records changes from grouped block updates.
     *
     * @param event grouped block update event
     */
    @EventHandler(priority = -1)
    public void blocks(ChunkBlocksUpdateEvent event) {
        if (event.phase() != Phase.POST) return;
        this.update(event);
    }

    //endregion

    //region Core lifecycle

    /**
     * Starts tracking changes to nether chunks.
     *
     * @param listener action to run when a chunk changes
     */
    public void start(LongConsumer listener) {
        this.start(listener, null);
    }

    /**
     * Starts tracking and loading nether chunks.
     *
     * @param listener action to run when a chunk changes
     * @param completion action to run after loading
     */
    public void start(LongConsumer listener, Runnable completion) {
        if (!Client.nether()) return;

        if (tracker != null && tracker != this) {
            tracker.release();
        }

        if (this.world == null) {
            this.world = client.world.getRegistryKey();

            this.newchunks = ModuleManager.getModule(PaletteNewChunks.class);
            this.oldchunks = ModuleManager.getModule(OldChunks.class);
            this.columns = ModuleManager.getModule(LavaColumns.class);
            this.portals = ModuleManager.getModule(Portals.class);

            tracker = this;
            XaeroPlus.EVENT_BUS.register(this);
        }

        this.completion = completion;
        this.listener = listener;
        this.tick();
    }

    /**
     * Stops notifications while keeping tracker active.
     */
    public void stop() {
        this.completion = null;
        this.listener = null;
    }

    /**
     * Collects completed work and schedules updates.
     */
    public void tick() {
        if (!this.active()) return;

        Database database = Database.current();
        if (database == null) return;

        if (this.loader.start(database)) {
            chunks = new Long2ByteOpenHashMap();
            discard();
        }

        this.drain();
        this.flush();
    }

    /**
     * Publishes the loaded chunks and scores.
     */
    public void drain() {
        var data = this.loader.drain();
        if (data == null) return;

        Long2ByteOpenHashMap pending = loading;
        loading = new Long2ByteOpenHashMap();
        discard();

        chunks = data.chunks();
        skipper = data.skipper();
        scores = data.scores();

        for (Long2ByteMap.Entry entry : pending.long2ByteEntrySet()) {
            this.record(entry.getLongKey(), entry.getByteValue());
        }

        if (this.completion != null) {
            this.completion.run();
        }
    }

    /**
     * Resets chunk tracking and saved scores.
     */
    public static void reset() {
        if (tracker != null) tracker.release();

        Loader.reset();
        discard();

        chunks = new Long2ByteOpenHashMap();
    }

    /**
     * Releases chunk tracking and event listeners.
     */
    private void release() {
        if (this.world != null) this.register();
        if (tracker == this) tracker = null;

        this.completion = null;
        this.listener = null;
        this.world = null;

        this.newchunks = null;
        this.oldchunks = null;
        this.columns = null;
        this.portals = null;
    }

    /**
     * Unregisters this tracker from Xaero events.
     */
    private void register() {
        XaeroPlus.EVENT_BUS.unregister(this);
    }

    //endregion

    //region Public utilities

    /**
     * Returns the saved nether chunk flags.
     *
     * @return recorded chunk flags
     */
    public Long2ByteMap chunks() {
        return chunks;
    }

    /**
     * Returns the latest published skip scores.
     *
     * @return published skip scores
     */
    public Long2ByteMap scores() {
        return scores;
    }

    /**
     * Checks whether database loading is pending.
     *
     * @return true when an import is pending
     */
    public boolean pending() {
        return this.loader.pending();
    }

    /**
     * Checks whether nether chunks are ready.
     *
     * @return true when chunk flags are ready
     */
    public boolean ready() {
        return this.loader.ready();
    }

    /**
     * Reads the latest flags from Xaero.
     *
     * @param key packed chunk position
     * @return detected chunk flags
     */
    public byte get(long key) {
        if (!this.active()) return 0;

        byte flags = 0;
        ChunkPos pos = new ChunkPos(key);

        if (this.legacy(pos)) flags |= Type.Legacy.mask;
        if (this.modern(pos)) flags |= Type.Modern.mask;
        if (this.recent(pos)) flags |= Type.Recent.mask;
        if (this.column(pos)) flags |= Type.Column.mask;
        if (this.portal(pos)) flags |= Type.Portal.mask;

        return flags;
    }

    /**
     * Checks a chunk for the requested flag.
     *
     * @param key packed chunk position
     * @param type flag to check
     * @return true when the flag is present
     */
    public boolean has(long key, Type type) {
        byte flags = chunks.get(key);
        if (flags == 0) flags = this.get(key);

        if (type != Type.Unknown) {
            return (flags & type.mask) != 0;
        } else {
            return flags == 0;
        }
    }

    /**
     * Checks whether a chunk is within the set radius.
     *
     * @param pos chunk position
     * @param radius radius in chunks
     * @return true when the chunk is nearby
     */
    public boolean near(ChunkPos pos, int radius) {
        if (client.player == null) return false;

        ChunkPos center = client.player.getChunkPos();
        return Math.abs(pos.x - center.x) <= radius
            && Math.abs(pos.z - center.z) <= radius;
    }

    /**
     * Checks whether a chunk is within the set bounds.
     *
     * @param pos chunk position
     * @param x1 first X chunk coordinate
     * @param y1 first Z chunk coordinate
     * @param x2 second X chunk coordinate
     * @param y2 second Z chunk coordinate
     * @return true when the chunk is inside the bounds
     */
    public boolean within(ChunkPos pos, int x1, int y1, int x2, int y2) {
        return pos.x >= Math.min(x1, x2) && pos.x <= Math.max(x1, x2)
            && pos.z >= Math.min(y1, y2) && pos.z <= Math.max(y1, y2);
    }

    //endregion

    //region Skip calculation

    /**
     * Publishes finished batches and starts new updates.
     */
    private void flush() {
        if (!this.available()) return;

        if (skipper != null && !changes.isEmpty()) {
            this.submit();
        }
    }

    /**
     * Checks whether background updates are ready.
     *
     * @return true when another update can start
     */
    private boolean available() {
        if (task == null) return true;
        return task.isDone() && this.collect();
    }

    /**
     * Collects a completed background score update.
     *
     * @return false when the client thread is interrupted
     */
    private boolean collect() {
        Future<Update> done = task;
        task = null;

        if (done.isCancelled()) return true;

        try {
            this.publish(done.get());
        } catch (ExecutionException error) {
            XaeroPlus.LOGGER.warn("Error!", error.getCause());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }

        return true;
    }

    /**
     * Starts a background batch of score updates.
     */
    private void submit() {
        Long2ByteOpenHashMap batch = changes;
        changes = new Long2ByteOpenHashMap();

        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, "skip-updater");
                thread.setDaemon(true);
                return thread;
            });
        }

        Skipper current = skipper;
        task = worker.submit(() -> this.update(current, batch));
    }

    /**
     * Applies updated scores to the published map.
     *
     * @param result update containing score changes
     */
    private void publish(Update result) {
        if (result.skipper() != skipper) return;
        Long2ByteOpenHashMap batch = result.changes();

        for (var entry : batch.long2ByteEntrySet()) {
            long key = entry.getLongKey();
            byte value = entry.getByteValue();

            if (value == 0) scores.remove(key);
            else scores.put(key, value);

            if (this.listener != null) {
                this.listener.accept(key);
            }
        }
    }

    /**
     * Cancels pending work and clears stored changes.
     */
    private static void discard() {
        if (task != null) task.cancel(true);
        task = null;

        if (worker != null) worker.shutdownNow();
        worker = null;

        changes.clear();
        loading.clear();

        scores = new Long2ByteOpenHashMap();
        skipper = null;
    }

    /**
     * Calculates score changes using the thread worker.
     *
     * @param current active skipper instance
     * @param batch pending chunk changes
     * @return completed score changes
     */
    private Update update(Skipper current, Long2ByteMap batch) {
        current.update(batch);
        return new Update(current, current.drain());
    }

    //endregion

    //region Chunk classification

    /**
     * Checks whether Xaero detected legacy chunk generation.
     *
     * @param pos chunk position
     * @return true when the chunk was classified as legacy
     */
    private boolean legacy(ChunkPos pos) {
        if (this.oldchunks == null) return false;
        return this.oldchunks.isOldChunk(pos.x, pos.z, this.world);
    }

    /**
     * Checks whether Xaero detected newer chunk generation.
     *
     * @param pos chunk position
     * @return true when the chunk was classified as modern
     */
    private boolean modern(ChunkPos pos) {
        if (this.oldchunks == null) return false;
        return this.oldchunks.isOldChunkInverse(pos.x, pos.z, this.world);
    }

    /**
     * Checks whether Xaero detected newly generated terrain.
     *
     * @param pos chunk position
     * @return true when the chunk was newly generated
     */
    private boolean recent(ChunkPos pos) {
        if (this.newchunks == null) return false;
        return this.newchunks.isNewChunk(pos.x, pos.z, this.world);
    }

    /**
     * Checks whether Xaero recorded a tall lava column.
     *
     * @param pos chunk position
     * @return true when a tall lava column was detected
     */
    private boolean column(ChunkPos pos) {
        if (this.columns == null) return false;

        ChunkHighlightCache cache = this.columns.lavaColumnsCache.get();
        return cache.getCacheMap(this.world).get(pos.toLong()) >= 10;
    }

    /**
     * Checks whether Xaero recorded a portal in the chunk.
     *
     * @param pos chunk position
     * @return true when a portal was detected
     */
    private boolean portal(ChunkPos pos) {
        if (this.portals == null) return false;
        return this.portals.isPortalChunk(pos.x, pos.z, this.world);
    }

    //endregion

    //region Update manager

    /**
     * Updates chunk flags from a single block update.
     *
     * @param packet incoming block update
     */
    private void update(BlockUpdateS2CPacket packet) {
        ChunkPos pos = new ChunkPos(packet.getPos());
        this.update(pos, this.flowing(packet.getState()));
    }

    /**
     * Saves the latest chunk flags, even while loading.
     *
     * @param pos updated chunk position
     * @param flow whether flowing lava was observed
     */
    private void update(ChunkPos pos, boolean flow) {
        if (!active()) return;

        long key = pos.toLong();
        byte flags = this.get(key);

        byte mask = Type.Flowing.mask;
        if (flow) flags |= mask;

        if (this.ready()) {
            this.record(key, flags);
        } else if (loader.pending()) {
            byte prior = (byte) (loading.get(key) & mask);
            loading.put(key, (byte) (flags | prior));
        }
    }

    /**
     * Processes block changes received in a single update.
     *
     * @param event grouped block updates
     */
    private void update(ChunkBlocksUpdateEvent event) {
        Long2ByteOpenHashMap updates = new Long2ByteOpenHashMap();

        event.packet().visitUpdates((pos, state) -> {
            long key = new ChunkPos(pos).toLong();
            byte mask = this.flowing(state) ? Type.Flowing.mask : 0;
            updates.put(key, (byte) (updates.get(key) | mask));
        });

        for (Long2ByteMap.Entry entry : updates.long2ByteEntrySet()) {
            ChunkPos pos = new ChunkPos(entry.getLongKey());
            this.update(pos, entry.getByteValue() != 0);
        }
    }

    /**
     * Records changed flags and notifies listeners.
     *
     * @param key packed chunk position
     * @param flags detected flags
     */
    private void record(long key, byte flags) {
        boolean known = chunks.containsKey(key);
        if (!known && flags == 0) return;

        byte portal = Type.Portal.mask;
        byte flowing = Type.Flowing.mask;
        byte prior = known ? chunks.get(key) : flags;

        byte next = (byte) (prior & ~portal);
        next |= (flags & (portal | flowing));
        if (known && prior == next) return;

        chunks.put(key, next);
        changes.put(key, next);

        if (this.listener != null) {
            this.listener.accept(key);
        }
    }

    /**
     * Checks whether the tracked dimension is loaded.
     *
     * @return true when tracking can continue
     */
    private boolean active() {
        return this.world != null && Client.loaded()
            && this.world.equals(client.world.getRegistryKey());
    }

    /**
     * Checks whether a block contains flowing lava.
     *
     * @param state block state
     * @return true when the block contains flowing lava
     */
    private boolean flowing(BlockState state) {
        return state.getFluidState().isOf(Fluids.FLOWING_LAVA);
    }

    //endregion

    //region Data structures

    /**
     * Stores a completed batch of score changes.
     *
     * @param skipper worker that calculated the scores
     * @param changes updated scores
     */
    private record Update(
        Skipper skipper, Long2ByteOpenHashMap changes
    ) {}

    //endregion
}
