package net.amdfaster.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimdSupportTest {

    @Test
    void resolutionNeverThrowsOnAJvmWithoutTheModule() {
        // The test JVM is not started with --add-modules=jdk.incubator.vector, so this exercises the path
        // most users are actually on. A mod that threw here would not start at all, which is worse than
        // running its scalar code.
        SimdSupport.Resolution resolution = SimdSupport.resolve(null);

        assertNotNull(resolution);
        assertEquals(SimdSupport.Backend.SCALAR, resolution.backend());
        assertFalse(resolution.isVector());
        assertNotNull(resolution.reason());
        assertTrue(resolution.reason().contains("jdk.incubator.vector"),
                "the reason names the module so the log line is actionable: " + resolution.reason());
    }

    @Test
    void theKillSwitchWinsBeforeAnythingIsProbed() {
        // The user has to be able to turn this off after a bad driver interaction without editing a
        // config file they may not be able to reach. It is checked first, so it works even on a machine
        // where probing the module would itself misbehave.
        SimdSupport.Resolution resolution = SimdSupport.resolve("true");

        assertEquals(SimdSupport.Backend.SCALAR, resolution.backend());
        assertTrue(resolution.reason().contains(SimdSupport.KILL_SWITCH),
                "the reason says which switch: " + resolution.reason());
        assertTrue(SimdSupport.resolve("TRUE").reason().contains(SimdSupport.KILL_SWITCH),
                "the value is read case-insensitively");
    }

    @Test
    void anUnsetOrFalseKillSwitchDoesNotDisableAnything() {
        assertFalse(SimdSupport.resolve("false").reason().contains("kill switch"));
        assertFalse(SimdSupport.resolve(null).reason().contains("kill switch"));
    }

    @Test
    void theScalarPathStillGetsASaneChunkWidth() {
        // Even without the module, the scalar loop is chunked to the width the machine would use if the
        // module were present, so that adding the flag changes the instruction the loop body compiles to
        // and nothing about the memory access pattern. Otherwise enabling it would invalidate every
        // measurement made beforehand.
        SimdSupport.Resolution scalar = SimdSupport.resolve(null);
        assertTrue(SimdSupport.scalarChunkElements(scalar, 4) >= 1);
        assertEquals(1, SimdSupport.scalarChunkElements(scalar, 64),
                "elements wider than a vector still process one at a time");
    }

    @Test
    void aVectorResolutionOf512BitsCarriesSixteenIntLanes() {
        SimdSupport.Resolution resolution =
                new SimdSupport.Resolution(SimdSupport.Backend.VECTOR, 512, 16, "test");
        assertTrue(resolution.isVector());
        assertEquals(16, resolution.intLanes());
        assertEquals(4, SimdSupport.scalarChunkElements(resolution, 4), "16 lanes of 4 bytes over 16 bytes");
        assertTrue(SimdSupport.needsLatencyHiding(resolution),
                "Zen 5 raised 512-bit minimum latency from one cycle to two");
    }

    @Test
    void a256BitResolutionDoesNotNeedLatencyHiding() {
        // The extra cycle only appeared with the native 512-bit datapath. On Zen 4, and on Zen 5 mobile
        // and the Ryzen AI APUs which kept the 256-bit path, splitting work across registers buys nothing
        // and costs registers.
        SimdSupport.Resolution resolution =
                new SimdSupport.Resolution(SimdSupport.Backend.VECTOR, 256, 8, "test");
        assertFalse(SimdSupport.needsLatencyHiding(resolution));
        assertFalse(SimdSupport.needsLatencyHiding(SimdSupport.resolve(null)),
                "and the scalar path has no vector latency to hide");
    }

    @Test
    void gatherIsNeverAssumedCheap() {
        // Zen implements gather and scatter far more slowly than a contiguous load. Every culling kernel
        // here is structure-of-arrays so that no access gathers; a kernel that would need one should be
        // restructured rather than written with a gather and left slow.
        assertFalse(SimdSupport.cheapGather());
    }

    @Test
    void theUsefulnessFloorIs256Bits() {
        // Narrower than that and the setup cost of entering the vector loop outweighs what it saves over
        // a handful of elements. 128-bit NEON on an ARM laptop is exactly this case.
        assertEquals(256, SimdSupport.MIN_USEFUL_BITS);
    }
}
