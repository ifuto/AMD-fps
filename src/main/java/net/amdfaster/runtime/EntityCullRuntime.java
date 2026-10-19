package net.amdfaster.runtime;

import net.amdfaster.cull.CameraCutDetector;
import net.amdfaster.cull.Frustum;
import net.amdfaster.cull.PyramidGeometry;
import net.amdfaster.cull.ViewProjection;
import java.util.Arrays;

import net.amdfaster.entity.EntityBuffer;

/**
 * Decides, once per frame, which entities the frame will draw.
 *
 * <p>The game asks about entities one at a time, and this pass is about the set: a per-frame budget has
 * to be spent across all of them, and the ordering that keeps the nearest ones when the budget runs out
 * needs the whole list. So the frame is gathered first and answered second. The hook that asks is also
 * the one that gathers -- the first question of a frame triggers the pass, and every question after it,
 * including that first one, is answered from the result. Nothing is a frame late, which matters because
 * a cull that lags by one frame makes an entity appear a frame after it becomes visible.
 *
 * <p>No Minecraft types appear here on purpose. The mixin reads the camera and the level and calls
 * {@link #beginFrame}, {@link #offer} and {@link #finishFrame}; everything that decides anything is in
 * this class and runs on a plain JVM, which is the only way any of it can be tested.
 *
 * <p>What this adds over what the game already does is the frustum test happening before the game's own,
 * a budget across the frame, and the depth pyramid once one is supplied. The distance filter follows the
 * game's render distance rather than inventing a shorter one, so nothing this pass removes is something
 * the player would otherwise have seen.
 */
public final class EntityCullRuntime {

    /**
     * How much further than the entity distance limit the far plane sits.
     *
     * <p>The far plane is a side effect of needing a projection matrix, not a culling rule of its own, so
     * it is placed where it cannot reject anything the distance filter would have kept. A far plane that
     * cut closer would be a second, undocumented distance limit, and the overlay would report a distance
     * the pass is not actually using.
     */
    public static final float FAR_MARGIN = 4.0f;

    private static final int EMPTY = 0;

    private final EntityWorldRuntime world;

    /** The camera history the occlusion pass uses to hold itself open after a camera cut. */
    private final CameraCutDetector cuts;

    private final EntityBuffer buffer = new EntityBuffer(2048);
    private final float[] viewProjection = new float[16];
    private final Frustum frustum = new Frustum(Frustum.identity());

    /** Entity ids that survived, open-addressed and stored offset by one so zero means empty. */
    private int[] survivors = new int[1024];
    private int survivorCount;

    private float camX;
    private float camY;
    private float camZ;
    private long frameId = Long.MIN_VALUE;
    private long gatheredFrameId = Long.MIN_VALUE;
    private boolean gathering;

    private long frames;
    private long testedTotal;
    private long culledTotal;

    /**
     * The pyramid's level geometry, supplied with the pyramid it describes.
     *
     * <p>Null until a depth source exists, in which case the occlusion pass is skipped and the frustum
     * result stands on its own rather than being tested against a buffer nobody filled.
     */
    private PyramidGeometry pyramid;

    public EntityCullRuntime(EntityWorldRuntime world, CameraCutDetector cuts) {
        if (world == null || cuts == null) {
            throw new IllegalArgumentException("world runtime and camera history are required");
        }
        this.world = world;
        this.cuts = cuts;
    }

    /**
     * Starts a frame. Everything offered after this belongs to it.
     *
     * @param frameId          a number that changes once per frame, so a second call in the same frame
     *                         is a no-op and the pass does not run twice
     * @param camX             camera position, world space
     * @param camY             camera position, world space
     * @param camZ             camera position, world space
     * @param right            the world direction of screen +x, xyz, unit length
     * @param up               the world direction of screen +y, xyz, unit length
     * @param forward          the world direction the camera looks, xyz, unit length
     * @param nearHalfWidth    half the near plane's width, in world units
     * @param nearHalfHeight   half the near plane's height, in world units
     * @param nearDistance     distance to the near plane
     * @param maxCullDistance  how far the game is prepared to draw an entity, which this pass adopts
     *                         rather than substituting its own
     */
    public void beginFrame(long frameId, float camX, float camY, float camZ,
            float[] right, float[] up, float[] forward,
            float nearHalfWidth, float nearHalfHeight, float nearDistance, float maxCullDistance) {
        if (frameId == this.frameId) {
            return;
        }
        this.frameId = frameId;
        this.gathering = true;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.buffer.clear();
        this.survivorCount = 0;

        this.world.setMaxCullDistance(maxCullDistance);
        ViewProjection.build(camX, camY, camZ,
                right[0], right[1], right[2], up[0], up[1], up[2], forward[0], forward[1], forward[2],
                nearHalfWidth, nearHalfHeight, nearDistance,
                maxCullDistance * FAR_MARGIN, this.viewProjection);
        // The frustum's plane set is unchanged if screen +x is negated -- LEFT and RIGHT swap and the six
        // planes are the same six -- but the projection above is not, which is why the axes come from the
        // near plane rather than from a reconstruction of the game's yaw convention.
        this.frustum.update(this.viewProjection);
    }

