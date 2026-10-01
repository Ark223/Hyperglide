package hyperglide.navigation;

import net.minecraft.util.math.Vec2f;
import java.util.List;

public final class Clipper {
    private static final float epsilon = 1.0E-4F;

    private Clipper() {}

    /**
     * Removes the part of a segment inside the given radius.
     *
     * @param segment segment to clip
     * @param radius radius around the origin
     * @return remaining segment parts
     */
    public static List<Segment> cut(Segment segment, float radius) {
        if (radius <= 0.0F) return List.of(segment);

        float[] range = range(segment, radius);
        if (range == null) return List.of(segment);

        float low = range[0];
        float high = range[1];

        boolean before = low > epsilon;
        boolean after = high < 1.0F - epsilon;

        if (before && after) {
            return List.of(
                new Segment(segment.start(), segment.line(low)),
                new Segment(segment.line(high), segment.end())
            );
        }

        if (before) {
            return List.of(
                new Segment(segment.start(), segment.line(low))
            );
        }

        if (after) {
            return List.of(
                new Segment(segment.line(high), segment.end())
            );
        }

        return List.of();
    }

    /**
     * Finds the part of a segment inside the given radius.
     *
     * @param segment segment to check
     * @param radius radius around the origin
     * @return normalized range, or null when outside
     */
    private static float[] range(Segment segment, float radius) {
        Vec2f vector = segment.vector();

        float length = vector.lengthSquared();
        float squared = radius * radius;

        if (length <= epsilon) {
            return segment.start().dot(segment.start()) <
                squared ? new float[] {0.0F, 1.0F} : null;
        }

        float xb = 2.0F * segment.start().dot(vector);
        float xc = segment.start().dot(segment.start()) - squared;

        float delta = xb * xb - 4.0F * length * xc;
        if (delta <= epsilon) return null;

        float root = (float) Math.sqrt(delta);

        float first = (-xb - root) / (2.0F * length);
        float second = (-xb + root) / (2.0F * length);

        float low = Math.max(0.0F, first);
        float high = Math.min(1.0F, second);

        return high > low + epsilon
            ? new float[] {low, high} : null;
    }
}
