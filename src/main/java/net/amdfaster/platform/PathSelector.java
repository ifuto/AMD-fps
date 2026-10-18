package net.amdfaster.platform;

import net.amdfaster.gpu.OccupancyPlanner;
import net.amdfaster.mesh.Meshlet;
import net.amdfaster.render.DepthPrepassSelector;

/**
 * Turns an identified GPU generation into the concrete execution paths the renderer should take.
 *
 * <p>The pieces this chooses between already exist and were already written with the hardware in mind:
 * {@link DepthPrepassSelector} takes an {@code earlyZSuppressed} flag, {@link OccupancyPlanner} takes a
 * wave size, {@link DynamicVgprPlanner} asks whether wave32 compute is eligible, and the meshlet path
 * assumes a particular quad count. What was missing is the thing that decides those inputs from the device
 * in front of it. Without it every one of them has to be supplied by hand, which in practice means they
 * get supplied once, with a guess, and then stay wrong on every generation the guess was not about.
 *
 * <p>The one that matters most is early-Z. RADV carries a HiZ workaround for GFX12 that is enabled by
 * default, and its {@code full} setting suppresses early-Z rejection. Minecraft is close to the worst case
 * for losing it: large amounts of cutout and translucency, and a lot of depth overdraw, so fragments that
 * early-Z would have discarded get shaded instead. The selector that decides whether a depth pre-pass pays
 * already models this by relaxing its threshold -- a pre-pass becomes worth it at lower overdraw once
 * early-Z is gone -- but it takes the suppression as a parameter, so it only helps if something actually
 * sets the parameter. That is what this does.
 *
 * <p>Note that the answer is per driver as well as per generation. The workaround is a RADV tunable and
 * the alternative {@code full_rez} setting recovers early-Z by re-testing it late, so a user who has set
 * that, or who is on Windows with a different driver, is not in the suppressed case at all. The caller
 * therefore passes what it observed rather than letting this infer it from the chip alone, and this
 * supplies the default for the driver it cannot see.
 *
 * <p>Everything here degrades rather than fails. An unrecognised device gets the conservative path on
 * every axis, which runs correctly everywhere and leaves performance on the table.
 *
 * @see <a href="https://docs.mesa3d.org/drivers/radv/perf.html">RADV performance tunables --
 *      {@code radv_gfx12_hiz_wa} with its {@code disabled}, {@code partial}, {@code full} and
 *      {@code full_rez} settings</a>
 */
public final class PathSelector {

    /**
     * The HiZ workaround settings RADV accepts, in increasing order of what they cost.
     *
     * <p>{@code full} is the default on GFX12 and is the one that suppresses early-Z. {@code full_rez}
     * applies the same workaround but re-tests depth late so the rejection is recovered, at the price of
     * the extra test. {@code disabled} and {@code partial} apply less of it and so cost less.
     */
    public enum HizWorkaround {
        /** Not applied. Early-Z works normally. */
        DISABLED(false),
        /** Applied to some surfaces. Early-Z is partly weakened. */
        PARTIAL(false),
        /** Applied fully. This is RADV's GFX12 default and it suppresses early-Z. */
        FULL(true),
        /** Applied fully with a late re-Z, so early-Z rejection is recovered. */
        FULL_REZ(false);

        private final boolean suppressesEarlyZ;

        HizWorkaround(boolean suppressesEarlyZ) {
            this.suppressesEarlyZ = suppressesEarlyZ;
        }

        /** Whether this setting leaves the forward pass without working early-Z. */
        public boolean suppressesEarlyZ() {
            return this.suppressesEarlyZ;
        }
    }

    private PathSelector() {
    }

