package net.amdfaster.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the scheduled ticks that are due, without scanning every pending one.
 *
 * <p>Water spreading, repeater delay, crop growth and a dozen other things work by booking a tick for
 * some time in the future. Vanilla keeps the bookings in one collection and walks all of it every tick
 * looking for the ones that have come due. A large redstone contraption with flowing water holds
 * thousands of bookings, so that is 5,000 checks a tick, 100,000 a second, to find the 50 that are
 * actually due -- 99% of the work is looking at bookings that are not ready yet.
 *
 * <p>Bucketing by due time means a tick only opens the buckets at or before the current time, which
 * takes the same scenario to 50 checks.
 *
 * <h2>The failure this must not have</h2>
 *
 * <p>Missing a due tick is not a performance problem. Water stops flowing, a repeater stops propagating,
 * a crop stops growing -- the player sees a machine that no longer works and has no way to connect it to
 * a renderer mod. So the rule is that every booking at or before the current time is returned, and the
 * test for it uses bookings spread across many buckets with the current time well past most of them.
 *
 * <p>That includes the case where the clock goes backwards, which happens on a world rollback or when a
 * server corrects its time. Bookings that were already due before the rollback are still due, and
 * silently dropping them would leave a machine permanently stuck. They are returned as well.
 *
 * <h2>Why a bucket per tick and not a heap</h2>
 *
 * <p>A priority queue would give the earliest booking in log n, but taking every due one is a sequence of
 * removals, and the common case is that most bookings are due within a few ticks of each other. One list
 * per tick makes that a single lookup and no reordering, and the memory is bounded by the spread of due
 * times rather than by the number of bookings.
 *
 * <p>Not thread safe. Scheduled ticks are processed on the server thread, which in singleplayer lives
 * inside the client process.
 */
public final class ScheduledTickIndex {

    private final Map<Long, List<Booking>> buckets = new HashMap<>();

    private long scheduled;
    private long fired;
    private long checksSaved;
    private int peakPending;

    /** A booking: what was scheduled, where, and for when. */
    public record Booking(long id, int x, int y, int z, long dueTick) {}

    /**
     * Books a tick.
     *
     * @param id identifies what to run
     * @param dueTick the tick it should run on
     */
    public void schedule(long id, int x, int y, int z, long dueTick) {
        this.buckets.computeIfAbsent(dueTick, k -> new ArrayList<>())
                .add(new Booking(id, x, y, z, dueTick));
        this.scheduled++;
        this.peakPending = Math.max(this.peakPending, pending());
    }

    /**
     * Everything due at or before a tick.
     *
     * <p>Bookings are removed as they are returned, so calling this twice for the same tick returns the
     * same set once and nothing the second time.
     */
    public List<Booking> due(long tick) {
        List<Booking> found = new ArrayList<>();
        // A clock that went backwards leaves bookings due in the past. They are still due, and dropping
        // them would leave a machine permanently stuck, so they are collected too.
        List<Long> keys = new ArrayList<>(this.buckets.keySet());
        for (long key : keys) {
            if (key > tick) {
                continue;
            }
            List<Booking> bucket = this.buckets.remove(key);
            if (bucket != null) {
                found.addAll(bucket);
                this.fired += bucket.size();
            }
        }
        // Every booking that was not due still had to be examined by the scan this replaces.
        this.checksSaved += pending();
        return found;
    }

    /** Cancels a booking, for a block that was removed before its tick came. */
    public boolean cancel(long id, long dueTick) {
        List<Booking> bucket = this.buckets.get(dueTick);
        if (bucket == null) {
            return false;
        }
        boolean removed = bucket.removeIf(booking -> booking.id() == id);
        if (bucket.isEmpty()) {
            this.buckets.remove(dueTick);
        }
        return removed;
    }

    /** Cancels every booking at a position, for a block that changed. */
    public int cancelAt(int x, int y, int z) {
        int removed = 0;
        for (List<Booking> bucket : this.buckets.values()) {
            removed += bucket.size();
            bucket.removeIf(booking -> booking.x() == x && booking.y() == y && booking.z() == z);
            removed -= bucket.size();
        }
        this.buckets.values().removeIf(List::isEmpty);
        return removed;
    }

    public void clear() {
        this.buckets.clear();
    }

    /** How many bookings are waiting. */
    public int pending() {
        int n = 0;
        for (List<Booking> bucket : this.buckets.values()) {
            n += bucket.size();
        }
        return n;
    }

    /** The earliest tick anything is booked for, or {@link Long#MAX_VALUE} if nothing is. */
    public long nextDueTick() {
        long earliest = Long.MAX_VALUE;
        for (long key : this.buckets.keySet()) {
            earliest = Math.min(earliest, key);
        }
        return earliest;
    }

    /**
     * Whether a whole tick can be skipped.
     *
     * <p>A caller that would otherwise wake up every tick can sleep until this returns false, which is
     * where most of the saving actually lands: not in the lookup but in not running at all.
     */
    public boolean isIdleAt(long tick) {
        return nextDueTick() > tick;
    }

    public long scheduled() {
        return this.scheduled;
    }

    public long fired() {
        return this.fired;
    }

    /** Bookings the replaced scan would have had to examine. */
    public long checksSaved() {
        return this.checksSaved;
    }

    public int peakPending() {
        return this.peakPending;
    }

    /**
     * Checks the scan this replaces would have cost over a run of ticks.
     *
     * <p>A scan looks at everything pending on every tick, whether or not anything is due.
     */
    public long checksWithoutIndex(int pendingCount, int ticks) {
        return (long) pendingCount * ticks;
    }

    public double savedPercent(int pendingCount, int ticks) {
        long without = checksWithoutIndex(pendingCount, ticks);
        if (without == 0) {
            return 0.0;
        }
        return 100.0 * (without - this.checksSaved) / without;
    }
}
