package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;

/**
 * A {@link VoxelView} backed by flat arrays, for tests and for any offline tooling that needs to
 * mesh without Minecraft.
 *
 * <p>Keys are the same in all six orientations here. A block that looks different on top and side
 * needs its own {@link VoxelView} implementation; the mesher does not care which it gets.
 */
public final class ArrayVoxelView implements VoxelView {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] keys;
    private final boolean[] opaque;

    public ArrayVoxelView(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.keys = new int[sizeX * sizeY * sizeZ];
        this.opaque = new boolean[sizeX * sizeY * sizeZ];
    }

    private int index(int x, int y, int z) {
        return (y * this.sizeZ + z) * this.sizeX + x;
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0
                && x < this.sizeX && y < this.sizeY && z < this.sizeZ;
    }

    public ArrayVoxelView set(int x, int y, int z, int key, boolean opaque) {
        this.keys[index(x, y, z)] = key;
        this.opaque[index(x, y, z)] = opaque;
        return this;
    }

    /** Fills an axis-aligned box with one key. Coordinates are half-open. */
    public ArrayVoxelView fill(int x0, int y0, int z0, int x1, int y1, int z1, int key, boolean opaque) {
        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    this.set(x, y, z, key, opaque);
                }
            }
        }
        return this;
    }

    @Override
    public int sizeX() {
        return this.sizeX;
    }

    @Override
    public int sizeY() {
        return this.sizeY;
    }

    @Override
    public int sizeZ() {
        return this.sizeZ;
    }

    @Override
    public boolean isOpaque(int x, int y, int z) {
        return inBounds(x, y, z) && this.opaque[index(x, y, z)];
    }

    @Override
    public int key(int x, int y, int z, Orientation orientation) {
        return inBounds(x, y, z) ? this.keys[index(x, y, z)] : NO_GEOMETRY;
    }

    @Override
    public int color(int key) {
        return key;
    }

    @Override
    public int light(int key) {
        return 0;
    }

    @Override
    public float u0(int key) {
        return 0f;
    }

    @Override
    public float v0(int key) {
        return 0f;
    }

    @Override
    public float uScale(int key) {
        return 1f;
    }

    @Override
    public float vScale(int key) {
        return 1f;
    }
}
