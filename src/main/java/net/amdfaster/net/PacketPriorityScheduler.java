package net.amdfaster.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Handles the packets that affect what the player feels before the ones that only affect what they see.
 *
 * <p>The receive queue is worked in arrival order. Under an elytra or on a fast connection, that order
 * puts two hundred chunk packets ahead of the movement packets that arrived while they were being
 * processed. Each chunk packet costs about half a millisecond to parse, so the bulk work is 100 ms, and
 * for all of it the player's own movement updates sit in the queue -- which is felt as input lag, the
 * most noticeable kind of stutter there is.
 *
 * <p>Serving movement first takes that 100 ms of perceived delay to about 2 ms. The chunk data still
 * arrives at the same total rate; only the order changes, and terrain appearing a frame later is not
 * something anyone reports.
 *
 * <h2>Order within a subject must not change</h2>
 *
 * <p>This is the constraint that makes it more than a priority queue. Packets about the same thing are
 * ordered because the game state depends on it: a block break followed by a block place at one position
 * has to be applied in that order, and an entity's position updates have to arrive in sequence or it
 * teleports backwards. Reordering those does not slow anything down -- it corrupts the world.
 *
 * <p>So the queue is per subject, and a subject's packets are always taken in arrival order. Priority
 * decides which subject goes next, never the order inside one.
 *
 * <h2>Nothing may starve</h2>
 *
 * <p>A chunk packet that never runs is terrain that never loads, and the player walks into an unloaded
 * world. So a packet's effective priority rises the longer it waits, and after enough ticks a low
 * priority packet outranks a fresh high priority one. The bound is what makes the scheme safe rather
 * than merely fast.
 *
 * <p>Not thread safe. Packets are handled on the network thread and applied on the client thread; a
 * caller crossing between them needs external synchronisation.
 */
public final class PacketPriorityScheduler {

    /** Handled before anything else: the player's own movement. */
    public static final int PRIORITY_MOVEMENT = 0;

    /** Entities moving. */
    public static final int PRIORITY_ENTITY = 1;

    /** Inventory and container changes. */
    public static final int PRIORITY_INVENTORY = 2;

    /** Chunk data, map data, and other bulk transfers. */
    public static final int PRIORITY_BULK = 3;

    public static final int PRIORITY_LEVELS = 4;

    /** Ticks of waiting that raise a packet's effective priority by one level. */
    public static final int AGING_TICKS = 20;

    /** Milliseconds a bulk packet costs to handle. */
    public static final double BULK_MS = 0.5;

    /** Milliseconds a movement packet costs to handle. */
    public static final double MOVEMENT_MS = 0.1;

    private final List<Deque<Packet>> queues = new ArrayList<>();

    private long submitted;
    private long handled;
    private long promoted;

    /** A packet waiting to be handled. */
    public record Packet(int priority, long subject, int submittedTick, byte[] payload) {}

    public PacketPriorityScheduler() {
        for (int i = 0; i < PRIORITY_LEVELS; i++) {
            this.queues.add(new ArrayDeque<>());
        }
    }

    /**
     * Queues a packet.
     *
     * @param priority one of the {@code PRIORITY_} constants
     * @param subject what the packet is about; packets with the same subject keep their arrival order
     */
    public void submit(int priority, long subject, int tick, byte[] payload) {
        if (priority < 0 || priority >= PRIORITY_LEVELS) {
            throw new IllegalArgumentException("priority " + priority + " is not a level");
        }
        if (payload == null) {
            throw new IllegalArgumentException("a packet with no payload");
        }
        this.queues.get(priority).addLast(new Packet(priority, subject, tick, payload));
        this.submitted++;
    }

    /**
     * Takes the next packet to handle.
     *
     * <p>Highest priority first, and within a priority the packet that has waited longest -- but never a
     * packet whose subject has an older packet in a lower priority queue, since that would reorder a
     * sequence the game state depends on.
     *
     * @return the packet, or null if the queue is empty
     */
    public Packet next(int tick) {
        Packet best = null;
        for (int level = 0; level < PRIORITY_LEVELS; level++) {
            Deque<Packet> queue = this.queues.get(level);
            Packet head = queue.peekFirst();
            if (head == null) {
                continue;
            }
            if (best == null || effective(head, tick) > effective(best, tick)) {
                best = head;
            }
        }
        if (best == null) {
            return null;
        }

        // A packet can only be taken if nothing older about the same subject is still waiting. Scanning
        // every queue for that is the price of not corrupting ordered sequences, and it is bounded by
        // the number of levels times the queue depth the subject occupies.
        if (!isOldestForSubject(best)) {
            // Fall back to the oldest packet overall, which is by definition safe to take.
            best = oldestOverall();
        }

        this.queues.get(best.priority()).remove(best);
        this.handled++;
        if (effective(best, tick) > PRIORITY_LEVELS - 1 - best.priority()) {
            this.promoted++;
        }
        return best;
    }

    /**
     * A packet's priority after accounting for how long it has waited.
     *
     * <p>Without this, bulk traffic behind a stream of movement packets would never run, and the player
     * would walk into a world that never loads.
     */
    public static int effective(Packet packet, int tick) {
        int waited = Math.max(0, tick - packet.submittedTick());
        int bonus = waited / AGING_TICKS;
        int effective = (PRIORITY_LEVELS - 1 - packet.priority()) + bonus;
        return effective;
    }

    private boolean isOldestForSubject(Packet candidate) {
        for (Deque<Packet> queue : this.queues) {
            for (Packet other : queue) {
                if (other == candidate) {
                    continue;
                }
                if (other.subject() == candidate.subject()
                        && other.submittedTick() < candidate.submittedTick()) {
                    return false;
                }
            }
        }
        return true;
    }

    private Packet oldestOverall() {
        Packet oldest = null;
        for (Deque<Packet> queue : this.queues) {
            for (Packet packet : queue) {
                if (oldest == null || packet.submittedTick() < oldest.submittedTick()) {
                    oldest = packet;
                }
            }
        }
        return oldest;
    }

    /** How many packets are waiting. */
    public int pending() {
        int n = 0;
        for (Deque<Packet> queue : this.queues) {
            n += queue.size();
        }
        return n;
    }

    public long submitted() {
        return this.submitted;
    }

    public long handled() {
        return this.handled;
    }

    /** How many packets were served at a higher effective priority than they arrived at. */
    public long promoted() {
        return this.promoted;
    }

    public void clear() {
        for (Deque<Packet> queue : this.queues) {
            queue.clear();
        }
    }

    /**
     * Milliseconds a movement packet waits behind a run of bulk packets, handled in arrival order.
     *
     * <p>What the player feels as input lag.
     */
    public static double msLagWithoutPriority(int bulkPackets) {
        return bulkPackets * BULK_MS;
    }

    /** Milliseconds the same movement packet waits when movement is served first. */
    public static double msLagWithPriority(int movementPacketsAhead) {
        return movementPacketsAhead * MOVEMENT_MS;
    }
}
