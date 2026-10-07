package net.amdfaster.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real device-name strings, exactly as the two AMD drivers on Linux and Windows report them,
 * checked against the classification table.
 *
 * <p>This is the one part of the probe that can be wrong silently: a Radeon RX 580 mis-filed as
 * RDNA 1 would schedule work-groups at 128 threads on hardware that wants 256.
 */
class GpuIdentityTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            // --- RADV on Linux: chip name appears in parentheses ------------------------
            "AMD Radeon RX 7900 XTX (radeonsi, navi31, LLVM 15.0.7, DRM 3.49, 6.1.0)|RDNA3",
            "AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 17.0.6, DRM 3.57, 6.8.0-45-generic)|RDNA3",
            "AMD Radeon RX 6800 XT (navi21, LLVM 15.0.7, DRM 3.49, 6.1.0)|RDNA2",
            "AMD Radeon RX 6600 (navi23, LLVM 15.0.7, DRM 3.49, 6.1.0)|RDNA2",
            "AMD Radeon RX 5700 XT (NAVI10, DRM 3.49.0, 6.1.0-generic, LLVM 15.0.7)|RDNA1",
            "AMD Radeon RX Vega 64 (VEGA10, DRM 3.49.0, 6.1.0-generic, LLVM 15.0.7)|GCN5_VEGA",
            "AMD Radeon RX Vega (RADV VEGA8)|GCN5_VEGA",
            "AMD Radeon VII (RADV VEGA20)|GCN5_VEGA",
            "AMD Radeon Graphics (RADV VANGOGH)|RDNA2",
            "AMD Radeon 780M Graphics (RADV PHOENIX)|RDNA3",
            "AMD Radeon 890M Graphics (RADV STRIX_HALE)|RDNA35",
            "AMD Radeon RX 9070 XT (RADV NAVI48)|RDNA4",
            "AMD Radeon RX 9060 XT (RADV NAVI44)|RDNA4",

            // --- Windows proprietary driver: marketing name only -----------------------
            "AMD Radeon(TM) RX 6800 XT|RDNA2",
            "AMD Radeon RX 7900 GRE|RDNA3",
            "AMD Radeon RX 5600 XT|RDNA1",
            "AMD Radeon RX 6500 XT|RDNA2",

            // --- things that must NOT be classified as a supported generation ----------
            "Radeon RX 580 Series|UNKNOWN",
            "AMD Radeon R9 390 Series|UNKNOWN",
            "AMD Radeon(TM) Graphics|UNKNOWN",
            "NVIDIA GeForce RTX 4090|UNKNOWN",
            "Intel(R) Iris(R) Xe Graphics|UNKNOWN",
            "llvmpipe (LLVM 15.0.7, 256 bits)|UNKNOWN"
    })
    void classifiesRealDeviceNames(String deviceName, String expected) {
        GpuIdentity.Match match = GpuIdentity.classify(deviceName, null, null);
        assertEquals(AmdArchitecture.valueOf(expected), match.architecture(),
                "misclassified: " + deviceName + " (matched on '" + match.token() + "')");
    }

    @Test
    void recordsWhichRuleMatched() {
        GpuIdentity.Match match = GpuIdentity.classify("AMD Radeon RX 7900 XTX (radeonsi, navi31)", null, null);
        assertEquals("navi3x", match.token());
        assertTrue(match.haystack().contains("navi31"));
    }

    @Test
    void unknownClassificationCarriesNoToken() {
        GpuIdentity.Match match = GpuIdentity.classify("Radeon RX 580 Series", null, null);
        assertEquals(AmdArchitecture.UNKNOWN, match.architecture());
        assertNull(match.token());
        assertEquals(0, AmdArchitecture.UNKNOWN.recommendedWorkgroupSize(),
                "UNKNOWN must not suggest a work-group size");
    }

    @Test
    void driverInfoAloneIsEnough() {
        // Some builds report a generic device name but keep the chip in driverInfo.
        assertEquals(AmdArchitecture.RDNA3,
                GpuIdentity.classify("AMD Radeon Graphics", "gfx1103", null).architecture());
        assertEquals(AmdArchitecture.RDNA2,
                GpuIdentity.classify("AMD Radeon Graphics", null, "gfx1035").architecture());
    }

    @Test
    void vendorIdsResolve() {
        assertEquals("AMD", GpuIdentity.vendorName(GpuIdentity.VENDOR_ID_AMD));
        assertEquals("NVIDIA", GpuIdentity.vendorName(GpuIdentity.VENDOR_ID_NVIDIA));
        assertEquals("Intel", GpuIdentity.vendorName(GpuIdentity.VENDOR_ID_INTEL));
        assertEquals("Unknown (0x1234)", GpuIdentity.vendorName(0x1234));
    }

    @Test
    void workGroupSizesFollowTheRdnaPerformanceGuide() {
        // "Make the workgroup size a multiple of 64 to obtain best performance across all GPU
        // generations." 64 is one wave64 on GCN and two wave32s on RDNA.
        assertEquals(0, AmdArchitecture.WORKGROUP_SIZE % 64);
        for (AmdArchitecture arch : AmdArchitecture.values()) {
            if (arch == AmdArchitecture.UNKNOWN) {
                continue;
            }
            assertEquals(AmdArchitecture.WORKGROUP_SIZE, arch.recommendedWorkgroupSize(), arch.name());
            assertEquals(0, arch.recommendedWorkgroupSize() % 64, arch.name());
            assertEquals(0, arch.recommendedWorkgroupSize() % arch.nativeWaveSize(),
                    arch.name() + " must not leave a partial wavefront");
        }
        // An 8x8 tile group, as recommended for LDS/image work, is also a multiple of 64.
        assertEquals(64, AmdArchitecture.WORKGROUP_TILED_8X8);
        assertEquals(8 * 8, AmdArchitecture.WORKGROUP_TILED_8X8);
    }

    @Test
    void ldsAndDescriptorBudgetsAreTheDocumentedOnes() {
        assertEquals(32, AmdArchitecture.LDS_BANKS);
        assertEquals(32, AmdArchitecture.LDS_BANK_BITS);
        assertEquals(13, AmdArchitecture.ROOT_SIGNATURE_DWORD_BUDGET);
        assertEquals(10, AmdArchitecture.MIN_WORK_PER_COMMAND_BUFFER);
        // RDNA 1/2 doubled the LDS per CU relative to GCN; RDNA 3 went back to 64 KB.
        assertEquals(64 * 1024, AmdArchitecture.GCN5_VEGA.ldsBytesPerCu());
        assertEquals(128 * 1024, AmdArchitecture.RDNA1.ldsBytesPerCu());
        assertEquals(128 * 1024, AmdArchitecture.RDNA2.ldsBytesPerCu());
        assertEquals(64 * 1024, AmdArchitecture.RDNA3.ldsBytesPerCu());
    }
}
