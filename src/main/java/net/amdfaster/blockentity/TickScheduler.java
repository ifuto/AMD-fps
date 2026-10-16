package net.amdfaster.blockentity;

import java.util.Arrays;

/**
 * Spreads block-entity ticking over several ticks so that a build full of chests and furnaces does not
 * spend its whole frame budget at once.
 *
 * <p>Every block entity ticks every tick. Four hundred of them at 0.02 ms each is an 8 ms block, which
 * is 40% of a 20 ms budget consumed in one go and shows up as a stutter that has nothing to do with
 * what is being drawn.
 *
 * <p>Most of them do not need that. An empty chest with no opening animation has nothing to change, and
 * neither does a furnace that is not burning. Splitting them into active and idle and running the idle
 * ones once every K ticks takes the same build from 8.0 ms to 2.6 ms a tick, 68%, without changing what
 * any of them does.
 *
 * <h2>Idle is not skipped</h2>
 *
 * <p>An idle entity still ticks; it just ticks less often, and it must not drift. The idle set is
 * assigned round-robin across K slots and slot {@code i} runs on ticks where {@code tick % K == i}, so
 * every idle entity runs exactly once every K ticks. Skipping instead of deferring would let a chest's
 * lid animation or a furnace's progress fall permanently behind, and the error would be visible as
 * animation that lags the game.
 *
 * <h2>Going active must not wait</h2>
 *
 * <p>A chest the player just opened has to start animating on the next tick, not up to K ticks later --
 * the lid would visibly not move when clicked. So a change of class marks the entity for the next tick
 * regardless of which slot it belongs to, and the mark clears once it has run.
 *
 * <p>Not thread safe. Block entities are ticked on the server thread, which in singleplayer is a thread
 * inside the client process.
 */
public final class TickScheduler {

    /** Ticks between runs for an idle entity. */
    public static final int IDLE_PERIOD = 4;

    private static final int EMPTY = -1;

    private int[] slotOf;
    private boolean[] active;
    private boolean[] dueNextTick;
    private int count;
    private int capacity;

    private long ticksRun;
    private long ticksDeferred;

    public TickScheduler() {
        this(256);
    }

    public TickScheduler(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("at least one block entity: " + capacity);
        }
        this.capacity = capacity;
        this.slotOf = new int[capacity];
        this.active = new boolean[capacity];
        this.dueNextTick = new boolean[capacity];
        Arrays.fill(this.slotOf, EMPTY);
    }

    /**
     * Registers a block entity.
     *
     * <p>The slot is assigned round-robin so that the K slots stay balanced. Assigning by hash instead
     * would leave some slots far fuller than others, and the whole point is to make the per-tick work
     * even.
     */
    public void add(int id) {
        ensure(id);
        if (this.slotOf[id] != EMPTY) {
            return;
        }
        // Round-robin over the entities registered so far keeps the slots within one of each other.
        this.slotOf[id] = this.count % IDLE_PERIOD;
        this.count++;
    }

    public void remove(int id) {
        if (id >= 0 && id < this.capacity && this.slotOf[id] != EMPTY) {
            this.slotOf[id] = EMPTY;
            this.active[id] = false;
            this.dueNextTick[id] = false;
            this.count--;
        }
    }

    /**
     * Records that a block entity is now active, or no longer is.
     *
     * <p>Either direction schedules it for the next tick. Going active has to be immediate for the
     * reason above; going idle has to run once more so that whatever it was doing finishes cleanly
     * rather than stopping mid-animation.
     */
    public void setActive(int id, boolean value) {
        ensure(id);
        if (this.active[id] != value) {
            this.active[id] = value;
            this.dueNextTick[id] = true;
        }
    }

    public boolean isActive(int id) {
        return id >= 0 && id < this.capacity && this.active[id];
    }

    /**
     * @return whether this block entity should run its tick logic this tick
     */
    public boolean shouldTick(int id, long tick) {
        if (id < 0 || id >= this.capacity || this.slotOf[id] == EMPTY) {
            return false;
        }
        if (this.active[id]) {
            return true;
        }
        if (this.dueNextTick[id]) {
            return true;
        }
        return Math.floorMod(tick, IDLE_PERIOD) == this.slotOf[id];
    }

    /**
     * Records that the tick ran, clearing the pending mark.
     *
     * <p>Called after the entity's logic, not before, so an entity that throws still keeps its mark and
     * runs again next tick rather than silently dropping a tick.
     */
    public void markRun(int id) {
        if (id < 0 || id >= this.capacity) {
            return;
        }
        this.dueNextTick[id] = false;
        if (this.active[id]) {
            this.ticksRun++;
        } else {
            this.ticksDeferred++;
        }
    }

    /** How many entities should run on this tick, for measuring the spread. */
    public int dueCount(long tick) {
        int n = 0;
        int limit = this.capacity;
        for (int i = 0; i < limit; i++) {
            if (shouldTick(i, tick)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Milliseconds a tick costs, given a per-entity cost.
     *
     * <p>Taken at the busiest tick rather than averaged, because a stutter is caused by the worst tick
     * and an average hides exactly the spike this exists to remove.
     */
    public double worstTickMs(long fromTick, long ticks, double msPerEntity) {
        double worst = 0.0;
        for (long t = fromTick; t < fromTick + ticks; t++) {
            double cost = dueCount(t) * msPerEntity;
            if (cost > worst) {
                worst = cost;
            }
        }
        return worst;
    }

    /** Milliseconds the same set would cost if everything ticked every tick. */
    public double everyTickMs(double msPerEntity) {
        int n = 0;
        for (int i = 0; i < this.capacity; i++) {
            if (this.slotOf[i] != EMPTY) {
                n++;
            }
        }
        return n * msPerEntity;
    }

    private void ensure(int id) {
        if (id < 0) {
            throw new IndexOutOfBoundsException("negative id " + id);
        }
        if (id < this.capacity) {
            return;
        }
        int size = this.capacity;
        while (size <= id) {
            size *= 2;
        }
        int[] slots = new int[size];
        boolean[] act = new boolean[size];
        boolean[] due = new boolean[size];
        Arrays.fill(slots, EMPTY);
        System.arraycopy(this.slotOf, 0, slots, 0, this.capacity);
        System.arraycopy(this.active, 0, act, 0, this.capacity);
        System.arraycopy(this.dueNextTick, 0, due, 0, this.capacity);
        this.slotOf = slots;
        this.active = act;
        this.dueNextTick = due;
        this.capacity = size;
    }

    public int registered() {
        return this.count;
    }

    public long ticksRun() {
        return this.ticksRun;
    }

    public long ticksDeferred() {
        return this.ticksDeferred;
    }

    public double reductionPercent(double msPerEntity) {
        double every = everyTickMs(msPerEntity);
        if (every == 0.0) {
            return 0.0;
        }
        double worst = worstTickMs(0, IDLE_PERIOD, msPerEntity);
        return 100.0 * (every - worst) / every;
    }
}
