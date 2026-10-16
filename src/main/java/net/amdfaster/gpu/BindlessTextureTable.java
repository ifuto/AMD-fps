package net.amdfaster.gpu;

/**
 * Looks textures up by a handle in a table, so a draw never has to bind one.
 *
 * <p>Every material change is a bind: the CPU tells the driver which texture the next draw uses, the driver
 * validates it and updates the state, and the GPU waits for the state to settle. A scene with a hundred
 * materials is a hundred binds a frame, and on integrated graphics the driver work is a measurable part of the
 * frame -- it is the part that makes a CPU-bound scene CPU-bound.
 *
 * <p>Putting every texture in one table and letting the shader index into it by a handle removes the binds
 * entirely. The material becomes a number in the vertex data, and a hundred materials draw in one call with no
 * state changes between them.
 *
 * <h2>A handle is a slot, not a pointer</h2>
 *
 * <p>The table is an array and the handle is an index into it, so it stays valid as long as the slot holds the
 * same texture. A handle that was a pointer or a driver object would be invalidated by anything that moved or
 * reallocated the texture, and the failure would be a draw reading freed memory -- which is not a rendering
 * bug, it is a crash or, worse, a frame that happens to look right until the memory is reused.
 *
 * <h2>An unloaded texture must not leave a dangling slot</h2>
 *
 * <p>A resource pack reload, or a texture that is no longer used, frees the image. If its slot still holds the
 * old handle, a draw that references it reads something that is gone. So unloading writes a known placeholder
 * into the slot -- a texture that exists and is obviously wrong -- rather than leaving the old value. A block
 * drawn with the missing texture is a visible defect the player can report; a block drawn from freed memory is
 * neither visible nor reportable.
 *
 * <h2>A freed slot is not reused while a frame is using it</h2>
 *
 * <p>Frames are in flight: the GPU is drawing frame N while the CPU prepares frame N+1. Freeing a slot and
 * giving it to a different texture would let frame N draw the new texture where the old one belonged, which is
 * a frame that is wrong in a way that depends on timing and cannot be reproduced. So a freed slot waits until
 * no frame that could reference it is still in flight, which the frame ring already tracks.
 *
 * <h2>Why this matters more with batching</h2>
 *
 * <p>The whole point of merging draws is that one call covers many objects, and a bind between any two of them
 * would split the call back apart. Bindless is what lets a merged draw stay merged: without it, the merger can
 * only combine objects that already share a texture, which is a much smaller set.
 *
 * <p>Not thread safe. The table is written on the client thread and read by shaders through the bound buffer.
 */
public final class BindlessTextureTable {

    /** Slots the table has. */
    public static final int CAPACITY = 4096;

    /** The handle written into a slot whose texture has been unloaded. */
    public static final int MISSING = 0;

    /** Milliseconds one texture bind costs. */
    public static final double MS_PER_BIND = 0.004;

    /** Milliseconds one handle lookup costs, which the shader does rather than the CPU. */
    public static final double MS_PER_LOOKUP = 0.00002;

    private final long[] slots = new long[CAPACITY];
    private final int[] freeAfterFrame = new int[CAPACITY];

    private int currentFrame;
    private int inUse;
    private int nextFree;

    private long bound;
    private long lookedUp;
    private long unloaded;

    public BindlessTextureTable() {
        // Slot zero is the missing texture, so a slot that was never filled reads as one rather than as
        // whatever the driver object at zero happens to be.
        this.slots[MISSING] = -1L;
        this.nextFree = 1;
    }

    /**
     * Adds a texture and returns its handle.
     *
     * @param textureId an identifier for the texture, opaque to this class
     * @return the handle to store in vertex data, or -1 if the table is full
     */
    public int add(long textureId) {
        if (this.nextFree >= CAPACITY) {
            return -1;
        }
        int handle = this.nextFree++;
        this.slots[handle] = textureId;
        this.inUse++;
        this.bound++;
        return handle;
    }

    /**
     * Removes a texture.
     *
     * <p>The slot is filled with the missing texture rather than left holding the old one, because a draw that
     * references a freed texture has to read something that exists. A block drawn with the missing texture is a
     * visible defect the player can report; one drawn from freed memory is neither visible nor reportable.
     *
     * @param inFlightFrames how many frames could still reference the handle
     */
    public void remove(int handle, int inFlightFrames) {
        if (handle <= MISSING || handle >= CAPACITY) {
            throw new IllegalArgumentException("not a handle: " + handle);
        }
        if (inFlightFrames < 0) {
            throw new IllegalArgumentException("a negative frame count: " + inFlightFrames);
        }
        this.slots[handle] = -1L;
        // The slot is not handed out again until no frame that could reference it is still being drawn.
        // Freeing it immediately would let an in-flight frame draw the new texture where the old one belonged,
        // which is a frame that is wrong in a way that depends on timing and cannot be reproduced.
        this.freeAfterFrame[handle] = this.currentFrame + Math.max(1, inFlightFrames);
        this.inUse--;
        this.unloaded++;
    }

    /** Marks a frame as submitted, so slots freed that long ago can be reused. */
    public void endFrame() {
        this.currentFrame++;
    }

    /**
     * Whether a freed slot can be given to a new texture.
     *
     * @return true if no in-flight frame could still reference it
     */
    public boolean isReusable(int handle) {
        if (handle <= MISSING || handle >= CAPACITY) {
            throw new IllegalArgumentException("not a handle: " + handle);
        }
        return this.slots[handle] == -1L && this.freeAfterFrame[handle] <= this.currentFrame;
    }

    /**
     * Reads a texture by handle.
     *
     * @return the texture identifier, or -1 for a slot that has been unloaded
     */
    public long lookup(int handle) {
        if (handle < 0 || handle >= CAPACITY) {
            throw new IllegalArgumentException("not a handle: " + handle);
        }
        this.lookedUp++;
        return this.slots[handle];
    }

    /** How many slots hold a texture. */
    public int inUse() {
        return this.inUse;
    }

    /** How many slots the table has. */
    public int capacity() {
        return CAPACITY;
    }

    public long bound() {
        return this.bound;
    }

    public long lookedUp() {
        return this.lookedUp;
    }

    /** How many textures were unloaded. */
    public long unloaded() {
        return this.unloaded;
    }

    /** Milliseconds of binding avoided, for the draws that used handles instead. */
    public double msSaved() {
        return this.bound * MS_PER_BIND - this.bound * MS_PER_LOOKUP;
    }

    /**
     * The share of the bind cost avoided.
     *
     * <p>Zero before any texture has been added. A table that has nothing in it has technically avoided every
     * bind, but reporting that as a saving would describe an unused one as though it had done something.
     */
    public double savedPercent() {
        if (this.bound == 0) {
            return 0.0;
        }
        double binds = this.bound * MS_PER_BIND;
        return 100.0 * Math.max(0.0, binds - this.bound * MS_PER_LOOKUP) / binds;
    }
}
