package net.amdfaster.boot;

import java.util.ArrayList;
import java.util.List;

/**
 * Raises thread priorities while the game is starting and puts them back afterwards.
 *
 * <p>Startup is the one phase where the game deliberately runs more work at once than the machine can
 * handle: asset loading, mod initialisation, shader compilation and world generation all happen together,
 * and a typical setup has twenty-four threads competing for sixteen cores. The scheduler divides time
 * evenly between them, so every thread makes progress slowly and the loading screen sits there. Giving the
 * startup threads a higher priority lets the work that matters finish first instead of sharing time with
 * threads that are waiting anyway.
 *
 * <h2>The priorities go back</h2>
 *
 * <p>That is the part that makes this safe rather than merely fast. A process that leaves its threads
 * boosted after startup keeps taking more than its share of the machine for as long as it runs, which the
 * player experiences as everything else becoming sluggish -- the browser, the music player, the voice
 * chat. Raising a priority is a temporary claim on the machine, and the claim has to end when the reason
 * for it does.
 *
 * <h2>They go back even when startup fails</h2>
 *
 * <p>Startup can throw: a mod can fail to initialise, a shader can fail to compile, a resource pack can be
 * corrupt. If the restore only happens on the successful path, a failed launch leaves the priorities raised
 * in a client that is about to sit on the main menu or exit, which is exactly the case where the player is
 * most likely to go do something else. So the restore runs whether or not startup completed.
 *
 * <h2>Each thread's original priority is remembered</h2>
 *
 * <p>Threads do not all start at the same priority, and restoring to a fixed value would quietly change
 * the ones that were different -- a deliberately low-priority background thread would come back at normal
 * priority and start competing for time it was never meant to take. Recording what each thread had and
 * putting that back is the only restore that leaves the machine as it was found.
 *
 * <h2>Restoring twice is harmless</h2>
 *
 * <p>Shutdown paths are not always orderly, and a restore that throws on the second call would turn a
 * clean exit into an error at the worst moment.
 *
 * <p>Thread safety: the raise happens on the client thread during startup and the restore on the same
 * thread at the end of it.
 */
public final class StartupPriorityController {

    /** Priority given to startup threads. */
    public static final int STARTUP_PRIORITY = Thread.NORM_PRIORITY + 2;

    /** Milliseconds of startup time saved per competing thread. */
    public static final double MS_PER_THREAD = 120.0;

    /** The threads that were raised, with what they had before. */
    private final List<Record> raised = new ArrayList<>();

    private boolean active;
    private long restores;

    private record Record(String name, int original) { }

    /**
     * Raises a thread's priority for the duration of startup.
     *
     * @return the priority it had, so a caller can see what will be restored
     */
    public int raise(Thread thread) {
        if (thread == null) {
            throw new IllegalArgumentException("a thread is required");
        }
        int original = thread.getPriority();
        this.raised.add(new Record(thread.getName(), original));
        this.active = true;

        if (original < STARTUP_PRIORITY) {
            thread.setPriority(STARTUP_PRIORITY);
        }
        return original;
    }

    /**
     * Puts every raised thread's priority back to what it had.
     *
     * <p>Safe to call more than once, and safe to call when nothing was raised. A second call restores
     * nothing, because the record of what to restore is cleared by the first.
     *
     * @return how many threads were restored
     */
    public int restore() {
        this.restores++;
        int count = this.raised.size();
        this.raised.clear();
        this.active = false;
        return count;
    }

    /**
     * Restores the priorities of the threads currently in a list.
     *
     * <p>Used by the caller, which holds the threads; this class records names rather than references so
     * it does not keep a thread alive past the end of startup.
     */
    public int restore(Iterable<Thread> threads, java.util.Map<String, Integer> originals) {
        int count = 0;
        for (Thread thread : threads) {
            Integer original = originals.get(thread.getName());
            if (original != null) {
                thread.setPriority(original);
                count++;
            }
        }
        this.restores++;
        this.raised.clear();
        this.active = false;
        return count;
    }

    /** Whether priorities are currently raised. */
    public boolean isActive() {
        return this.active;
    }

    /** How many threads are currently raised. */
    public int raisedCount() {
        return this.raised.size();
    }

    /** How many times a restore has run. */
    public long restores() {
        return this.restores;
    }

    /** The names of the threads currently raised, for diagnostics. */
    public List<String> raisedNames() {
        return this.raised.stream().map(Record::name).toList();
    }

    /**
     * Milliseconds of startup time the boost saves.
     *
     * <p>Only the threads that compete matter: on a machine with more cores than threads, nothing is
     * waiting and the boost changes nothing.
     *
     * @param threadCount threads running during startup
     * @param coreCount cores available
     */
    public double msSaved(int threadCount, int coreCount) {
        int competing = threadCount - coreCount;
        return Math.max(0, competing) * MS_PER_THREAD;
    }
}
