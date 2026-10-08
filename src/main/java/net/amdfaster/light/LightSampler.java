package net.amdfaster.light;

/**
 * Where {@link LightCache} reads light and opacity from, in world coordinates.
 *
 * <p>An interface for the same reason {@code VoxelView} is one: the lighting algorithm is the part
 * worth testing, and it cannot be tested if it reaches into a Minecraft {@code Level} for its
 * input. The real implementation reads a chunk's light sections; a test returns values from an
 * array.
 */
public interface LightSampler {

    /** Sky light at {@code (x,y,z)}, 0..15. */
    int sky(int x, int y, int z);

    /** Block light at {@code (x,y,z)}, 0..15. */
    int block(int x, int y, int z);

    /**
     * True when the block at {@code (x,y,z)} blocks light and occludes the corner behind it.
     *
     * <p>This drives ambient occlusion, so it has to mean "visually solid" rather than "has a
     * collision box": glass blocks light but must not darken a corner, and a slab fills its block
     * while leaving the corner open. Getting this wrong is the most visible lighting bug there is,
     * because it shows up as black smudges in every corner of the world.
     */
    boolean occludes(int x, int y, int z);
}
