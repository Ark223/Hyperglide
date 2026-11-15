package hyperglide.scanner;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import xaeroplus.XaeroPlus;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Loads saved nether chunks and prepares their portal skips.
 */
public final class Loader {
    private static Path world;
    private static FutureTask<Result> task;
    private static boolean loaded, failed;

    //region Core lifecycle

    /**
     * Checks whether the background load is running.
     *
     * @return true when an import is pending
     */
    public boolean pending() {
        return task != null;
    }

    /**
     * Checks whether the saved chunks are ready to use.
     *
     * @return true when the chunks are ready
     */
    public boolean ready() {
        if (!loaded) return false;

        Database database = Database.current();
        if (database == null) return false;

        return world.equals(database.folder());
    }

    /**
     * Cancels loading while preserving cached chunks.
     */
    public static void cancel() {
        if (task != null) task.cancel(true);
        task = null;
    }

    /**
     * Resets the state and cancels pending loading.
     */
    public static void reset() {
        cancel();
        clear();
    }

    /**
     * Clears the current world and loading status.
     */
    private static void clear() {
        world = null;
        loaded = false;
        failed = false;
    }

    //endregion

    //region Chunk loading

    /**
     * Starts loading a world unless its data is saved.
     *
     * @param database current world database
     * @return true when the world has changed
     */
    public boolean start(Database database) {
        Path folder = database.folder();

        boolean changed = world != null && !world.equals(folder);
        if (changed) reset();

        world = folder;

        if (!loaded && !failed && task == null) {
            ChatUtils.info("Loading nether chunks...");
            task = new FutureTask<>(() -> load(database));

            Thread thread = new Thread(task, "chunk-loader");
            thread.setDaemon(true);
            thread.start();
        }

        return changed;
    }

    /**
     * Makes finished chunks and portal skips available.
     *
     * @return prepared data, or null when unavailable
     */
    public Result drain() {
        if (task == null || !task.isDone()) {
            return null;
        }

        FutureTask<Result> done = task;
        task = null;

        try {
            Result data = done.get();
            ChatUtils.info("Chunks successfully loaded.");
            loaded = true;
            return data;
        } catch (CancellationException ignored) {
            return null;
        } catch (ExecutionException error) {
            failed = true;
            XaeroPlus.LOGGER.warn("Error!", error.getCause());
        } catch (InterruptedException error) {
            failed = true;
            Thread.currentThread().interrupt();
        }

        return null;
    }

    /**
     * Loads the chunk flags, then calculates portal skips.
     *
     * @param database selected world database
     * @return prepared classifications and skip scores
     * @throws SQLException when a database cannot be read
     * @throws InterruptedException when loading is cancelled
     */
    private static Result load(Database database)
        throws SQLException, InterruptedException {

        Skipper skipper = new Skipper();
        Long2ByteOpenHashMap chunks = database.load();

        ChatUtils.info("Calculating portal skips...");

        int mask = Xaero.Type.Legacy.mask | Xaero.Type.Modern.mask;
        skipper.filter(chunks.long2ByteEntrySet(), mask);

        return new Result(chunks, skipper, skipper.scores());
    }

    /**
     * Stores completed source flags and portal skips.
     *
     * @param chunks original chunk flags
     * @param skipper prepared skip calculator
     * @param scores published skip scores
     */
    public record Result(Long2ByteOpenHashMap chunks,
        Skipper skipper, Long2ByteOpenHashMap scores) {}

    //endregion
}
