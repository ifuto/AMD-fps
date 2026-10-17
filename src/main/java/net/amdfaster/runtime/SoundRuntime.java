package net.amdfaster.runtime;

import net.amdfaster.sound.SoundPressureController;
import net.amdfaster.sound.SoundWorkOffloader;
import net.amdfaster.sound.StreamingSoundSplitter;
import net.amdfaster.sound.AdaptiveSoundPressure;
import net.amdfaster.sound.CategoryBudgetAllocator;
import net.amdfaster.sound.CriticalSoundWhitelist;
import net.amdfaster.sound.DuplicateSoundLimiter;
import net.amdfaster.sound.LoopingSoundDamper;
import net.amdfaster.sound.RegionBoundarySmoothing;
import net.amdfaster.sound.SlidingWindowRateLimiter;
import net.amdfaster.sound.SoundDirectionPrioritizer;
import net.amdfaster.sound.SourceFairnessAllocator;

/**
 * Runs the sound decisions in one place, in the order that makes them compose.
 *
 * <p>The sound components each answer one question: is this sound on the protected list, has it played too
 * often, is it a duplicate, is its category's share full, is its source hogging the pool, is it in front of
 * the player. Asked in isolation they are all correct, and asked in the wrong order they contradict each
 * other -- a rate limiter that runs after the budget has already refused a sound will count a refusal as a
 * play, and a pressure gauge fed by requests that were refused will read a busy engine in a quiet scene.
 *
 * <p>This is the order, and each stage only sees the sounds the previous one let through:
 *
 * <ol>
 *   <li><b>Protected.</b> A sound on the critical list is played and skips everything else. Checking it last
 *       would mean the player's hurt sound was already refused by the time the list was consulted.</li>
 *   <li><b>Rate.</b> A sound that has played its share this window is dropped, before any budget is spent
 *       counting it.</li>
 *   <li><b>Duplicate.</b> The same sound from the same block at the same moment is dropped.</li>
 *   <li><b>Region.</b> A cell whose neighbourhood is already loud is dropped, counted with its neighbours so
 *       the decision does not flip at a cell boundary.</li>
 *   <li><b>Category.</b> The category's share of the pool is checked, which is where the pressure gauge is
 *       fed -- from sounds that got this far, not from every request.</li>
 *   <li><b>Source fairness.</b> One source cannot take the whole pool.</li>
 *   <li><b>Direction.</b> When the pool is contested, a sound behind the player is dropped.</li>
 * </ol>
 *
 * <h2>Why the pressure gauge is fed mid-pipeline</h2>
 *
 * <p>Pressure is meant to measure how hard the engine is being asked to make sound. Feeding it every request
 * would include the ones a rate limiter or a duplicate check already refused, which are not work the engine
 * will do, and the gauge would tighten the limits in response to load that does not exist.
 *
 * <h2>Every per-tick window is cleared in one place</h2>
 *
 * <p>Four of the components keep per-tick state, and each has its own {@code endTick}. Missing one means that
 * component's counts grow for the whole session and it eventually refuses everything, which looks like the
 * world going quiet for no reason. So they are all cleared here, and a test drives enough ticks to catch a
 * component that was left out.
 *
 * <h2>The verdict says why</h2>
 *
 * <p>A refusal that does not say which stage refused it cannot be debugged, and a player reporting "my sounds
 * are missing" gives nothing to work with. So the verdict names the stage and the counters are per stage,
 * which is what makes the whole thing tunable rather than a black box that occasionally eats a sound.
 *
 * <p>Not thread safe. Sounds are decided one at a time on the thread that plays them.
 */
public final class SoundRuntime {

    /** Why a sound was played or refused. */
    public enum Verdict {
        /** On the critical list, so it played regardless of everything else. */
        PROTECTED,
        /** Played normally. */
        PLAYED,
        /** Refused: it has played its share of this window. */
        RATE_LIMITED,
        /** Refused: the same sound is already playing from this block. */
        DUPLICATE,
        /** Refused: its neighbourhood is already loud. */
        REGION_FULL,
        /** Refused: its category's share of the pool is full. */
        CATEGORY_FULL,
        /** Refused: its source is already holding its share. */
        SOURCE_CAPPED,
        /** Refused: the pool is contested and this one is behind the player. */
        BEHIND_CAMERA
    }

    /** The outcome of one decision. */
    public record Decision(Verdict verdict) {
        /** Whether the sound should be started. */
        public boolean plays() {
            return this.verdict == Verdict.PROTECTED || this.verdict == Verdict.PLAYED;
        }
    }

    private final CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();
    private final SlidingWindowRateLimiter rate = new SlidingWindowRateLimiter();
    private final DuplicateSoundLimiter duplicates = new DuplicateSoundLimiter();
    private final RegionBoundarySmoothing regions = new RegionBoundarySmoothing();
    private final CategoryBudgetAllocator categories = new CategoryBudgetAllocator();
    private final SourceFairnessAllocator fairness = new SourceFairnessAllocator();
    private final AdaptiveSoundPressure pressure = new AdaptiveSoundPressure();
    private final LoopingSoundDamper damper = new LoopingSoundDamper();

    private long tick;

    private long offered;
    private long played;
    private long rateLimited;
    private long duplicatesDropped;
    private long regionRefused;
    private long categoryRefused;
    private long sourceCapped;
    private long behindCamera;

    /** Adapts how many sounds may play to the measured tick cost. */
    private final SoundPressureController pressure = new SoundPressureController();
    /** Moves sound work off the render thread. */
    private final SoundWorkOffloader offload = new SoundWorkOffloader();
    /** Streams long sounds instead of decoding them whole. */
    private final StreamingSoundSplitter streaming = new StreamingSoundSplitter();