    /** Adds one entity to the frame being gathered. Its id is what {@link #survived} is asked about. */
    public void offer(int entityId, float x, float y, float z, float yaw, int light,
            float halfWidth, float height) {
        this.buffer.add(entityId, x, y, z, yaw, light, halfWidth, height);
    }

    /**
     * Runs the passes and records who survived.
     *
     * <p>Called once per frame, after every entity has been offered and before any of them is answered.
     * Calling it twice for one frame is harmless and does nothing, because the survivors are already
     * those of this frame.
     */
    public void finishFrame() {
        if (this.gatheredFrameId == this.frameId) {
            return;
        }
        this.gatheredFrameId = this.frameId;
        this.gathering = false;
        this.lastGatherNanos = System.nanoTime();
        this.frames++;

        int survived = this.world.cullEntities(this.buffer, this.frustum, this.camX, this.camY, this.camZ,
                this.viewProjection, this.pyramid, this.cuts);
        // The table has to be emptied, not just its count zeroed. An open-addressed set is its contents;
        // resetting the count leaves last frame's ids sitting in the slots, where survived() finds them
        // and reports an entity the frame never gathered as visible.
        Arrays.fill(this.survivors, EMPTY);
        this.survivorCount = 0;
        for (int i = 0; i < survived; i++) {
            // survivorAt indexes into the buffer, and the buffer's model key is the entity id this pass
            // was offered, which is what the draw path will ask about.
            add(this.buffer.modelKey(this.world.survivorAt(i)));
        }
        this.testedTotal += this.world.cullTested();
        this.culledTotal += this.world.cullCulled();
    }

    /** Whether an entity the frame gathered survived the passes. */
    public boolean survived(int entityId) {
        int mask = this.survivors.length - 1;
        int slot = mix(entityId) & mask;
        while (true) {
            int held = this.survivors[slot];
            if (held == EMPTY) {
                return false;
            }
            if (held - 1 == entityId) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * How long a frame token may go unchanged before the next question starts a frame anyway.
     *
     * <p>The token is bumped by a hook into the game's own render path, and a mod that replaces that path
     * -- Sodium replaces the level renderer wholesale -- would stop bumping it. Answering from a set that
     * was computed an arbitrary time ago is not a conservative fallback, it is culling against a camera
     * the player left. So the set has an age, and past it the next question recomputes.
     */
    public static final long STALE_NANOS = 100_000_000L;

    private long lastGatherNanos;

    /**
     * Whether the next question should start a new frame.
     *
     * @param frameId the caller's frame token
     * @return true if the pass has not run for this frame, or its result has gone stale
     */
    public boolean shouldBegin(long frameId) {
        if (frameId != this.frameId) {
            return true;
        }
        return System.nanoTime() - this.lastGatherNanos > STALE_NANOS;
    }

    /** Whether a frame is in progress that has been gathered, which is what makes an answer meaningful. */
    public boolean hasResult() {
        return this.gatheredFrameId != Long.MIN_VALUE && !this.gathering;
    }

    /** Sets the pyramid's level geometry, to be paired with the depth the world runtime is given. */
    public void setPyramidGeometry(PyramidGeometry pyramid) {
        this.pyramid = pyramid;
    }

    /** How many frames have been decided. */
    public long frames() {
        return this.frames;
    }

    /** Entities tested across every frame so far. */
    public long testedTotal() {
        return this.testedTotal;
    }

    /** Entities removed across every frame so far. */
    public long culledTotal() {
        return this.culledTotal;
    }

    /** How many entities the current frame kept. */
    public int survivorCount() {
        return this.survivorCount;
    }

    /** Drops the frame in progress, which a world change makes meaningless. */
    public void onWorldChange() {
        this.buffer.clear();
        Arrays.fill(this.survivors, EMPTY);
        this.survivorCount = 0;
        this.gathering = false;
        this.frameId = Long.MIN_VALUE;
        this.gatheredFrameId = Long.MIN_VALUE;
        this.lastGatherNanos = 0L;
    }

    private void add(int entityId) {
        // Grow before probing, not after: at a load factor above three quarters an open-addressed table
        // starts clustering, and a table that only grows once it is full can loop looking for a slot.
        if ((this.survivorCount + 1) * 4 >= this.survivors.length * 3) {
            int[] old = this.survivors;
            this.survivors = new int[old.length << 1];
            this.survivorCount = 0;
            for (int held : old) {
                if (held != EMPTY) {
                    add(held - 1);
                }
            }
            // survivorCount was reset for the rehash, so it is already correct for the entries re-added.
            add(entityId);
            return;
        }
        int mask = this.survivors.length - 1;
        int slot = mix(entityId) & mask;
        while (this.survivors[slot] != EMPTY) {
            if (this.survivors[slot] - 1 == entityId) {
                return;
            }
            slot = (slot + 1) & mask;
        }
        this.survivors[slot] = entityId + 1;
        this.survivorCount++;
    }

    /**
     * Spreads an entity id across the table.
     *
     * <p>Entity ids are handed out in order and rise by one, so using one directly would fill consecutive
     * slots and degenerate into a linear scan. A multiply-shift breaks that up without the cost of a
     * full hash.
     */
    private static int mix(int value) {
        int h = value * 0x9E3779B9;
        return h ^ (h >>> 16);
    }
}
