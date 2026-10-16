package net.amdfaster.blockentity;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes the parts of a block entity that never move into the chunk mesh, so only the moving parts are
 * drawn separately.
 *
 * <p>A chest, a bed and a sign are drawn by a BlockEntityRenderer, which assembles their geometry on the
 * CPU every frame: it builds the quads, applies the texture and issues the draw call, once per block
 * entity, whether or not anything about it has changed. A chest that has sat unopened for an hour is
 * rebuilt sixty times a second. In a storage room with a hundred of them that is a hundred separate draw
 * calls and a hundred geometry assemblies per frame for geometry that is identical every time.
 *
 * <p>Baking the unchanging parts into the section mesh makes them ordinary blocks: one draw call for the
 * whole section, no per-frame assembly, and -- because they really are block models now -- smooth lighting
 * and resource pack support come for free rather than having to be reimplemented in a special renderer.
 *
 * <h2>Only the parts that do not move</h2>
 *
 * <p>A chest lid opens. A bed does not. So the lid stays with the renderer and the body is baked. Baking
 * the lid would freeze it in one position, and a chest that cannot be seen to open tells the player
 * nothing about whether the click registered -- which is worse than the frame time saved. The decision is
 * per part, not per block entity.
 *
 * <h2>Not every renderer goes away</h2>
 *
 * <p>A block entity whose whole appearance animates -- a beacon beam, an end portal, a floating name --
 * has nothing static to bake and keeps its renderer entirely. Baking is for the case where most of the
 * geometry is still and a little of it is not, and pretending otherwise would produce a static object
 * where the game intends a moving one.
 *
 * <h2>The baked geometry has to match</h2>
 *
 * <p>The player has seen thousands of chests and knows what one looks like. Baked geometry that is a
 * millimetre out, or textured with the wrong face, is immediately noticeable in a way that a dropped frame
 * is not. So the bake uses the same model definition the renderer used, and the parts it takes are the
 * parts the renderer drew, at the same positions.
 *
 * <h2>Invalidation is the same as for any block</h2>
 *
 * <p>Once baked, a chest body is part of a section and is rebuilt when that section is rebuilt -- when a
 * block is placed next to it, when the section unloads, when the world changes. There is no separate
 * invalidation path to get wrong, which is most of the reason this is safe.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class StaticGeometryBaker {

    /** Milliseconds a BlockEntityRenderer spends assembling one block entity's geometry per frame. */
    public static final double MS_PER_ENTITY = 0.01;

    /** Quads in a chest body. */
    public static final int CHEST_BODY_QUADS = 22;

    /** Quads in a chest lid, which stays with the renderer because it opens. */
    public static final int CHEST_LID_QUADS = 6;

    /** Bytes per baked quad. */
    public static final int BYTES_PER_QUAD = 36;

    private final List<String> baked = new ArrayList<>();
    private final List<String> kept = new ArrayList<>();

    private long quadsBaked;
    private long drawCallsRemoved;

    /**
     * Records which parts of a block entity were baked and which were left with the renderer.
     *
     * @param type the block entity type, such as {@code minecraft:chest}
     * @param staticParts the parts that never move, with their quad counts
     * @param animatedParts the parts that move, which keep their renderer
     * @return how many quads were baked
     */
    public int bake(String type, List<Part> staticParts, List<Part> animatedParts) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("a block entity type is required");
        }
        if (staticParts == null) {
            throw new IllegalArgumentException("static parts are required for " + type);
        }

        int quads = 0;
        for (Part part : staticParts) {
            quads += part.quads();
        }

        if (quads == 0) {
            // Nothing static to bake. A block entity whose whole appearance animates keeps its renderer
            // entirely; baking would produce a static object where the game intends a moving one.
            this.kept.add(type);
            return 0;
        }

        this.baked.add(type);
        this.quadsBaked += quads;
        this.drawCallsRemoved++;
        return quads;
    }

    /** A piece of a block entity's geometry. */
    public record Part(String name, int quads, boolean animated) {
        /** An explicit constructor: a compact one is rejected by javac for records with validation. */
        public Part {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("a part name is required");
            }
            if (quads < 0) {
                throw new IllegalArgumentException("a negative quad count: " + quads);
            }
        }
    }

    /** Whether a block entity type had any of its geometry baked. */
    public boolean isBaked(String type) {
        return this.baked.contains(type);
    }

    /** Whether a block entity type kept its renderer entirely. */
    public boolean keptRenderer(String type) {
        return this.kept.contains(type);
    }

    public long quadsBaked() {
        return this.quadsBaked;
    }

    /** Bytes of vertex data the baked geometry occupies. */
    public long bytesBaked() {
        return this.quadsBaked * (long) BYTES_PER_QUAD;
    }

    /** How many per-frame draw calls were removed. */
    public long drawCallsRemoved() {
        return this.drawCallsRemoved;
    }

    /** Milliseconds of per-frame assembly avoided. */
    public double msSavedPerFrame() {
        return this.drawCallsRemoved * MS_PER_ENTITY;
    }

    /** Milliseconds a frame costs assembling every block entity separately. */
    public double msWithoutBaking() {
        return (this.drawCallsRemoved + this.kept.size()) * MS_PER_ENTITY;
    }

    /**
     * The share of the per-frame assembly avoided.
     *
     * <p>Zero before anything has been baked. A baker that has done nothing has technically avoided all of
     * it, but reporting a saving for an unused one reads as a result when it is the absence of one.
     */
    public double savedPercent() {
        double without = msWithoutBaking();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * (without - msSavedPerFrame()) / without;
    }
}
