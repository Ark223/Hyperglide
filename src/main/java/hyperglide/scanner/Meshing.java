package hyperglide.scanner;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.ChunkPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/**
 * Merges neighboring map cells into cached rectangles.
 */
public final class Meshing {
    private final ArrayList<Rect> rects = new ArrayList<>();

    private final IntUnaryOperator select;
    private final IntUnaryOperator colors;
    private volatile boolean dirty = true;

    private Area drawn;
    private int step = 1;

    //region Public interface

    /**
     * Creates a mesh using the supplied classification and color.
     *
     * @param select selects the classification from chunk flags
     * @param colors returns the packed color for a classification
     */
    public Meshing(IntUnaryOperator select, IntUnaryOperator colors) {
        this.select = select;
        this.colors = colors;
    }

    /**
     * Clears all cached visible rectangles.
     */
    public void reset() {
        this.rects.clear();
        this.dirty = true;
        this.drawn = null;
    }

    /**
     * Invalidates the mesh when a visible chunk changes.
     *
     * @param key packed chunk position
     */
    public void changed(long key) {
        if (this.drawn != null && this.drawn.contains(key)) {
            this.dirty = true;
        }
    }

    /**
     * Draws adjacent chunks as merged rectangles.
     *
     * @param context draw context
     * @param area visible chunk bounds
     * @param chunks saved chunk classifications
     * @param sx screen X origin
     * @param sz screen Y origin
     * @param pixels pixels occupied by one map cell
     * @param step chunks covered by one map cell
     */
    public void draw(DrawContext context, Area area,
        Long2ByteMap chunks, int sx, int sz, int pixels, int step) {

        if (area == null) return;

        if (!area.equals(this.drawn) ||
            this.dirty || step != this.step) {
            this.build(area, chunks, step);
        }

        for (Rect rect : this.rects) {
            int px = sx + rect.cx * pixels;
            int pz = sz + rect.cz * pixels;

            int width = rect.width * pixels;
            int height = rect.height * pixels;

            int color = this.colors.applyAsInt(rect.mask);
            context.fill(px, pz, px + width, pz + height, color);
        }
    }

    //endregion

    //region Rectangle meshing

    /**
     * Builds the mesh for the visible chunk area.
     *
     * @param area visible chunk bounds
     * @param chunks saved chunk classifications
     * @param step chunks represented by one map pixel
     */
    private void build(Area view, Long2ByteMap chunks, int step) {
        this.rects.clear();

        Area area = new Area(
            Math.floorDiv(view.x1(), step),
            Math.floorDiv(view.z1(), step),
            Math.floorDiv(view.x2(), step),
            Math.floorDiv(view.z2(), step)
        );

        int diff = area.x2() - area.x1();
        Rect[] above = new Rect[diff + 1];
        Rect[] below = new Rect[diff + 1];

        for (int z = area.z1(); z <= area.z2(); z++) {
            this.runs(area, z, chunks, above, below, step);

            Rect[] swap = above;
            above = below;
            below = swap;

            Arrays.fill(below, null);
        }

        this.dirty = false;
        this.drawn = view;
        this.step = step;
    }

    /**
     * Finds matching horizontal runs in one chunk row.
     *
     * @param area visible chunk bounds
     * @param row current chunk row
     * @param chunks saved chunk classifications
     * @param above rectangles from the previous row
     * @param below rectangles from the current row
     * @param step chunks represented by one map pixel
     */
    private void runs(Area area, int row, Long2ByteMap chunks,
        Rect[] above, Rect[] below, int step) {

        for (int col = area.x1(); col <= area.x2();) {
            int next = col + 1, mask = this.select.applyAsInt(
                chunks.get(ChunkPos.toLong(col * step, row * step))
            );

            while (next <= area.x2() && this.select.applyAsInt(chunks.get(
                ChunkPos.toLong(next * step, row * step))) == mask) {
                next++;
            }

            if (mask != 0) {
                int index = col - area.x1(), len = next - col;
                below[index] = this.merge(above[index], col, row, len, mask);
            }

            col = next;
        }
    }

    /**
     * Extends a matching rectangle into the current row.
     *
     * @param above rectangle from the previous row
     * @param cx first chunk X coordinate
     * @param cz current chunk row
     * @param width matching run width
     * @param mask selected classification
     * @return merged rectangle
     */
    private Rect merge(Rect above, int cx, int cz, int width, int mask) {
        if (above != null && above.width == width && above.mask == mask) {
            above.height++;
            return above;
        }

        Rect rect = new Rect(cx, cz, width, mask);
        this.rects.add(rect);
        return rect;
    }

    //endregion

    //region Data structures

    /**
     * Represents neighboring cells with one chunk flag.
     */
    private static final class Rect {
        private final int cx, cz, width, mask;
        private int height = 1;

        /**
         * Creates a rectangle for a run of matching chunks.
         *
         * @param cx first X chunk coordinate
         * @param cz first Z chunk coordinate
         * @param width width in chunks
         * @param mask visible chunk flag
         */
        private Rect(int cx, int cz, int width, int mask) {
            this.cx = cx;
            this.cz = cz;
            this.width = width;
            this.mask = mask;
        }
    }

    //endregion
}