    /**
     * Every path decision that follows from the generation.
     *
     * @param earlyZSuppressed  what to pass to {@link DepthPrepassSelector}
     * @param waveSize          the wave size compute shaders should dispatch with before any register
     *                          pressure is known; {@link OccupancyPlanner} refines this per shader
     * @param dynamicVgprs      whether the shader may ask for registers dynamically, which RDNA4 allows
     *                          for wave32 compute only
     * @param workgroupSize     threads per workgroup
     * @param meshletQuads      quads per meshlet, which the draw derivation and the buffer strides assume
     * @param ldsBytes          shared memory the plan may budget against
     */
    public record Paths(boolean earlyZSuppressed, int waveSize, boolean dynamicVgprs, int workgroupSize,
                        int meshletQuads, int ldsBytes) {
    }

    /**
     * The default HiZ workaround for a generation, used when the driver's actual setting is not visible.
     *
     * <p>Only GFX12 defaults to the early-Z-suppressing setting. Assuming it everywhere would push every
     * AMD device into depth pre-passes it does not need, and assuming it nowhere would leave RDNA4
     * shading fragments it could have discarded.
     */
    public static HizWorkaround defaultHizWorkaround(AmdArchitecture architecture) {
        require(architecture != null, "an architecture is needed");
        return architecture == AmdArchitecture.RDNA4 ? HizWorkaround.FULL : HizWorkaround.DISABLED;
    }

    /**
     * Whether early-Z is suppressed, combining the generation with what the caller observed.
     *
     * <p>Observed wins. A user who set {@code full_rez} or {@code disabled} is not in the suppressed case
     * whatever the chip is, and a non-RADV driver never had the workaround to begin with. The generation
     * only supplies the answer when there is nothing observed to go on.
     *
     * @param observed the setting read from the driver, or null when it could not be read
     */
    public static boolean earlyZSuppressed(AmdArchitecture architecture, HizWorkaround observed) {
        require(architecture != null, "an architecture is needed");
        return (observed != null ? observed : defaultHizWorkaround(architecture)).suppressesEarlyZ();
    }

    /** The wave size to dispatch with before any shader's register pressure is known. */
    public static int defaultWaveSize(AmdArchitecture architecture) {
        require(architecture != null, "an architecture is needed");
        // GCN 5 has no wave32 at all, so there is nothing to choose.
        return architecture.supportsWave32() ? 32 : 64;
    }

    /**
     * Whether a compute shader may request registers dynamically.
     *
     * <p>Restricted to wave32 compute on RDNA4 by the ISA, and gated behind a chip-wide control register
     * the driver has to set up, so this is the eligibility half of the question rather than a promise that
     * a request will succeed -- allocation can fail and force the wave to wait.
     */
    public static boolean dynamicVgprsAvailable(AmdArchitecture architecture, boolean compute) {
        require(architecture != null, "an architecture is needed");
        return architecture == AmdArchitecture.RDNA4 && compute
                && DynamicVgprPlanner.isEligible(true, true);
    }

    /**
     * Resolves every path for a device.
     *
     * @param architecture the identified generation
     * @param observedHiz  the HiZ setting read from the driver, or null when it could not be read
     */
    public static Paths select(AmdArchitecture architecture, HizWorkaround observedHiz) {
        require(architecture != null, "an architecture is needed");
        int lds = architecture.ldsBytesPerCu();
        return new Paths(
                earlyZSuppressed(architecture, observedHiz),
                defaultWaveSize(architecture),
                dynamicVgprsAvailable(architecture, true),
                Math.max(AmdArchitecture.WORKGROUP_SIZE, defaultWaveSize(architecture)),
                Meshlet.MAX_QUADS,
                lds > 0 ? lds : OccupancyPlanner.LDS_MAX_PER_WORKGROUP);
    }

    /**
     * Whether the identified generation is one this mod tunes for at all.
     *
     * <p>An unrecognised device still gets correct paths -- the conservative ones -- but nothing here is
     * measured on it, so the caller should say so in its diagnostics rather than reporting a tuned
     * configuration it did not choose.
     */
    public static boolean tuned(AmdArchitecture architecture) {
        require(architecture != null, "an architecture is needed");
        return architecture != AmdArchitecture.UNKNOWN;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
