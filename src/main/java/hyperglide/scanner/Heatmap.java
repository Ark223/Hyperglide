package hyperglide.scanner;

import net.minecraft.util.math.MathHelper;

/**
 * Colors portal skip scores using three customizable colors.
 */
public final class Heatmap {
    private final int[] colors = new int[64];
    private static int neutral = 0xFFFFFF;

    private static final int maximum = 63;
    private static final int minimum = 6;

    private int limit, cold, mild, warm;
    private boolean prepared;

    /**
     * Prepares the palette when its settings change.
     *
     * @param limit square width that reaches maximum heat
     * @param cold color for the lowest scores
     * @param mild color for the middle scores
     * @param warm color for the highest scores
     */
    public void palette(int limit, int cold, int mild, int warm) {
        if (this.matches(limit, cold, mild, warm)) return;

        this.limit = limit;
        this.cold = cold;
        this.mild = mild;
        this.warm = warm;

        this.generate();
        this.prepared = true;
    }

    /**
     * Checks whether a palette uses the requested settings.
     *
     * @param limit maximum heat threshold
     * @param cold low-score color
     * @param mild middle-score color
     * @param warm high-score color
     * @return true when no colors need updating
     */
    private boolean matches(int limit, int cold, int mild, int warm) {
        if (!this.prepared) return false;

        return this.limit == limit && this.cold == cold
            && this.mild == mild && this.warm == warm;
    }

    /**
     * Creates the color for every possible heat score.
     */
    private void generate() {
        int length = this.colors.length;

        for (int width = minimum; width < length; width++) {
            int value = Math.min(width, this.limit) - minimum;

            float score = (float) (value) / (this.limit - minimum);
            this.colors[width] = 0xE0000000 | this.gradient(score);
        }
    }

    /**
     * Returns the color of a chunk's precomputed heat score.
     *
     * @param width raw skip score, or zero for empty space
     * @return color, or transparent for empty space
     */
    public int color(int width) {
        int score = MathHelper.clamp(width, minimum, maximum);
        return width != 0 ? this.colors[score] : 0;
    }

    /**
     * Blends the selected colors through a neutral midpoint.
     *
     * @param score heat level between zero and one
     * @return packed RGB color
     */
    private int gradient(float score) {
        if (score < 0.35f) {
            return blend(this.cold, neutral, score / 0.35f);
        } else if (score < 0.55f) {
            return blend(neutral, this.mild, (score - 0.35f) / 0.20f);
        } else {
            return blend(this.mild, this.warm, (score - 0.55f) / 0.45f);
        }
    }

    /**
     * Blends colors by interpolating between their components.
     *
     * @param first starting color
     * @param second ending color
     * @param amount amount of the second color, between zero and one
     * @return packed RGB color between the two inputs
     */
    private static int blend(int first, int second, float amount) {
        int result = 0;

        for (int shift = 0; shift <= 16; shift += 8) {
            int start = (first >>> shift) & 255;
            int end = (second >>> shift) & 255;

            float value = start + (end - start) * amount;
            result |= Math.round(value) << shift;
        }

        return result;
    }
}
