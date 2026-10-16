package net.amdfaster.net;

/**
 * Does not send a movement packet for an entity that has not moved.
 *
 * <p>Every tracked entity has its position and rotation broadcast every tick, whether or not anything about
 * it changed. In a world where most entities are standing still -- the cows in a pen, the villagers at their
 * workstations, the armour stands in a storeroom -- most of those packets say "this entity is exactly where
 * it was", and the receiving end does the work of reading a packet that tells it nothing.
 *
 * <p>Not sending it removes the packet, the write, the flush and the handling on the other side.
 *
 * <h2>Rotation alone becomes a smaller packet</h2>
 *
 * <p>An entity that turned without moving still has to be updated, because the model faces a direction and a
 * villager looking the wrong way is visible. But the position is unchanged, so the packet only needs the
 * rotation -- a third of the data, and on a connection carrying hundreds of entities that is the difference
 * between the updates fitting in a tick and not.
 *
 * <h2>The threshold is not zero</h2>
 *
 * <p>An entity can move by a fraction of a block and be considered still, because the protocol encodes
 * position as a delta and a delta smaller than the encoding's resolution cannot be represented anyway. So a
 * movement below that resolution is not a change the other end could see, and sending it would transmit a
 * difference that is lost on the way. The threshold is what the encoding can carry, not a guess about what
 * matters.
 *
 * <h2>A teleport is always sent</h2>
 *
 * <p>A large movement is encoded as an absolute position rather than a delta, because the delta would not
 * fit. Suppressing one would leave the entity visibly in the wrong place on the other end until it moved
 * again, which is not a saving -- it is the entity being somewhere it is not. So anything past the teleport
 * distance bypasses the check entirely.
 *
 * <h2>Why this matters in single-player</h2>
 *
 * <p>The integrated server runs in the same process, and the packets it sends go through the same
 * connection and are handled by the same code. Suppressing them removes work from the client's own frame,
 * not just from a network that is not there.
 *
 * <p>Not thread safe. The tracker runs on the server thread.
 */
public final class MovementPacketSuppressor {

    /** Blocks of movement below which the delta cannot be encoded. */
    public static final double MIN_DELTA = 1.0 / 128.0;

    /** Radians of rotation below which nothing visibly changed. */
    public static final double MIN_ROTATION = 2.0 * Math.PI / 256.0;

    /** Blocks of movement that force an absolute teleport packet. */
    public static final double TELEPORT_DISTANCE = 8.0;

    /** Bytes a full position and rotation packet occupies. */
    public static final int BYTES_FULL = 16;

    /** Bytes a rotation-only packet occupies. */
    public static final int BYTES_ROTATION = 5;

    /** Milliseconds handling one movement packet costs on the receiving end. */
    public static final double MS_PER_PACKET = 0.004;

    private long ticks;
    private long suppressed;
    private long rotationOnly;
    private long full;
    private long teleports;

    /**
     * Decides what to send for one entity this tick.
     *
     * @param moved how far the entity moved, in blocks
     * @param turned how far it rotated, in radians
     * @return the kind of packet to send
     */
    public Packet decide(double moved, double turned) {
        if (moved < 0.0 || turned < 0.0) {
            throw new IllegalArgumentException(
                    "a negative movement or rotation: " + moved + ", " + turned);
        }
        this.ticks++;

        if (moved >= TELEPORT_DISTANCE) {
            // A large movement is encoded as an absolute position rather than a delta, because the delta
            // would not fit. Suppressing one would leave the entity visibly in the wrong place on the other
            // end until it moved again, which is not a saving -- it is the entity being somewhere it is not.
            this.teleports++;
            return Packet.TELEPORT;
        }
        boolean movedEnough = moved >= MIN_DELTA;
        boolean turnedEnough = turned >= MIN_ROTATION;

        if (!movedEnough && !turnedEnough) {
            this.suppressed++;
            return Packet.NONE;
        }
        if (!movedEnough) {
            // The position is unchanged, so the packet only needs the rotation. A villager looking the wrong
            // way is visible, so it still has to be sent -- but at a third of the size.
            this.rotationOnly++;
            return Packet.ROTATION;
        }
        this.full++;
        return Packet.FULL;
    }

    /** What to send for an entity this tick. */
    public enum Packet {
        /** Nothing changed that the encoding could represent. */
        NONE,
        /** Only the rotation changed. */
        ROTATION,
        /** The position changed. */
        FULL,
        /** The movement was too large for a delta. */
        TELEPORT
    }

    public long ticks() {
        return this.ticks;
    }

    /** How many entity updates were not sent at all. */
    public long suppressed() {
        return this.suppressed;
    }

    public long rotationOnly() {
        return this.rotationOnly;
    }

    public long full() {
        return this.full;
    }

    public long teleports() {
        return this.teleports;
    }

    public double suppressedPercent() {
        return this.ticks == 0 ? 0.0 : 100.0 * this.suppressed / this.ticks;
    }

    /** Bytes the packets that were sent occupy. */
    public long bytesSent() {
        return (this.rotationOnly + this.teleports) * (long) BYTES_ROTATION
                + this.full * (long) BYTES_FULL;
    }

    /** Bytes sending every entity every tick would occupy. */
    public long bytesWithoutSuppression() {
        return this.ticks * (long) BYTES_FULL;
    }

    /** Milliseconds of handling avoided on the receiving end. */
    public double msSaved() {
        return (this.suppressed * (long) BYTES_FULL
                + this.rotationOnly * (BYTES_FULL - BYTES_ROTATION))
                / (double) BYTES_FULL * MS_PER_PACKET;
    }

    /**
     * The share of the movement traffic avoided.
     *
     * <p>Zero before any entity has been tracked. A suppressor that has seen nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        double without = this.ticks * MS_PER_PACKET;
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved()) / without;
    }
}
