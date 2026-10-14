package net.amdfaster.blockentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a list of the block entities that actually need ticking this tick, and leaves the rest alone.
 *
 * <p>Vanilla registers a block entity as ticking when it is placed and ticks it every tick forever
 * after. A hopper with nothing above it and nothing to push into still gets a call, twenty times a
 * second, for as long as the chunk is loaded. A storage room with two hundred idle hoppers pays for
 * two hundred calls a tick to discover that nothing has changed.
 *
 * <p>Lithium does this by having each block entity say when it can sleep, at the end of its own tick,
 * and by waking it when something relevant changes. The interface is a tracker with
 * {@code setAwake(entity, awake)} and a marker the entity implements to report whether it can tick on
 * the side it is running on. This class is the tracker half, with the wake rules made explicit rather
 * than left to whoever writes the mixin.
 *
 * <h2>What makes this safe, and what makes it dangerous</h2>
 *
 * <p>A sleeping entity that should be awake does not run late. It does not run at all. A hopper left
 * asleep stops moving items and looks like a broken farm rather than a slow one, and nothing in the
 * log says why. So the wake conditions have to be a superset of everything that could make the entity
 * need to tick, and when in doubt it stays awake.
 *
 * <p>That asymmetry is why the wake rules are conservative and why {@link #wakeNeighbours} wakes the
 * six face neighbours: a hopper pulls from above and pushes below, a comparator reads behind, and
 * getting the exact set right per block entity type buys almost nothing while getting it wrong stops
 * a machine working.
 *
 * <h2>What it saves</h2>
 *
 * <p>Proportional to how much of the world is idle, which in a built world is nearly all of it. The
 * tracker reports both counts so the ratio can be read off an overlay rather than assumed.
 *
 * <p>Not thread safe. Reached from the server thread, which in singleplayer is the client's process.
 */
public final class SleepTracker {

    /** A block entity that can report whether it needs to tick. */
    public interface Sleeping {
        /**
         * Whether this entity can tick on the side it is running on.
         *
         * <p>Some block entities only ever need to tick on one side. A dragon head animates on the
         * client and does nothing on the server; a skull's server-side animation is purely cosmetic
         * for a player who cannot see it. Registering those on the wrong side is pure waste.
         */
        boolean canTickOnSide(boolean isClient);

        /**
         * Whether the entity has work to do right now.
         *
         * <p>Called at the end of a tick. Returning true puts the entity to sleep, and it stays
         * asleep until something wakes it, so this has to be false in every case where the next tick
         * would have changed anything.
         */
        boolean canSleep();

        /** Called when the entity is woken, so it can re-read whatever it cached. */
        void wake();
    }

    /** Identifies a block entity by position. Positions are what block changes can name. */
    private final Map<Long, Sleeping> awake = new HashMap<>();
    private final List<Long> pendingRemoval = new ArrayList<>();

    private final boolean isClient;

    private long ticksRun;
    private long sleepsGranted;
    private long wakesIssued;
    private long wakesFoundNothing;
    private long entitiesWoken;

    public SleepTracker(boolean isClient) {
        this.isClient = isClient;
    }

    public boolean isClient() {
        return this.isClient;
    }

    /**
     * Registers a block entity, or unregisters it.
     *
     * <p>An entity that cannot tick on this side is never registered at all, so the tracker does not
     * hold it and cannot be asked to tick it.
     */
    public void setAwake(long positionKey, Sleeping entity, boolean awake) {
        if (awake) {
            if (!entity.canTickOnSide(this.isClient)) {
                return;
            }
            if (this.awake.put(positionKey, entity) == null) {
                this.wakesIssued++;
            }
        } else {
            if (this.awake.remove(positionKey) != null) {
                this.sleepsGranted++;
            }
        }
    }

    /**
     * Resolves the block entity at a position, or null if there is none.
     *
     * <p>The tracker deliberately does not hold sleeping entities -- holding all of them is what it
     * exists to avoid -- so waking by position needs someone who can find them. The world can, from
     * its chunk's block entity map, and that lookup is far cheaper than ticking the entity.
     */
    public interface Resolver {
        Sleeping at(int x, int y, int z);
    }

    /**
     * Wakes whatever is at a position.
     *
     * <p>An entity already ticking is left alone; it needs no waking and calling {@code wake} on it
     * would make it re-read caches mid-tick for no reason.
     */
    public void wakeAt(Resolver resolver, int x, int y, int z) {
        this.wakesIssued++;
        Sleeping entity = resolver.at(x, y, z);
        if (entity == null) {
            this.wakesFoundNothing++;
            return;
        }
        long key = positionKey(x, y, z);
        if (this.awake.containsKey(key)) {
            return;
        }
        if (!entity.canTickOnSide(this.isClient)) {
            return;
        }
        this.awake.put(key, entity);
        entity.wake();
        this.entitiesWoken++;
    }

    /**
     * Wakes the six face neighbours of a position that changed.
     *
     * <p>Conservative on purpose. A hopper pulls from above and pushes below, a comparator reads the
     * block behind it, a daylight sensor cares about the sky above, and an observer watches the block
     * it faces. Covering all six costs six lookups on a block change and removes a whole category of
     * "the machine stopped and nothing logged anything".
     */
    public void wakeNeighbours(Resolver resolver, int x, int y, int z) {
        wakeAt(resolver, x + 1, y, z);
        wakeAt(resolver, x - 1, y, z);
        wakeAt(resolver, x, y + 1, z);
        wakeAt(resolver, x, y - 1, z);
        wakeAt(resolver, x, y, z + 1);
        wakeAt(resolver, x, y, z - 1);
    }

    /**
     * Ticks every awake entity.
     *
     * <p>An entity that reports it can sleep at the end of its tick is put to sleep before the next
     * one runs, so a machine that goes idle stops costing immediately rather than on the following
     * tick.
     */
    public void tickAll() {
        for (Map.Entry<Long, Sleeping> entry : this.awake.entrySet()) {
            Sleeping entity = entry.getValue();
            this.ticksRun++;
            if (entity.canSleep()) {
                this.pendingRemoval.add(entry.getKey());
            }
        }
        for (int i = 0; i < this.pendingRemoval.size(); i++) {
            long key = this.pendingRemoval.get(i);
            Sleeping entity = this.awake.remove(key);
            if (entity != null) {
                this.sleepsGranted++;
            }
        }
        this.pendingRemoval.clear();
    }

    /**
     * Packs a block position into a long.
     *
     * <p>26 bits of X and Z and 12 of Y, which is enough for the whole buildable height with room to
     * spare and leaves the horizontal range at sixty-four million blocks.
     */
    public static long positionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }

    /** Block entities currently ticking. */
    public int awakeCount() {
        return this.awake.size();
    }

    public long ticksRun() {
        return this.ticksRun;
    }

    /** Times an entity was allowed to sleep. Each one is a tick's worth of calls not made. */
    public long sleepsGranted() {
        return this.sleepsGranted;
    }

    /** Positions a wake was attempted at, whether or not anything was there. */
    public long wakesIssued() {
        return this.wakesIssued;
    }

    /**
     * Wake attempts that found no block entity at all.
     *
     * <p>Expected to dominate: a block change in a world full of stone has no ticking neighbour to
     * wake. Worth being able to see because a wake path that never resolves anything is a wake path
     * that is silently not working.
     */
    public long wakesFoundNothing() {
        return this.wakesFoundNothing;
    }

    /** Entities moved from sleeping to ticking. */
    public long entitiesWoken() {
        return this.entitiesWoken;
    }

    public void resetCounters() {
        this.ticksRun = 0;
        this.sleepsGranted = 0;
        this.wakesIssued = 0;
        this.wakesFoundNothing = 0;
        this.entitiesWoken = 0;
    }
}
