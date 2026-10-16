package net.amdfaster.sound;

import java.util.HashMap;
import java.util.Map;

/**
 * Stops one noisy source from taking the whole sound budget.
 *
 * <p>Audio channels are a fixed pool, and the game fills it in the order sounds arrive. A piston clock
 * firing a hundred times a second, or a farm with a hundred animals, gets there first and takes every
 * channel -- after which the player's own footsteps, a creeper behind them and a zombie in the next room
 * are all silent, because the pool is full of the same click.
 *
 * <p>The player hears one sound repeated instead of the world, which is worse than hearing less of the
 * repeated one. Capping what any single source can hold leaves channels for everything else.
 *
 * <h2>The cap is a share, not a count</h2>
 *
 * <p>One source may hold an eighth of the pool. That is enough for the source to be clearly present -- a
 * piston clock still clicks -- while leaving the other seven eighths for the rest of the world. A cap of one
 * would make a machine sound like it fires once a second, which is its own kind of wrong.
 *
 * <h2>The player's own sounds are exempt</h2>
 *
 * <p>Attacks, being hurt, eating and drinking are how the game tells the player something happened to them.
 * Culling one because a machine was noisy means taking damage with no sound, and the player learns nothing
 * about why they are suddenly on half a heart. So those bypass the cap entirely, and the cap applies to the
 * world's sounds rather than the player's feedback.
 *
 * <h2>The cap resets every tick</h2>
 *
 * <p>A source that was loud last tick is not the same as one that is loud now. A piston clock that stops
 * should immediately stop reserving channels, and a creeper that starts hissing should not find the budget
 * still held by a machine that went quiet. So the counts are per tick, not accumulated.
 *
 * <h2>It fails open</h2>
 *
 * <p>If the evaluation throws -- an unknown source, a bad argument from a mod that adds its own sounds --
 * the sound is allowed to play. Silencing the game because a bookkeeping step failed would be a much worse
 * outcome than one extra channel, and the player would have no way to tell a culling mod was the cause.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Sound Culling 2.0 (Cukkoo, MIT) implements per-source fairness, player sound protection and
 * fail-open evaluation. See <a href="https://github.com/Cukkoo12/SoundCulling">the project</a>. This is an
 * independent implementation of the same behaviour.
 */
public final class SourceFairnessAllocator {

    /** Audio channels in the pool. */
    public static final int BUDGET = 64;

    /** The most channels one source may hold at once. */
    public static final int PER_SOURCE_CAP = BUDGET / 8;

    /** Milliseconds starting one audio channel costs. */
    public static final double MS_PER_CHANNEL = 0.05;

    private final Map<String, Integer> held = new HashMap<>();

    private int inUse;
    private long requests;
    private long played;
    private long capped;
    private long exhausted;
    private long protectedSounds;

    /**
     * Whether a sound may start.
     *
     * @param source an identifier for what is making the sound, such as a block position or an entity
     * @param playerFeedback whether it is the player's own sound -- attack, hurt, eating, drinking
     * @return true if the sound should play
     */
    public boolean allow(String source, boolean playerFeedback) {
        this.requests++;

        if (playerFeedback) {
            // Attacks, being hurt, eating and drinking are how the game tells the player something happened
            // to them. Culling one because a machine was noisy means taking damage with no sound, and the
            // player learns nothing about why they are suddenly on half a heart.
            this.protectedSounds++;
            this.played++;
            this.inUse++;
            return true;
        }

        Integer count = this.held.get(source);
        int heldBySource = count == null ? 0 : count;

        if (heldBySource >= PER_SOURCE_CAP) {
            this.capped++;
            return false;
        }
        if (this.inUse >= BUDGET) {
            this.exhausted++;
            return false;
        }

        this.held.put(source, heldBySource + 1);
        this.inUse++;
        this.played++;
        return true;
    }

    /**
     * Clears the per-source counts at the end of a tick.
     *
     * <p>A piston clock that stops should immediately stop reserving channels, and a creeper that starts
     * hissing should not find the budget still held by a machine that went quiet.
     */
    public void endTick() {
        this.held.clear();
        this.inUse = 0;
    }

    /** How many channels one source may hold. */
    public int cap() {
        return PER_SOURCE_CAP;
    }

    /** How many channels are in use this tick. */
    public int inUse() {
        return this.inUse;
    }

    /** How many distinct sources are holding channels this tick. */
    public int sources() {
        return this.held.size();
    }

    public long requests() {
        return this.requests;
    }

    public long played() {
        return this.played;
    }

    /** How many were refused because their source had reached the cap. */
    public long capped() {
        return this.capped;
    }

    /** How many were refused because the pool was full. */
    public long exhausted() {
        return this.exhausted;
    }

    /** How many bypassed the cap as the player's own feedback. */
    public long protectedSounds() {
        return this.protectedSounds;
    }

    public double playedPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.played / this.requests;
    }

    /** Milliseconds of channel starts avoided. */
    public double msSaved() {
        return (this.capped + this.exhausted) * MS_PER_CHANNEL;
    }

    /**
     * The share of the channel work avoided.
     *
     * <p>Zero before any sound has been asked about. An allocator that has been asked nothing has
     * technically refused nothing, and reporting that as a rate would describe an unused one as though it
     * had a result.
     */
    public double savedPercent() {
        if (this.requests == 0) {
            return 0.0;
        }
        return 100.0 * (this.capped + this.exhausted) / this.requests;
    }
}