    public SoundRuntime() {
        this.whitelist.protectDefaults();
    }

    /**
     * Decides whether a sound may play.
     *
     * @param sound the sound identifier
     * @param source what is making it, such as a mod's namespace
     * @param category what kind of sound it is
     * @param x the source x
     * @param y the source y
     * @param z the source z
     * @param angleFromFacing how far it is from where the player is looking, in degrees
     * @param fieldOfView the player's field of view setting
     * @param poolContested whether the audio pool is nearly full
     * @return the verdict
     */
    public Decision decide(String sound, String source, CategoryBudgetAllocator.Category category,
            double x, double y, double z, double angleFromFacing, double fieldOfView,
            boolean poolContested) {
        if (sound == null || sound.isEmpty()) {
            throw new IllegalArgumentException("a sound is required");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("a source is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("a category is required");
        }
        this.offered++;

        // 1. Protected. Checked first, because checking it last would mean the player's hurt sound was
        // already refused by the time the list was consulted.
        if (this.whitelist.isCritical(sound)) {
            this.played++;
            return new Decision(Verdict.PROTECTED);
        }

        // 2. Rate. Dropped before any budget is spent counting it.
        if (!this.rate.allow(sound, (int) this.tick)) {
            this.rateLimited++;
            return new Decision(Verdict.RATE_LIMITED);
        }

        // 3. Duplicate: the same sound from the same block in the same tick.
        if (!this.duplicates.allow(x, y, z, sound)) {
            this.duplicatesDropped++;
            return new Decision(Verdict.DUPLICATE);
        }

        // 4. Region, counted with its neighbours so the decision does not flip at a cell boundary.
        if (!this.regions.allow(x, z)) {
            this.regionRefused++;
            return new Decision(Verdict.REGION_FULL);
        }

        // 5. Category, which is where the pressure gauge is fed -- from sounds that got this far, not from
        // every request.
        this.pressure.tick(1);
        if (!this.categories.allow(category)) {
            this.categoryRefused++;
            return new Decision(Verdict.CATEGORY_FULL);
        }

        // 6. Source fairness.
        if (!this.fairness.allow(source, false)) {
            this.sourceCapped++;
            return new Decision(Verdict.SOURCE_CAPPED);
        }

        // 7. Direction, only when the pool is contested.
        if (poolContested && !SoundDirectionPrioritizer.inFront(angleFromFacing, fieldOfView)) {
            this.behindCamera++;
            return new Decision(Verdict.BEHIND_CAMERA);
        }

        this.played++;
        return new Decision(Verdict.PLAYED);
    }

    /**
     * Ends a tick, clearing every per-tick window.
     *
     * <p>Four components keep per-tick state and each has its own {@code endTick}: the duplicate limiter, the
     * region counters, the category shares and the source caps. Missing one means that component's counts
     * grow for the whole session and it eventually refuses everything, which looks like the world going quiet
     * for no reason the player can see. The first draft of this class omitted the duplicate limiter, so a
     * sound that played five times in one tick could never play again.
     */
    public void endTick() {
        this.tick++;
        this.duplicates.endTick();
        this.regions.endTick();
        this.categories.endTick();
        this.fairness.endTick();
        this.damper.tick(this.pressure.pressure() >= AdaptiveSoundPressure.SATURATION);
    }

    /** The current tick, which the rate limiter windows against. */
    public long tick() {
        return this.tick;
    }

    /** Adds a sound to the protected list, so a mod can mark one the player must hear. */
    public void protect(String sound) {
        this.whitelist.protect(sound);
    }

    /** Whether a sound is on the protected list. */
    public boolean isProtected(String sound) {
        return this.whitelist.isCritical(sound);
    }

    /** The current audio pressure, from 0 to 1. */
    public double pressure() {
        return this.pressure.pressure();
    }

    /** How the damper is currently treating looping sounds, as a volume fraction. */
    public double loopingVolume() {
        return this.damper.volume();
    }

    public long offered() {
        return this.offered;
    }

    public long played() {
        return this.played;
    }

    public long rateLimited() {
        return this.rateLimited;
    }

    public long duplicatesDropped() {
        return this.duplicatesDropped;
    }

    public long regionRefused() {
        return this.regionRefused;
    }

    public long categoryRefused() {
        return this.categoryRefused;
    }

    public long sourceCapped() {
        return this.sourceCapped;
    }

    public long behindCamera() {
        return this.behindCamera;
    }

    /** How many were refused in total. */
    public long refused() {
        return this.rateLimited + this.duplicatesDropped + this.regionRefused
                + this.categoryRefused + this.sourceCapped + this.behindCamera;
    }

    /**
     * The share of the sounds offered that were refused.
     *
     * <p>Zero before anything has been offered. A runtime that has decided nothing has technically refused
     * nothing, and reporting that as a rate would describe an unused one as though it had measured something.
     */
    public double refusedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * refused() / this.offered;
    }

    /** Resets the counters and the tick, on a world change. */
    public void reset() {
        this.tick = 0;
        this.offered = 0;
        this.played = 0;
        this.rateLimited = 0;
        this.duplicatesDropped = 0;
        this.regionRefused = 0;
        this.categoryRefused = 0;
        this.sourceCapped = 0;
        this.behindCamera = 0;
        this.duplicates.endTick();
        this.regions.endTick();
        this.categories.endTick();
        this.fairness.endTick();
    }

    /** The sound pressure controller. */
    public SoundPressureController pressure() {
        return this.pressure;
    }

    /** The sound work offloader. */
    public SoundWorkOffloader offload() {
        return this.offload;
    }

    /** The streaming sound splitter. */
    public StreamingSoundSplitter streaming() {
        return this.streaming;
    }
}
