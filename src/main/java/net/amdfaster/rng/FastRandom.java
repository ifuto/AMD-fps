package net.amdfaster.rng;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Faster random numbers, for the paths where the sequence is not observable.
 *
 * <h2>The constraint that decides the whole design</h2>
 *
 * <p>Swapping Minecraft's generator for a faster algorithm is not free, and the price is not performance.
 * A different algorithm produces a different sequence from the same seed, so world generation changes: the
 * caves are different, lava pools land somewhere else, azalea trees appear in different places. That is a
 * visible change to a world the player already has, and it is the one thing this mod is not allowed to do --
 * the culling is required to leave the picture identical, and generation is a much bigger change than
 * culling.
 *
 * <p>So this does not replace the generator. It offers a faster one for the draws whose sequence nobody can
 * observe: particle jitter, ambient splash positions, cosmetic variation. Those are random by definition and
 * not persisted, so a different sequence is not a difference the player can detect or the world can record.
 *
 * <h2>Refusal is the feature</h2>
 *
 * <p>{@link Usage} marks each kind of draw as acceleratable or not, and the draw methods throw for the ones
 * that are not. Throwing rather than silently falling back is deliberate: a worldgen draw that quietly used
 * a different generator would change terrain, and that would surface as "the mod changed my world" with
 * nothing in the code to point at. A caller that wants randomness for worldgen has to go to Minecraft's own
 * {@code RandomSource}, which is the correct answer, not a worse one.
 *
 * <h2>What makes it faster</h2>
 *
 * <p>Two things, and they are separate wins. Java 17's LXM generators ({@code L64X128MixRandom} and its
 * relatives) advance a splittable state with fewer operations than {@code java.util.Random}, whose seed is an
 * {@code AtomicLong} updated by compare-and-set -- so every draw on the legacy path is a contended atomic,
 * and several threads drawing at once serialise on it. A per-thread generator removes the contention, and
 * LXM removes the arithmetic.
 *
 * <p>LXM generators cost more to construct than they save on a single draw, which is why this pools one per
 * thread rather than making one per call. Minecraft re-uses its generators for the same reason.
 *
 * <h2>JVMs without LXM</h2>
 *
 * <p>Not every JVM ships the LXM implementations, and headless builds in particular often do not. When the
 * generator is missing this falls back to {@link ThreadLocalRandom} and says so, rather than failing to load
 * or pretending to be accelerated. {@link ThreadLocalRandom} is still faster than the legacy path because it
 * removes the shared atomic, so the fallback is a real improvement and not a stub.
 *
 * <h2>What this does not claim</h2>
 *
 * <p>Published figures for this kind of substitution range from 4-8x on typical operations to 100x on the
 * worst legacy path. Neither number is repeated here, because neither was measured on this code and a
 * speedup quoted from someone else's machine is not a result. The counters below say how many draws were
 * accelerated; a benchmark on real hardware decides what that is worth.
 *
 * <p>Thread safe.
 */
public final class FastRandom {

    /** The LXM generator this prefers, when the JVM provides it. */
    public static final String PREFERRED_GENERATOR = "L64X128MixRandom";

    /**
     * Which kind of draw is being asked for.
     *
     * <p>The split is whether the sequence is observable: either persisted into the world, or visible to the
     * player as a difference they could notice between two runs of the same seed.
     */
    public enum Usage {
        /** Particle velocity, lifetime and size jitter. Client-side, transient, random by definition. */
        PARTICLES(true),

        /** Rain and snow splash positions, cloud drift, ambient variation. */
        AMBIENT(true),

        /** Cosmetic variation in rendering: glint phase, shake offsets. */
        RENDER_JITTER(true),

        /** Terrain, caves, structures and decoration. Changing this changes the world. */
        WORLDGEN(false),

        /** Loot rolls. Reproducible from a seed, so the sequence is part of the world's record. */
        LOOT(false),

        /** Mob target and path choices. Divergent behaviour compounds and the player notices. */
        MOB_AI(false),

        /** Where a dropped item lands and how it scatters. Stored on the entity and synced. */
        ITEM_SCATTER(false),

        /** Random ticking. Decides which blocks grow, melt and spread. */
        RANDOM_TICK(false);

        private final boolean acceleratable;

        Usage(boolean acceleratable) {
            this.acceleratable = acceleratable;
        }

        /** Whether a faster generator may produce this draw without changing anything observable. */
        public boolean acceleratable() {
            return this.acceleratable;
        }
    }

    /**
     * One generator per thread.
     *
     * <p>A shared generator would need synchronising, which is the cost being avoided in the first place.
     * Construction is the expensive part of an LXM generator, so a thread that draws once a frame still pays
     * for it only once.
     */
    private static final ThreadLocal<RandomGenerator> GENERATORS =
            ThreadLocal.withInitial(FastRandom::newGenerator);

    private static final String GENERATOR_NAME = resolveGeneratorName();
    private static final boolean ACCELERATED = GENERATOR_NAME != null;

    private static long draws;
    private static long acceleratedDraws;
    private static long refusedDraws;

    private FastRandom() {
    }

