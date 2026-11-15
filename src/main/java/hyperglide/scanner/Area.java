package hyperglide.scanner;

import net.minecraft.util.math.ChunkPos;

/**
 * Represents visible chunk bounds.
 *
 * @param x1 first X chunk coordinate
 * @param z1 first Z chunk coordinate
 * @param x2 last X chunk coordinate
 * @param z2 last Z chunk coordinate
 */
public record Area(int x1, int z1, int x2, int z2) {
    private static final int border = 3750000;

    /**
     * Checks whether a chunk lies inside the bounds.
     *
     * @param key packed chunk position
     * @return true when the chunk is inside
     */
    public boolean contains(long key) {
        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);

        return cx >= this.x1 && cx <= this.x2
            && cz >= this.z1 && cz <= this.z2;
    }

    /**
     * Returns the default nether chunk bounds.
     *
     * @return full default chunk area
     */
    public static Area defaults() {
        int edge = border >> 4;
        return new Area(-edge, -edge, edge, edge);
    }

    /**
     * Returns the single chunk at the origin.
     *
     * @return an area containing a zero chunk
     */
    public static Area empty() {
        return new Area(0, 0, 0, 0);
    }
}
