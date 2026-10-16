package net.amdfaster.sound;

import java.util.HashMap;
import java.util.Map;

/**
 * Shares the sound channel budget fairly, and tightens it when the game is under load.
 *
 * <p>A sound engine has a fixed number of channels, and when more sounds want to play than there are
 * channels, something has to be refused. Refusing whichever arrives last is simple and wrong: a single
 * machine firing ten times a second takes the whole budget, and the footsteps, the damage sound and the door
 * behind it all go silent. The player hears one machine and nothing else, which is not a quieter game -- it is
 * a game that has stopped telling them things.
 *
 * <p>Dividing the budget between sources means no single one can take it all, and the sounds that matter
 * still get through.
 *
 * <h2>Sounds the player acts on are never refused</h2>
 *
 * <p>Taking damage, a creeper hissing, an anvil landing, a level-up. Those are not atmosphere, they are
 * information the player changes what they are doing because of, and losing one to a budget is worse than
 * losing ten machine noises. So they bypass the budget entirely, whatever else is playing.
 *
 * <h2>Pressure rises with load and falls when it passes</h2>
 *
 * <p>Sound costs mixing time on the same thread that is already struggling, so when the tick is running long
 * the budget tightens and the game sheds atmosphere rather than ticks. When the tick recovers, the budget
 * comes back -- and the gap between those two points is what stops it fluttering between them every tick.
 *
 * <h2>There is a floor on the tightening</h2>
 *
 * <p>A budget that shrank to nothing under load would silence the game exactly when the player is fighting
 * something difficult, which is when they most need to hear it. The floor is the level at which the world
 * still sounds like itself, and pressure does not go below it however late the tick is.
 *
 * <h2>Why fairness is per source and not per distance</h2>
 *
 * <p>Distance already decides whether a sound is audible at all, and {@link SoundCuller} handles that. Two
 * sounds from the same machine are at the same distance and both are audible; the problem is that there are
 * ten of them a second from one source and one a second from another. Fairness is about who is taking the
 * budget, not about how far away they are.
 *
 * <p>Not thread safe. Sounds are played on the client thread.
 */
public final class SoundPressureController {

    /** Channels the engine has. */
    public static final int CHANNEL_BUDGET = 64;

    /** The most channels one source may hold at once. */
    public static final int PER_SOURCE_LIMIT = 4;

    /** The budget does not tighten below this. */
    public static final int MIN_BUDGET = 24;

    /** Milliseconds of tick time above which pressure rises. */
    public static final double PRESSURE_ABOVE_MS = 45.0;

    /** Milliseconds below which pressure eases. */
    public static final double PRESSURE_BELOW_MS = 35.0;

    /** How much one step of pressure changes the budget by. */
    public static final int PRESSURE_STEP = 8;

    /** Milliseconds mixing one channel costs. */
    public static final double MS_PER_CHANNEL = 0.02;

    private final Map<String, Integer> perSource = new HashMap<>();

    private int budget = CHANNEL_BUDGET;
    private int inUse;

    private long offered;
    private long played;
    private long refusedBudget;
    private long refusedSource;
    private long feedback;
    private long pressureChanges;

    /**
     * Adjusts the budget for one tick.
     *
     * @param mspt how long the last tick took
     */
    public void adjust(double mspt) {
        if (mspt < 0.0) {
            throw new IllegalArgumentException("a negative tick time: " + mspt);
        }
        if (mspt > PRESSURE_ABOVE_MS && this.budget > MIN_BUDGET) {
            // Sound costs mixing time on the same thread that is already struggling, so the game sheds
            // atmosphere rather than ticks.
            this.budget = Math.max(MIN_BUDGET, this.budget - PRESSURE_STEP);
            this.pressureChanges++;
        } else if (mspt < PRESSURE_BELOW_MS && this.budget < CHANNEL_BUDGET) {
            // The gap between the two thresholds is what stops it fluttering between them every tick.
            this.budget = Math.min(CHANNEL_BUDGET, this.budget + PRESSURE_STEP);
            this.pressureChanges++;
        }
    }

    /**
     * Whether a sound may take a channel.
     *
     * @param source what is making the sound, so one machine cannot take the whole budget
     * @param playerFeedback whether the player acts on it -- damage, a creeper, an anvil, a level-up
     */
    public boolean canPlay(String source, boolean playerFeedback) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("a source is required");
        }
        this.offered++;

        if (playerFeedback) {
            // Those are not atmosphere, they are information the player changes what they are doing because
            // of, and losing one to a budget is worse than losing ten machine noises.
            this.feedback++;
            this.played++;
            return true;
        }

        int held = this.perSource.getOrDefault(source, 0);
        if (held >= PER_SOURCE_LIMIT) {
            this.refusedSource++;
            return false;
        }
        if (this.inUse >= this.budget) {
            this.refusedBudget++;
            return false;
        }
        this.perSource.put(source, held + 1);
        this.inUse++;
        this.played++;
        return true;
    }

    /** Releases a channel when a sound finishes. */
    public void release(String source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("a source is required");
        }
        Integer held = this.perSource.get(source);
        if (held != null && held > 0) {
            this.perSource.put(source, held - 1);
            this.inUse = Math.max(0, this.inUse - 1);
        }
    }

    /** How many channels a source is holding. */
    public int heldBy(String source) {
        return this.perSource.getOrDefault(source, 0);
    }

    /** The current budget. */
    public int budget() {
        return this.budget;
    }

    /** How many channels are in use. */
    public int inUse() {
        return this.inUse;
    }

    public long offered() {
        return this.offered;
    }

    public long played() {
        return this.played;
    }

    /** How many were refused because the budget was full. */
    public long refusedBudget() {
        return this.refusedBudget;
    }

    /** How many were refused because their source was already at the limit. */
    public long refusedSource() {
        return this.refusedSource;
    }

    /** How many bypassed the budget because the player acts on them. */
    public long feedback() {
        return this.feedback;
    }

    public long pressureChanges() {
        return this.pressureChanges;
    }

    /** Milliseconds of mixing done. */
    public double msMixing() {
        return this.played * MS_PER_CHANNEL;
    }

    /**
     * The share of the offered sounds that were refused.
     *
     * <p>Zero before any sound has been offered. A controller that has heard nothing has technically refused
     * everything, but reporting that as a rate would describe an unused one as though it had decided
     * something.
     */
    public double refusedPercent() {
        return this.offered == 0 ? 0.0
                : 100.0 * (this.refusedBudget + this.refusedSource) / this.offered;
    }
}
