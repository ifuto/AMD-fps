package net.amdfaster.mc;

import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.voxel.RegionVoxelView;
import net.amdfaster.mesh.voxel.VoxelView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads block data out of a Minecraft {@link LevelReader} for {@link RegionVoxelView}.
 *
 * <p>Deliberately thin. Every decision that can be wrong in an interesting way -- the merge key
 * layout, the section arithmetic, the smooth lighting -- lives in a class that has no Minecraft type
 * in it and is covered by tests. What is left here is a handful of calls whose only failure mode is
 * a wrong name, which the compiler catches.
 *
 * <p>The names used are {@code LevelReader.getBlockState(BlockPos)}, {@code LevelReader.hasChunk},
 * and {@code BlockState.isAir()}, {@code isSolid()}, {@code useShapeForLightOcclusion()} and
 * {@code getLightEmission()}, all checked against the published mappings for 1.21.11.
 *
 * <p><b>Opacity</b> is {@code isSolid() && !useShapeForLightOcclusion()}. That is what "this block
 * hides the face touching it" has to mean, and deriving it from the two primitives rather than
 * calling a ready-made predicate keeps the rule visible here instead of hidden in the game. A slab
 * is solid but uses its shape for light occlusion, so it does not hide a neighbour's face; a slab
 * that did would leave a hole in the world wherever two slabs meet.
 *
 * <p><b>Build limits</b> are constructor parameters rather than a call to the level, because the
 * accessor's name could not be confirmed for 1.21.11 and guessing at it is how a mod ends up not
 * compiling. It also makes the below-the-world behaviour testable, which matters: reading air below
 * the build limit is exactly what makes the underside of the world disappear.
 */
public final class LevelBlockSampler implements RegionVoxelView.BlockSampler {

    private final LevelReader level;
    private final BlockFaceSource faces;
    private final int minBuildHeight;
    private final int maxBuildHeightExclusive;
    private final BlockStateCache states = new BlockStateCache();

    /**
     * One mutable position, reused for every read.
     *
     * <p>The code this replaced allocated a {@code BlockPos} per lookup, and meshing a section does
     * tens of thousands of lookups. These are short-lived enough that the generational collector
     * reclaims them cheaply, but they are not free: allocating at that rate inside the hottest loop
     * of a rebuild is what makes a rebuild cost more than the geometry it produces.
     *
     * <p>Safe to reuse because {@code getBlockState} reads the position and returns a
     * {@code BlockState}, which does not retain it.
     */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /**
     * @param level                 the world to read
     * @param faces                 appearance and light for each face
     * @param minBuildHeight        lowest Y that exists, inclusive
     * @param maxBuildHeightExclusive one past the highest Y that exists
     */
    public LevelBlockSampler(LevelReader level, BlockFaceSource faces, int minBuildHeight,
                             int maxBuildHeightExclusive) {
        if (maxBuildHeightExclusive <= minBuildHeight) {
            throw new IllegalArgumentException("empty build height: " + minBuildHeight + ".."
                    + maxBuildHeightExclusive);
        }
        this.level = level;
        this.faces = faces;
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeightExclusive = maxBuildHeightExclusive;
    }

    public int minBuildHeight() {
        return this.minBuildHeight;
    }

    public int maxBuildHeightExclusive() {
        return this.maxBuildHeightExclusive;
    }

    /**
     * True when the coordinate exists in the world.
     *
     * <p>Two different questions get answered here and they are easy to conflate. Outside the build
     * height there is genuinely nothing, and the mesher must treat it as air so the bottom of the
     * world still gets a floor. An unloaded chunk is a different case: there may well be a block
     * there, it is just not resident, and answering "air" would punch a hole in the mesh that only
     * closes when the chunk arrives. Both read as "not in world" to the view, which is correct for
     * the first and merely conservative for the second -- the section gets remeshed when the chunk
     * loads.
     */
    @Override
    public boolean isInWorld(int x, int y, int z) {
        if (y < this.minBuildHeight || y >= this.maxBuildHeightExclusive) {
            return false;
        }
        return this.level.hasChunk(SectionCoords.sectionOf(x), SectionCoords.sectionOf(z));
    }

    /**
     * Drops the cached block flags. Must be called before each rebuild of a section.
     *
     * <p>Without it a block that changed since the last read would keep meshing from its old state,
     * and because nothing else dirties the section the wrong mesh would persist. See
     * {@link BlockStateCache}.
     */
    public void invalidate() {
        this.states.clear();
    }

    /** Cached flag lookups since the last {@link #invalidate()}; exposed so the hit rate is testable. */
    public int cacheHits() {
        return this.states.hits();
    }

    /** Level lookups since the last {@link #invalidate()}. */
    public int cacheMisses() {
        return this.states.misses();
    }

    /**
     * Resolves the three facts meshing needs about a block, through the cache.
     *
     * <p>Opacity is {@code isSolid() && !useShapeForLightOcclusion()}: a slab is solid but uses its
     * shape for light occlusion, so it must not hide a neighbour's face. Deriving it from the two
     * primitives rather than a ready-made predicate keeps the rule visible here.
     */
    private int flags(int x, int y, int z) {
        return this.states.flags(x, y, z, (bx, by, bz) -> {
            BlockState state = this.level.getBlockState(this.cursor.set(bx, by, bz));
            boolean opaque = state.isSolid() && !state.useShapeForLightOcclusion();
            return BlockStateCache.pack(state.isAir(), opaque, state.getLightEmission());
        });
    }

    @Override
    public boolean isOpaque(int x, int y, int z) {
        return BlockStateCache.isOpaque(flags(x, y, z));
    }

    @Override
    public int key(int x, int y, int z, Orientation orientation) {
        int sprite = this.faces.spriteId(x, y, z, orientation);
        if (sprite < 0) {
            return VoxelView.NO_GEOMETRY;
        }
        int flags = flags(x, y, z);
        if (BlockStateCache.isAir(flags)) {
            return VoxelView.NO_GEOMETRY;
        }
        return BlockKeys.encode(sprite,
                this.faces.blockLight(x, y, z),
                this.faces.skyLight(x, y, z),
                this.faces.tint(x, y, z, orientation),
                BlockStateCache.lightEmission(flags),
                this.faces.isCutout(x, y, z, orientation));
    }
}
