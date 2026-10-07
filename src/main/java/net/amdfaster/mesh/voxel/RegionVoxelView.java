package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;

/**
 * A {@link VoxelView} over one section of a world, translating section-local coordinates into world
 * coordinates and asking a {@link BlockSampler} for everything else.
 *
 * <p>This exists because of a bug that is easy to ship and impossible to miss once you see it:
 * mesh a 16³ section in isolation and every face on a section boundary looks like it touches air,
 * so the world grows a wall of quads at every chunk seam.
 *
 * <p>The fix is <em>not</em> to make the view bigger. A view of 18³ would make the mesher iterate
 * the border blocks too and emit their faces, which duplicates the neighbour section's geometry and
 * produces the same seam from the other side. The view stays 16³ and simply answers for the one
 * block outside it, because {@link GreedyMesher} asks for {@code block - 1} and {@code block + 1}
 * along the axis it is sweeping. What has to change is that those queries reach the world instead
 * of being treated as out of bounds.
 *
 * <p>The distinction that matters is between <em>outside the section</em> and <em>outside the
 * world</em>. The first is a normal query the sampler answers from the neighbouring section. The
 * second -- below y = 0, above the build limit -- really has nothing there, and reads as air so the
 * bottom of the world still gets a floor.
 */
public final class RegionVoxelView implements VoxelView {

    /**
     * Where block data comes from. Implemented against Minecraft's {@code Level} by the adapter and
     * against a map in tests, so the seam behaviour is checkable without the game.
     *
     * <p>An implementation must answer correctly for one block outside the section on every side.
     * Answering "air" there instead is the seam bug this class exists to prevent.
     */
    public interface BlockSampler {

        /** The merge key at a world coordinate, or {@link VoxelView#NO_GEOMETRY} for air. */
        int key(int x, int y, int z, Orientation orientation);

        /** Whether a block at a world coordinate hides the face touching it. */
        boolean isOpaque(int x, int y, int z);

        /** Whether the coordinate is inside the world at all, as opposed to merely unloaded. */
        default boolean isInWorld(int x, int y, int z) {
            return true;
        }
    }

    /** How far outside the section the mesher asks, on each side. */
    public static final int BORDER = 1;

    private final BlockSampler sampler;
    private final int originX;
    private final int originY;
    private final int originZ;

    /**
     * @param originX world coordinate of the section's low corner
     */
    public RegionVoxelView(BlockSampler sampler, int originX, int originY, int originZ) {
        this.sampler = sampler;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    @Override
    public int sizeX() {
        return SectionMesh.SIZE;
    }

    @Override
    public int sizeY() {
        return SectionMesh.SIZE;
    }

    @Override
    public int sizeZ() {
        return SectionMesh.SIZE;
    }

    @Override
    public boolean isOpaque(int x, int y, int z) {
        int wx = this.originX + x;
        int wy = this.originY + y;
        int wz = this.originZ + z;
        if (!this.sampler.isInWorld(wx, wy, wz)) {
            return false;   // below the world or above the build limit: nothing to hide a face
        }
        return this.sampler.isOpaque(wx, wy, wz);
    }

    @Override
    public int key(int x, int y, int z, Orientation orientation) {
        int wx = this.originX + x;
        int wy = this.originY + y;
        int wz = this.originZ + z;
        if (!this.sampler.isInWorld(wx, wy, wz)) {
            return NO_GEOMETRY;
        }
        return this.sampler.key(wx, wy, wz, orientation);
    }

    @Override
    public int color(int key) {
        return key;
    }

    @Override
    public int light(int key) {
        return key;
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
