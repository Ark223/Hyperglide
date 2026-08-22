package dev.arkieee.hyperglide.navigation;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

public record Segment(Vec2f start, Vec2f end) {
    private static final float epsilon = 1.0E-4F;

    /**
     * Calculates the segment length.
     *
     * @return distance between segment endpoints
     */
    public float length() {
        return this.vector().length();
    }

    /**
     * Returns the segment direction vector.
     *
     * @return vector from start to end
     */
    public Vec2f vector() {
        return this.end.add(this.start.negate());
    }

    /**
     * Returns the normalized segment direction.
     *
     * @return normalized direction, or zero for an empty segment
     */
    public Vec2f unit() {
        Vec2f vector = this.vector();
        float length = vector.length();

        if (length <= epsilon) return Vec2f.ZERO;
        return vector.multiply(1.0F / length);
    }

    /**
     * Checks whether another segment continues in the same direction.
     *
     * @param other segment to compare
     * @return true when both directions are parallel and forward
     */
    public boolean forward(Segment other) {
        Vec2f first = this.vector();
        Vec2f second = other.vector();

        float scale = first.length() * second.length();
        return Math.abs(cross(first, second)) <= epsilon *
            Math.max(1.0F, scale) && first.dot(second) > 0.0F;
    }

    /**
     * Returns a clamped point along the segment.
     *
     * @param ratio normalized position along the segment
     * @return point on the segment
     */
    public Vec2f point(float ratio) {
        return this.line(MathHelper.clamp(ratio, 0.0F, 1.0F));
    }

    /**
     * Returns a point along the infinite segment line.
     *
     * @param ratio normalized position along the line
     * @return point on the segment line
     */
    public Vec2f line(float ratio) {
        return this.start.add(this.vector().multiply(ratio));
    }

    /**
     * Calculates the normalized projection onto the segment line.
     *
     * @param point point to project
     * @return normalized projection along the line
     */
    public float projection(Vec2f point) {
        Vec2f vector = this.vector();

        float length = vector.lengthSquared();
        if (length == 0.0F) return 0.0F;

        Vec2f offset = point.add(this.start.negate());
        return offset.dot(vector) / length;
    }

    /**
     * Checks whether a point lies on the segment.
     *
     * @param point point to check
     * @return true when the point lies on the segment
     */
    public boolean contains(Vec2f point) {
        Vec2f vector = this.vector();
        Vec2f offset = point.add(this.start.negate());

        float scale = Math.max(1.0F, Math.abs(vector.x) + Math.abs(vector.y));
        if (Math.abs(cross(offset, vector)) > epsilon * scale) return false;

        float dot = offset.dot(vector);
        return dot >= -epsilon && dot <= vector.lengthSquared() + epsilon;
    }

    /**
     * Checks whether another segment lies on the same infinite line.
     *
     * @param other segment to compare
     * @return true when both segments are collinear
     */
    public boolean collinear(Segment other) {
        Vec2f vector = this.vector();
        Vec2f delta = other.vector();

        if (Math.abs(cross(vector, delta)) > epsilon) {
            return false;
        }

        Vec2f offset = other.start.add(this.start.negate());
        float scale = Math.abs(vector.x) + Math.abs(vector.y);
        scale = Math.max(1.0F, scale);

        return Math.abs(cross(offset, vector)) <= epsilon * scale;
    }

    /**
     * Finds the single intersection point with another segment.
     *
     * @param other segment to intersect
     * @return intersection point, or null when none or overlapping
     */
    public Vec2f intersection(Segment other) {
        Vec2f vector = this.vector();
        Vec2f delta = other.vector();

        float cross = cross(vector, delta);
        if (Math.abs(cross) <= epsilon) return null;

        Vec2f offset = other.start.add(this.start.negate());

        float ratio = cross(offset, delta) / cross;
        float second = cross(offset, vector) / cross;

        if (ratio < -epsilon || ratio > 1.0F + epsilon ||
            second < -epsilon || second > 1.0F + epsilon) {
            return null;
        }

        return this.point(ratio);
    }

    /**
     * Calculates the 2D cross product magnitude.
     *
     * @param first first vector
     * @param second second vector
     * @return signed cross product magnitude
     */
    public static float cross(Vec2f first, Vec2f second) {
        return first.x * second.y - first.y * second.x;
    }
}