    /**
     * Whether an LXM generator was found.
     *
     * <p>False does not mean the class is inert: the fallback still removes the shared atomic from the
     * legacy path. It means the arithmetic improvement is not available on this JVM.
     */
    public static boolean isAccelerated() {
        return ACCELERATED;
    }

    /** The generator in use, or {@code null} when running on the fallback. */
    public static String generatorName() {
        return GENERATOR_NAME;
    }

    /**
     * Whether a faster generator may serve this kind of draw.
     *
     * @param usage what the draw is for
     * @return whether it is safe to accelerate
     */
    public static boolean mayAccelerate(Usage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("a usage is required");
        }
        return usage.acceleratable();
    }

    /**
     * Draws an int.
     *
     * @param usage what the draw is for
     * @return the value
     * @throws IllegalArgumentException if the usage must keep Minecraft's own sequence
     */
    public static int nextInt(Usage usage) {
        checkAcceleratable(usage);
        draws++;
        if (ACCELERATED) {
            acceleratedDraws++;
        }
        return GENERATORS.get().nextInt();
    }

    /**
     * Draws an int in {@code [0, bound)}.
     *
     * @param usage  what the draw is for
     * @param bound  the exclusive upper bound
     * @return the value
     */
    public static int nextInt(Usage usage, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("a positive bound is required: " + bound);
        }
        checkAcceleratable(usage);
        draws++;
        if (ACCELERATED) {
            acceleratedDraws++;
        }
        return GENERATORS.get().nextInt(bound);
    }

    /**
     * Draws a float in {@code [0, 1)}.
     *
     * @param usage what the draw is for
     * @return the value
     */
    public static float nextFloat(Usage usage) {
        checkAcceleratable(usage);
        draws++;
        if (ACCELERATED) {
            acceleratedDraws++;
        }
        return GENERATORS.get().nextFloat();
    }

    /**
     * Draws a float in {@code [-extent, extent)}, which is the shape cosmetic jitter actually wants.
     *
     * <p>Offered because the alternative at every call site is {@code (nextFloat() * 2 - 1) * extent}, and a
     * call site that writes {@code nextFloat() * extent} instead gets jitter that only ever pushes one way.
     *
     * @param usage  what the draw is for
     * @param extent how far the value may stray from zero in either direction
     * @return the value
     */
    public static float nextSymmetricFloat(Usage usage, float extent) {
        if (!(extent > 0.0f) || Float.isNaN(extent) || Float.isInfinite(extent)) {
            throw new IllegalArgumentException("a positive finite extent is required: " + extent);
        }
        return (nextFloat(usage) * 2.0f - 1.0f) * extent;
    }

    /** How many draws have been made through this class. */
    public static long draws() {
        return draws;
    }

    /** How many were served by an LXM generator rather than the fallback. */
    public static long acceleratedDraws() {
        return acceleratedDraws;
    }

    /**
     * How many draws were refused because the usage had to keep Minecraft's sequence.
     *
     * <p>Counted rather than silent, so a caller that is asking for worldgen randomness in a loop shows up
     * as a number instead of as an exception in a log nobody reads.
     */
    public static long refusedDraws() {
        return refusedDraws;
    }

    /** Drops the counters, for a world change. The generators stay: they hold no world state. */
    public static void reset() {
        draws = 0L;
        acceleratedDraws = 0L;
        refusedDraws = 0L;
    }

    private static void checkAcceleratable(Usage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("a usage is required");
        }
        if (!usage.acceleratable()) {
            refusedDraws++;
            // Refusing loudly is the point. A worldgen draw served from a different generator would change
            // terrain, and that would surface as "this mod changed my world" with no stack frame to explain
            // it. The caller wants Minecraft's own RandomSource, which is the right answer and not a worse one.
            throw new IllegalArgumentException(usage
                    + " draws must keep Minecraft's own sequence; a different generator would change the world");
        }
    }

    /**
     * Finds an LXM generator, or {@code null} when this JVM does not provide one.
     *
     * <p>Asking by name first, because the preferred one is a known quantity, and only then falling back to
     * any LXM the JVM does have. A JVM with a different LXM variant still gets the arithmetic win; a JVM with
     * none gets {@code null} and the caller uses {@link ThreadLocalRandom}.
     */
    private static String resolveGeneratorName() {
        try {
            RandomGeneratorFactory.of(PREFERRED_GENERATOR);
            return PREFERRED_GENERATOR;
        } catch (IllegalArgumentException ignored) {
            // Not this one. Look for any LXM the JVM does ship.
        } catch (Throwable ignored) {
            // A JVM whose RandomGenerator support is incomplete can fail in less tidy ways than throwing
            // IllegalArgumentException, and failing to load the mod over an optional accelerator would be
            // the wrong trade.
            return null;
        }
        try {
            return RandomGeneratorFactory.all()
                    .filter(factory -> factory.name().startsWith("L"))
                    .map(RandomGeneratorFactory::name)
                    .findFirst()
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static RandomGenerator newGenerator() {
        if (GENERATOR_NAME != null) {
            try {
                return RandomGeneratorFactory.of(GENERATOR_NAME).create();
            } catch (Throwable ignored) {
                // Fall through to the per-thread default rather than throwing from a ThreadLocal
                // initialiser, which would poison the thread for every later draw.
            }
        }
        return ThreadLocalRandom.current();
    }
}
