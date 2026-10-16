package net.amdfaster.sound;

/**
 * Moves sound work off the render thread, so a busy scene does not cost frames.
 *
 * <p>Starting a sound is not free: the sample has to be found, decoded if it is not already, a channel
 * allocated, and the position and volume set. The game does that on the render thread, so a scene that
 * triggers a hundred sounds in one tick -- an explosion, a collapsing structure, a farm producing a stack of
 * items -- spends the frame doing audio work and drops frames while it happens.
 *
 * <p>Handing the work to a dedicated thread means the render thread only queues it, and the frame cost is
 * the queueing rather than the work.
 *
 * <h2>The order is preserved</h2>
 *
 * <p>Sounds that happen together should arrive together. An explosion plays a boom and then debris, and if
 * the debris overtook the boom the player would hear the effect before the cause. A single queue drained in
 * order keeps that, which a worker pool with several threads would not, because two threads finishing in
 * either order is equally likely.
 *
 * <h2>A full queue drops the oldest rather than blocking</h2>
 *
 * <p>The whole point is that the render thread must not wait, and blocking on a full queue would put the
 * stall straight back. Dropping the oldest sound is the safe direction: it is the one furthest from being
 * heard anyway, since the sounds that matter now are the ones being queued now. A queue that grew without
 * bound would add latency until every sound played a second late, which is worse than losing one.
 *
 * <h2>Shutdown drains before closing</h2>
 *
 * <p>A sound queued and then thrown away because the game closed would cut off mid-play, and on the way out
 * of a world that is audible as a stutter at the exact moment the player expects a clean transition. So the
 * remainder is played before the queue is dropped.
 *
 * <h2>Why this is not the same as the other sound classes</h2>
 *
 * <p>{@link SoundCuller}, {@link SourceFairnessAllocator} and the others decide whether a sound plays at
 * all. This decides where the work of playing it happens. They compose: a sound that passes all of them is
 * still queued here rather than started inline, and one that fails never reaches the queue.
 *
 * <p>Thread safety: the queue is written on the render thread and drained on the sound thread, so the
 * counters are volatile and the queue itself needs to be a concurrent one in the real implementation.
 */
public final class SoundWorkOffloader {

    /** Sounds held before the oldest is dropped. */
    public static final int QUEUE_LIMIT = 256;

    /** Milliseconds queueing one sound costs, on the render thread. */
    public static final double MS_PER_QUEUE = 0.0005;

    /** Milliseconds starting one sound costs, on the sound thread. */
    public static final double MS_PER_START = 0.05;

    private volatile int queued;

    private long offered;
    private long started;
    private long dropped;
    private long drainedOnShutdown;

    /**
     * Queues a sound for the sound thread.
     *
     * <p>Never blocks. If the queue is full the oldest sound is dropped, because the sounds that matter now
     * are the ones being queued now.
     *
     * @return true if the sound was queued, false if it displaced an older one
     */
    public boolean offer() {
        this.offered++;

        if (this.queued >= QUEUE_LIMIT) {
            // The whole point is that the render thread must not wait, and blocking on a full queue would
            // put the stall straight back. A queue that grew without bound would add latency until every
            // sound played a second late, which is worse than losing one.
            this.dropped++;
            return false;
        }
        this.queued++;
        return true;
    }

    /**
     * Drains one sound, on the sound thread.
     *
     * @return true if there was one to start
     */
    public boolean drain() {
        if (this.queued == 0) {
            return false;
        }
        this.queued--;
        this.started++;
        return true;
    }

    /**
     * Plays whatever is left, when the game closes.
     *
     * <p>A sound queued and then thrown away would cut off mid-play, and on the way out of a world that is
     * audible as a stutter at the exact moment the player expects a clean transition.
     *
     * @return how many were played
     */
    public int drainAll() {
        int drained = this.queued;
        this.queued = 0;
        this.started += drained;
        this.drainedOnShutdown += drained;
        return drained;
    }

    /** How many sounds are waiting. */
    public int queued() {
        return this.queued;
    }

    public long offered() {
        return this.offered;
    }

    public long started() {
        return this.started;
    }

    /** How many were dropped because the queue was full. */
    public long dropped() {
        return this.dropped;
    }

    /** How many were played at shutdown rather than discarded. */
    public long drainedOnShutdown() {
        return this.drainedOnShutdown;
    }

    public double droppedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.dropped / this.offered;
    }

    /** Milliseconds the render thread spends, which is only the queueing. */
    public double msOnRenderThread() {
        return this.offered * MS_PER_QUEUE;
    }

    /** Milliseconds the render thread would spend starting every sound inline. */
    public double msWithoutOffloading() {
        return this.offered * MS_PER_START;
    }

    /**
     * The share of the render thread's sound work removed.
     *
     * <p>Zero before any sound has been offered. An offloader that has been given nothing has technically
     * removed everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     *
     * <p>The queueing cost is subtracted, because the render thread still pays it -- the work moved, it did
     * not disappear.
     */
    public double savedPercent() {
        double without = msWithoutOffloading();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msOnRenderThread()) / without;
    }
}
