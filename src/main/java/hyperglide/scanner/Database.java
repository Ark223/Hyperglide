package hyperglide.scanner;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.util.math.ChunkPos;
import xaero.map.WorldMap;
import xaero.map.core.XaeroWorldMapCore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reads nether chunk information from Xaero databases.
 */
public final class Database {
    private static final String query =
        "SELECT x, z FROM \"minecraft:the_nether\"";

    private static final String lava =
        "SELECT x, z FROM \"minecraft:" +
        "the_nether\" WHERE foundTime >= 10";

    private static final String exists =
        "SELECT 1 FROM sqlite_master WHERE type = " +
        "'table' AND name = 'minecraft:the_nether'";

    private final Path folder;

    /**
     * Creates a reader for the given world directory.
     *
     * @param folder directory containing the databases
     */
    private Database(Path folder) {
        this.folder = folder;
    }

    //region Public interface

    /**
     * Returns the current Xaero world directory.
     *
     * @return database directory
     */
    public Path folder() {
        return this.folder;
    }

    /**
     * Finds the databases for the current Xaero world.
     *
     * @return current database, or null when unavailable
     */
    public static Database current() {
        var session = XaeroWorldMapCore.currentSession;
        if (session == null) return null;

        var processor = session.getMapProcessor();
        if (processor == null) return null;

        String name = processor.getCurrentWorldId();
        if (name == null || name.isBlank()) return null;

        Path path = WorldMap.saveFolder.toPath();
        Path folder = path.resolve(name).toAbsolutePath();

        return new Database(folder.normalize());
    }

    /**
     * Combines the saved chunk types into one map.
     *
     * @return combined chunk flags
     * @throws SQLException when a database cannot be read
     * @throws InterruptedException when loading is cancelled
     */
    public Long2ByteOpenHashMap load()
        throws SQLException, InterruptedException {

        try {
            Class.forName("org.rfresh.sqlite.JDBC");
        } catch (ClassNotFoundException error) {
            throw new SQLException("Driver unavailable!", error);
        }

        Long2ByteOpenHashMap data = new Long2ByteOpenHashMap();

        this.read(data, "XaeroPlusOldChunks.db", Xaero.Type.Legacy.mask);
        this.read(data, "XaeroPlusModernChunks.db", Xaero.Type.Modern.mask);
        this.read(data, "XaeroPlusPaletteNewChunks.db", Xaero.Type.Recent.mask);
        this.read(data, "XaeroPlusLavaColumns.db", Xaero.Type.Column.mask, lava);
        this.read(data, "XaeroPlusPortals.db", Xaero.Type.Portal.mask);

        return data;
    }

    //endregion

    //region Database reading

    /**
     * Adds chunk flags without losing classifications.
     *
     * @param data collected chunk flags
     * @param rows current database row
     * @param mask flag being inserted
     * @throws SQLException if the row cannot be read
     */
    private void insert(Long2ByteOpenHashMap data, ResultSet rows, byte mask)
        throws SQLException {
        long key = ChunkPos.toLong(rows.getInt(1), rows.getInt(2));
        data.put(key, (byte) (data.get(key) | mask));
    }

    /**
     * Inserts flags from database to the map structure.
     *
     * @param data destination chunk map
     * @param name database filename
     * @param mask classification flag
     * @throws SQLException when the database cannot be read
     * @throws InterruptedException when loading is cancelled
     */
    private void read(Long2ByteOpenHashMap data, String name, byte mask)
        throws SQLException, InterruptedException {
        this.read(data, name, mask, query);
    }

    /**
     * Adds matching saved chunks without losing flags.
     *
     * @param data destination chunk map
     * @param name database filename
     * @param mask classification flag
     * @param query SQL query selecting matching chunks
     * @throws SQLException when the database cannot be read
     * @throws InterruptedException when loading is cancelled
     */
    private void read(Long2ByteOpenHashMap data,
        String name, byte mask, String query)
        throws SQLException, InterruptedException {

        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        Path path = this.folder.resolve(name);
        if (!Files.isRegularFile(path)) return;

        String url = "jdbc:rfresh_sqlite:" + path.toUri() + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(url);
            Statement statement = connection.createStatement()) {

            try (ResultSet tables = statement.executeQuery(exists)) {
                if (!tables.next()) return;
            }

            try (ResultSet rows = statement.executeQuery(query)) {
                while (rows.next()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    } else {
                        this.insert(data, rows, mask);
                    }
                }
            }
        }
    }

    //endregion
}
