package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;

/**
 * The block data the mesher reads. Deliberately free of any Minecraft type so the greedy meshing
 * algorithm can be exercised on synthetic volumes in a unit test.
 *
 * <p>Coordinates are local to the volume; anything outside it is air. A real caller backs this
 * with a chunk section plus the one-block border from its neighbours, which is what makes faces on
 * a section boundary correct rather than spuriously emitted.
 */
public interface VoxelView {

    /** The {@link #key} value that means a face produces no geometry. */
    int NO_GEOMETRY = 0;

    int sizeX();

    int sizeY();

    int sizeZ();

    /**
     * True when the voxel at {@code (x,y,z)} hides the faces of its neighbours.
     *
     * <p>Out of bounds must return {@code false}, so the volume's own boundary still emits faces.
     */
    boolean isOpaque(int x, int y, int z);

    /**
     * The merge key for the face of the voxel at {@code (x,y,z)} that points along
     * {@code orientation}, or {@code 0} if that face produces no geometry.
     *
     * <p>Two faces are merged into one greedy quad only when their keys are equal, so the key has
     * to encode everything that must match: the sprite, the tint and the light. Leaving light out
     * of the key is the classic greedy-meshing bug -- it smears one block's lightmap value across
     * a whole merged quad. It is per-orientation because a block can look different on top, side
     * and bottom.
     */
    int key(int x, int y, int z, Orientation orientation);

    int color(int key);

    int light(int key);

    /** U at the minimum corner of one unit face. */
    float u0(int key);

    /** V at the minimum corner of one unit face. */
    float v0(int key);

    /** U advance per merged block along the first varying axis; 1 tiles the sprite once per block. */
    float uScale(int key);

    /** V advance per merged block along the second varying axis. */
    float vScale(int key);
}
