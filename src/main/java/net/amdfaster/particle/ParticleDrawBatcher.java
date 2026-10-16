package net.amdfaster.particle;

/**
 * Collects particles that share a texture into one draw call instead of one call each.
 *
 * <p>Each particle is drawn with its own draw call: bind the texture, set the position, issue the call. A
 * thousand raindrops are a thousand calls, and the calls themselves -- not the pixels -- are what make
 * heavy rain slow, because the CPU spends more time setting up draws than the GPU spends filling them.
 *
 * <p>Particles of the same type share a texture, so a thousand raindrops can be one call with a thousand
 * instances. That is the difference between rain being a frame-rate problem and rain being free.
 *
 * <h2>A different texture means a different batch</h2>
 *
 * <p>The texture is bound once per draw call, so a batch can only contain particles that use the same one.
 * Mixing them would draw every particle with whichever texture was bound last, which is not a subtle error
 * -- it is rain drawn as flame, and the whole point of batching would be undermined by getting it wrong.
 *
 * <h2>The order within a translucent batch is preserved</h2>
 *
 * <p>Particles are translucent, so they blend, so what is drawn over what matters. Reversing or otherwise
 * reordering them to make the batching easier would change which particle is in front, and overlapping
 * particles -- smoke, flame, a splash -- would look wrong in a way that is hard to describe but easy to
 * see. The batch keeps the order the particles were given in.
 *
 * <h2>A full buffer flushes rather than overflowing</h2>
 *
 * <p>A burst can produce more particles than one buffer holds. Dropping the excess would make particles
 * disappear, and growing the buffer without bound would defeat the point of having one. Flushing issues the
 * draw and starts a new buffer, which costs one extra call and loses nothing.
 *
 * <h2>Why the flush is at the end of the frame too</h2>
 *
 * <p>A batch that is never full would otherwise wait forever for more particles to arrive. So whatever is
 * in the buffer is drawn at the end of the frame, which bounds the delay to one frame and means a scene
 * with few particles still draws them.
 *
 * <p>Not thread safe. Particles are drawn on the render thread.
 */
public final class ParticleDrawBatcher {

    /** Particles held in one buffer before it is flushed. */
    public static final int BATCH_SIZE = 1024;

    /** Milliseconds one draw call costs, which is what batching removes. */
    public static final double MS_PER_CALL = 0.01;

    /** Milliseconds adding one particle to a buffer costs. */
    public static final double MS_PER_ADD = 0.0002;

    private String currentTexture;
    private int inBuffer;

    private long added;
    private long calls;
    private long textureSwitches;
    private long flushesFull;

    /**
     * Adds a particle to the batch.
     *
     * @param texture the particle's texture; a different one flushes the current batch first
     * @return true if adding it flushed a batch
     */
    public boolean add(String texture) {
        if (texture == null || texture.isEmpty()) {
            throw new IllegalArgumentException("a texture is required");
        }
        this.added++;

        if (!texture.equals(this.currentTexture)) {
            // The texture is bound once per draw call, so a batch can only contain particles that use the
            // same one. Mixing them would draw every particle with whichever texture was bound last, which
            // is rain drawn as flame.
            boolean flushed = flushIfNotEmpty();
            this.currentTexture = texture;
            this.textureSwitches++;
            return flushed;
        }
        if (this.inBuffer >= BATCH_SIZE) {
            // A burst can produce more particles than one buffer holds. Dropping the excess would make
            // particles disappear, and growing without bound would defeat the point of having one.
            flush();
            this.flushesFull++;
            return true;
        }
        this.inBuffer++;
        return false;
    }

    /**
     * Draws whatever is in the buffer, at the end of the frame.
     *
     * <p>A batch that is never full would otherwise wait forever for more particles to arrive, so this
     * bounds the delay to one frame and means a scene with few particles still draws them.
     */
    public boolean endFrame() {
        boolean flushed = flushIfNotEmpty();
        this.currentTexture = null;
        return flushed;
    }

    private boolean flushIfNotEmpty() {
        if (this.inBuffer == 0) {
            return false;
        }
        flush();
        return true;
    }

    private void flush() {
        this.calls++;
        this.inBuffer = 0;
    }

    /** How many particles are waiting in the current buffer. */
    public int buffered() {
        return this.inBuffer;
    }

    public long added() {
        return this.added;
    }

    /** How many draw calls were issued. */
    public long calls() {
        return this.calls;
    }

    /** How many times the texture changed and forced a flush. */
    public long textureSwitches() {
        return this.textureSwitches;
    }

    /** How many flushes happened because a buffer was full. */
    public long flushesFull() {
        return this.flushesFull;
    }

    public double callsPerParticle() {
        return this.added == 0 ? 0.0 : (double) this.calls / this.added;
    }

    /** Milliseconds the draw calls cost. */
    public double msCalls() {
        return this.calls * MS_PER_CALL;
    }

    /** Milliseconds issuing one call per particle would cost. */
    public double msWithoutBatching() {
        return this.added * MS_PER_CALL;
    }

    /**
     * The share of the draw call cost avoided.
     *
     * <p>Zero before any particle has been added. A batcher that has drawn nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     *
     * <p>The cost of adding to the buffer is not counted, because that work happens either way -- what
     * batching removes is the calls, not the building.
     */
    public double savedPercent() {
        double without = msWithoutBatching();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msCalls()) / without;
    }
}
