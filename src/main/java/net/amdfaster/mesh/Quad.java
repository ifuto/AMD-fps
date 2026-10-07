package net.amdfaster.mesh;

/**
 * One axis-aligned quad, as produced by greedy meshing.
 *
 * <p>Because every quad is axis-aligned, two opposite corners plus the plane coordinate describe
 * it completely; the four vertices and their winding are derived. Keeping the input this small
 * matters because the whole point of greedy meshing is to emit as few quads as possible, and the
 * builder then has to hash and deduplicate their corners.
 *
 * <p>Coordinates are section-local and in the range 0..16, so they fit in 5 bits.
 *
 * @param orientation which way the quad faces, and therefore which bucket it lands in
 * @param x0,y0,z0    first corner, component-wise minimum
 * @param x1,y1,z1    opposite corner, component-wise maximum
 * @param u0,v0       texture coordinate at the minimum corner of the two varying axes
 * @param u1,v1       texture coordinate at the maximum corner; may lie outside 0..1 when a greedy
 *                    quad spans several blocks and the texture is tiled
 * @param color       packed ARGB, uniform across the quad
 * @param light       packed lightmap value, uniform across the quad
 */
public record Quad(
        Orientation orientation,
        int x0, int y0, int z0,
        int x1, int y1, int z1,
        float u0, float v0,
        float u1, float v1,
        int color,
        int light
) {

    /** Vertices per quad. Minecraft quads are never split, so this is a constant. */
    public static final int VERTICES = 4;

    /** Triangles per quad. */
    public static final int TRIANGLES = 2;

    /**
     * X coordinate of vertex {@code i} in 0..3.
     *
     * <p>The winding is not arbitrary: {@link WindingTest} asserts that the geometric normal
     * computed from the emitted vertex order equals the declared {@link #orientation()} normal, so
     * the front face is the outside of the block.
     */
    public int cornerX(int i) {
        Orientation o = this.orientation;
        return switch (o.axis()) {
            case 0 -> o.isPositive() ? this.x1 : this.x0;
            case 1 -> o.cornerA(i) == 1 ? this.x1 : this.x0;
            default -> o.cornerA(i) == 1 ? this.x1 : this.x0;
        };
    }

    public int cornerY(int i) {
        Orientation o = this.orientation;
        return switch (o.axis()) {
            case 0 -> o.cornerA(i) == 1 ? this.y1 : this.y0;
            case 1 -> o.isPositive() ? this.y1 : this.y0;
            default -> o.cornerB(i) == 1 ? this.y1 : this.y0;
        };
    }

    public int cornerZ(int i) {
        Orientation o = this.orientation;
        return switch (o.axis()) {
            case 0 -> o.cornerB(i) == 1 ? this.z1 : this.z0;
            case 1 -> o.cornerB(i) == 1 ? this.z1 : this.z0;
            default -> o.isPositive() ? this.z1 : this.z0;
        };
    }

    public float cornerU(int i) {
        return this.orientation.cornerA(i) == 1 ? this.u1 : this.u0;
    }

    public float cornerV(int i) {
        return this.orientation.cornerB(i) == 1 ? this.v1 : this.v0;
    }

    /** A quad with no area is degenerate and must never reach a meshlet. */
    public boolean isDegenerate() {
        return switch (this.orientation.axis()) {
            case 0 -> this.y0 == this.y1 || this.z0 == this.z1;
            case 1 -> this.x0 == this.x1 || this.z0 == this.z1;
            default -> this.x0 == this.x1 || this.y0 == this.y1;
        };
    }
}
