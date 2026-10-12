package net.amdfaster.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VendorProfileTest {

    @Test
    void transcendentalsCostIntelTwiceWhatTheyCostAmd() {
        // RDNA runs them at a quarter of the FP32 rate. An XVE issues 2 extended-math operations per
        // clock against 16 FP32 MADs, which is an eighth. A "fast approximation" that pays off on AMD is
        // a different trade on Intel, so the ratio has to be read rather than assumed.
        assertEquals(4, VendorProfile.AMD_RDNA.extendedMathPenalty(), "RDNA quarter rate");
        assertEquals(8, VendorProfile.INTEL_XE_HPG.extendedMathPenalty(), "XVE eighth rate");
        assertEquals(8, VendorProfile.INTEL_XE2.extendedMathPenalty(), "Xe2 keeps the same narrow path");
    }

    @Test
    void intelsSharedMemoryIsTwiceAmds() {
        // An Xe-core has 128 KB of SLM against an RDNA work-group processor's 64 KB of LDS. Sizing a
        // tile to the smaller of the two throws away half of Intel's for no reason.
        assertEquals(64 * 1024, VendorProfile.AMD_RDNA.sharedMemoryBytes());
        assertEquals(128 * 1024, VendorProfile.INTEL_XE_HPG.sharedMemoryBytes());
        assertTrue(VendorProfile.tileSizesDiverge(VendorProfile.AMD_RDNA, VendorProfile.INTEL_XE_HPG));
        assertFalse(VendorProfile.tileSizesDiverge(VendorProfile.INTEL_XE_HPG, VendorProfile.INTEL_XE2),
                "the two Intel generations share a size, so one tile size serves both");
    }

    @Test
    void theTileEdgeComesFromSharedMemoryNotFromAConstant() {
        // Four floats per texel, two buffers live at once. Larger shared memory buys a genuinely larger
        // tile, and a larger tile means proportionally less halo work, because a tile's perimeter grows
        // as the square root of its area.
        assertEquals(45, VendorProfile.AMD_RDNA.maxSquareTile(4, 2), "64K over 2 buffers");
        assertEquals(64, VendorProfile.INTEL_XE_HPG.maxSquareTile(4, 2), "128K over 2 buffers");
        assertEquals(32, VendorProfile.CONSERVATIVE.maxSquareTile(4, 2), "32K over 2 buffers");
    }

    @Test
    void droppingToOneBufferFreesTheWholeOfSharedMemory() {
        assertEquals(64, VendorProfile.AMD_RDNA.maxSquareTile(4, 1));
        assertEquals(90, VendorProfile.INTEL_XE_HPG.maxSquareTile(4, 1));
    }

    @Test
    void aTileNeverComesOutSmallerThanOneEvenWithAbsurdInputs() {
        // Zero buffers or a huge texel format must not produce a zero edge, which would make every loop
        // over the tile run zero times and silently render nothing.
        assertEquals(1, VendorProfile.AMD_RDNA.maxSquareTile(1024, 1), "a texel bigger than the LDS");
        assertEquals(1, VendorProfile.AMD_RDNA.maxSquareTile(4, 0), "zero buffers clamps to one");
        assertEquals(1, VendorProfile.AMD_RDNA.maxSquareTile(0, 1), "zero floats per texel clamps to one");
    }

    @Test
    void xe2IsSixteenWideAndXeHpgIsEight() {
        // Xe2 moved to a native SIMD16 engine and halved the XVE count to compensate. Code tuned to
        // eight-lane granularity leaves half of a Xe2 engine idle, so the width has to come from here.
        assertEquals(8, VendorProfile.INTEL_XE_HPG.nativeSimdWidth());
        assertEquals(16, VendorProfile.INTEL_XE2.nativeSimdWidth());
        assertEquals(32, VendorProfile.AMD_RDNA.nativeSimdWidth(), "wave32 on RDNA2 and later");
    }

    @Test
    void deviceNamesMapToTheRightProfile() {
        assertEquals(VendorProfile.Vendor.AMD,
                VendorProfile.fromDeviceName("AMD Radeon RX 7800 XT").vendor());
        assertEquals(VendorProfile.Vendor.INTEL,
                VendorProfile.fromDeviceName("Intel(R) Arc(TM) A770 Graphics").vendor());
        assertEquals(VendorProfile.INTEL_XE2, VendorProfile.fromDeviceName("Intel Arc B580"),
                "Battlemage is Xe2");
        assertEquals(VendorProfile.INTEL_XE_HPG, VendorProfile.fromDeviceName("Intel Arc A750"),
                "Alchemist is Xe-HPG");
        assertEquals(VendorProfile.Vendor.INTEL,
                VendorProfile.fromDeviceName("Intel Iris Xe").vendor(), "the iGPU counts as Intel");
    }

    @Test
    void anythingUnrecognisedGetsTheConservativeProfile() {
        // Guessing wrong here costs a slightly suboptimal tile, so the fallback is the smallest shared
        // memory, the narrowest vector and the worst transcendental penalty of the three. Code sized
        // against it runs everywhere and leaves performance on the table, which is the right way to fail.
        assertEquals(VendorProfile.CONSERVATIVE, VendorProfile.fromDeviceName(null));
        assertEquals(VendorProfile.CONSERVATIVE, VendorProfile.fromDeviceName(""));
        assertEquals(VendorProfile.CONSERVATIVE, VendorProfile.fromDeviceName("Some Unknown GPU"));
        assertEquals(VendorProfile.Vendor.UNKNOWN, VendorProfile.CONSERVATIVE.vendor());
    }

    @Test
    void onlyAmdPartitionsItsL3() {
        // V-Cache sits on one CCD and is not shared with the others. Intel's L3 is a single shared pool,
        // so on Intel the cache topology collapses to one domain and CacheTopology's per-domain figure is
        // already the right number. The same class serves both vendors without a special case.
        assertFalse(VendorProfile.AMD_RDNA.l3IsPartitioned(),
                "false on the base profile; V-Cache partitioning is a property of the CCD count, "
                        + "which CacheTopology reads at runtime");
        assertFalse(VendorProfile.INTEL_XE_HPG.l3IsPartitioned());
        assertTrue(VendorProfile.AMD_RDNA.canAssumeLruEviction());
        assertTrue(VendorProfile.INTEL_XE_HPG.canAssumeLruEviction());
    }
}
