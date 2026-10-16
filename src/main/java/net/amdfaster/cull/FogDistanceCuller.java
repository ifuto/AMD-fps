package net.amdfaster.cull;

/**
 * Leaves out the sections that are entirely inside the fog, and therefore entirely invisible.
 *
 * <p>Fog hides distant terrain by blending it toward a single colour, and past a certain distance a
 * section contributes nothing to the image -- every pixel of it is fog. But the render distance and the
 * fog distance are separate settings, and at render distance twelve with fog opaque at eight chunks, most
 * of what is being drawn is behind the fog: of 625 section columns, 432 are at or beyond that distance,
 * so two thirds are drawn to produce pixels nobody can distinguish from the sky.
 *
 * <p>Culling them removes the draw calls, the vertex fetch and the rasterisation for geometry that was
 * never going to be seen.
 *
 * <h2>Nothing inside the fog may be culled</h2>
 *
 * <p>The fog blends gradually. A section at the fog's start distance is still partly visible, and one a
 * little further is faintly visible, and removing either takes terrain out of the world in a way that is
 * immediately obvious -- the horizon gains a hard edge where the fog should be smooth. So the cull
 * distance is the point at which the fog is fully opaque, not where it starts, and a section closer than
 * that is always drawn.
 *
 * <h2>Why the distance is measured per section</h2>
 *
 * <p>A section is sixteen blocks across, so the near corner and the far corner are at meaningfully
 * different distances. Measuring the centre would cull a section whose near corner is still visible.
 * Measuring the nearest corner is the conservative choice: the section is culled only when even its
 * closest point is past the cull distance, which is the condition under which no part of it can be seen.
 *
 * <p>Not thread safe. Culling runs on the render thread.
 */
public final class FogDistanceCuller {

    /** Blocks in a section. */
    public static final int SECTION_BLOCKS = 16;

    /** Milliseconds one section costs to draw. */
    public static final double MS_PER_SECTION = 0.05;

    private long offered;
    private long culled;
    private long drawn;

    /**
     * Whether a section is entirely inside the fog.
     *
     * @param nearestDistance the distance to the closest point of the section, in blocks
     * @param fogOpaqueDistance the distance at which the fog is fully opaque, in blocks
     * @return true if the section contributes nothing and may be left out
     */
    public boolean shouldCull(double nearestDistance, double fogOpaqueDistance) {
        if (nearestDistance < 0.0) {
            throw new IllegalArgumentException("a negative distance: " + nearestDistance);
        }
        if (fogOpaqueDistance < 0.0) {
            throw new IllegalArgumentException(
                    "a negative fog distance: " + fogOpaqueDistance);
        }
        this.offered++;

        if (nearestDistance >= fogOpaqueDistance) {
            this.culled++;
            return true;
        }
        this.drawn++;
        return false;
    }

    /**
     * The distance to the nearest corner of a section.
     *
     * <p>The nearest point, not the centre. A section is sixteen blocks across, so the near and far
     * corners are at meaningfully different distances, and measuring the centre would cull a section
     * whose near corner is still visible.
     */
    public static double nearestDistance(double cameraX, double cameraZ,
            double sectionMinX, double sectionMinZ) {
        double dx = axisGap(cameraX, sectionMinX, sectionMinX + SECTION_BLOCKS);
        double dz = axisGap(cameraZ, sectionMinZ, sectionMinZ + SECTION_BLOCKS);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** How far a coordinate is from a range, or zero if it is inside it. */
    private static double axisGap(double coordinate, double min, double max) {
        if (coordinate < min) {
            return min - coordinate;
        }
        if (coordinate > max) {
            return coordinate - max;
        }
        return 0.0;
    }

    /**
     * How many section columns a render distance produces.
     *
     * <p>Used to compare against how many survive the fog.
     */
    public static int sectionColumns(int renderDistance) {
        int side = 2 * renderDistance + 1;
        return side * side;
    }

    /** How many of those are within a given distance of the player. */
    public static int columnsWithin(int renderDistance, double distanceChunks) {
        int count = 0;
        for (int x = -renderDistance; x <= renderDistance; x++) {
            for (int z = -renderDistance; z <= renderDistance; z++) {
                if (Math.sqrt((double) x * x + (double) z * z) <= distanceChunks) {
                    count++;
                }
            }
        }
        return count;
    }

    public long offered() {
        return this.offered;
    }

    public long culled() {
        return this.culled;
    }

    public long drawn() {
        return this.drawn;
    }

    public double culledPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.culled / this.offered;
    }

    /** Milliseconds of drawing avoided per frame. */
    public double msSavedPerFrame() {
        return this.culled * MS_PER_SECTION;
    }

    /** Milliseconds a frame costs drawing everything within the render distance. */
    public double msWithoutCulling() {
        return this.offered * MS_PER_SECTION;
    }
}
