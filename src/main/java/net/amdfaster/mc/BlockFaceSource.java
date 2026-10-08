package net.amdfaster.mc;

import net.amdfaster.mesh.Orientation;

/**
 * Where a face's appearance and light come from, in plain ints.
 *
 * <p>No Minecraft type appears here, and that is the point. {@link LevelBlockSampler} composes this
 * with block-state data to build a merge key, and the composition is where the classic
 * greedy-meshing bugs live -- forgetting that light or tint has to be part of the key. Keeping the
 * inputs as ints means the composition can be driven from a fake in a unit test; if the interface
 * took a {@code BlockState}, the only way to test it would be to run the game.
 *
 * <p>The real implementation reads the block atlas for the sprite, the biome colour resolver for
 * the tint, and the chunk's light sections for the light levels.
 */
public interface BlockFaceSource {

    /**
     * Sprite id for the face of the block at {@code (x,y,z)} pointing along {@code orientation}, or
     * a negative value when that face produces no geometry.
     *
     * <p>Per-orientation because a block can look different on top, side and bottom: grass is green
     * above, dirt below, and a blended edge between.
     */
    int spriteId(int x, int y, int z, Orientation orientation);

    /**
     * Tint index for that face, or {@link BlockKeys#NO_TINT} when the face is not tinted.
     *
     * <p>An index rather than a colour so the key stays small. Two faces in different biomes resolve
     * to different indices and therefore do not merge, which is the whole requirement.
     */
    int tint(int x, int y, int z, Orientation orientation);

    /**
     * True when the face needs the alpha-tested pipeline rather than the opaque one.
     *
     * <p>Leaves and glass panes are the common cases. Merging them into an opaque quad draws the
     * transparent texels as black rather than discarding them.
     */
    boolean isCutout(int x, int y, int z, Orientation orientation);

    /** Block light at {@code (x,y,z)}, 0..15. */
    int blockLight(int x, int y, int z);

    /** Sky light at {@code (x,y,z)}, 0..15. */
    int skyLight(int x, int y, int z);
}
