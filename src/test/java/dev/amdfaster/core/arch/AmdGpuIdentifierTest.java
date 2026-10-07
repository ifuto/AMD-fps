package dev.amdfaster.core.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identification tables are the part of AMD-Faster that ages fastest, so they are pinned down by
 * tests: every GPU family the mod claims to support has at least one representative string here.
 */
class AmdGpuIdentifierTest {

    @Test
    @DisplayName("retail Radeon names map to the right GFX generation")
    void retailNames() {
        assertEquals(AmdArch.RDNA4, confident("AMD Radeon RX 9070 XT (0x7550)").arch());
        assertEquals(AmdArch.RDNA4, confident("AMD Radeon RX 9060 XT").arch());
        assertEquals(AmdArch.RDNA3, confident("AMD Radeon RX 7900 XTX (0x744C)").arch());
        assertEquals(AmdArch.RDNA3, confident("AMD Radeon RX 7800 XT").arch());
        assertEquals(AmdArch.RDNA3, confident("AMD Radeon RX 7600").arch());
        assertEquals(AmdArch.RDNA2, confident("AMD Radeon RX 6800 XT (0x73BF)").arch());
        assertEquals(AmdArch.RDNA2, confident("AMD Radeon RX 6700 XT").arch());
        assertEquals(AmdArch.RDNA2, confident("AMD Radeon RX 6600").arch());
        assertEquals(AmdArch.RDNA1, confident("AMD Radeon RX 5700 XT").arch());
        assertEquals(AmdArch.RDNA1, confident("AMD Radeon RX 5500 XT").arch());
        assertEquals(AmdArch.POLARIS, confident("AMD Radeon RX 580").arch());
        assertEquals(AmdArch.POLARIS, confident("Radeon RX 570 Series").arch());
        assertEquals(AmdArch.VEGA, confident("AMD Radeon RX Vega 64").arch());
        assertEquals(AmdArch.VEGA, confident("AMD Radeon VII").arch());
    }

    @Test
    @DisplayName("APU strings are distinguished from discrete boards that share a number")
    void apuNames() {
        // "680M" is Rembrandt (RDNA 2 iGPU); "RX 6800 XT" is Navi 22. Both contain "68".
        assertEquals(AmdArch.RDNA2_APU, confident("AMD Radeon 680M").arch());
        assertEquals(AmdArch.RDNA2, confident("AMD Radeon RX 6800 XT").arch());
        // "780M" is Phoenix (RDNA 3 iGPU); "RX 7800 XT" is Navi 32.
        assertEquals(AmdArch.RDNA3_APU, confident("AMD Radeon 780M").arch());
        assertEquals(AmdArch.RDNA3, confident("AMD Radeon RX 7800 XT").arch());
        assertEquals(AmdArch.RDNA3_APU, confident("AMD Radeon 890M Graphics").arch());
        assertEquals(AmdArch.VEGA_APU, confident("AMD Radeon Vega 8 Graphics").arch());
        assertEquals(AmdArch.RDNA2_APU, confident("AMD Custom GPU 0405 (RADV VANGOGH)").arch());
    }

    @Test
    @DisplayName("the ambiguous \"Radeon Graphics\" string is resolved by the PCI device id")
    void ambiguousApuStringUsesDeviceId() {
        AmdGpuIdentifier.Match withId = AmdGpuIdentifier.classifyAmd("AMD Radeon(TM) Graphics", 0x15DD);
        assertEquals(AmdArch.VEGA_APU, withId.arch());
        assertTrue(withId.confidence() >= 0.75, "a device id hit must be trustworthy enough to tune with");
        assertTrue(withId.reason().contains("device id"));

        // Without any id we must stay conservative rather than guess.
        AmdGpuIdentifier.Match withoutId = AmdGpuIdentifier.classifyAmd("AMD Radeon(TM) Graphics", -1);
        assertEquals(AmdArch.VEGA_APU, withoutId.arch());
        assertTrue(withoutId.confidence() < 0.75, "an ambiguous name must not be treated as confident");
    }

    @Test
    @DisplayName("device id table resolves ids whose names are meaningless")
    void deviceIdTable() {
        assertEquals(AmdArch.RDNA3, AmdGpuIdentifier.archForDeviceId(0x744C));
        assertEquals(AmdArch.RDNA2, AmdGpuIdentifier.archForDeviceId(0x73BF));
        assertEquals(AmdArch.RDNA1, AmdGpuIdentifier.archForDeviceId(0x731F));
        assertEquals(AmdArch.POLARIS, AmdGpuIdentifier.archForDeviceId(0x67DF));
        assertEquals(AmdArch.UNKNOWN, AmdGpuIdentifier.archForDeviceId(0x1234));
    }

    @Test
    @DisplayName("vendor classification covers the non-AMD cases the mod has to survive")
    void vendorClassification() {
        assertEquals(GpuVendor.AMD, GpuVendor.classify("AMD", "AMD Radeon RX 6700 XT", -1));
        assertEquals(GpuVendor.AMD, GpuVendor.classify(null, "AMD Radeon RX 6700 XT", 0x1002));
        assertEquals(GpuVendor.NVIDIA, GpuVendor.classify(null, "NVIDIA GeForce RTX 4090", -1));
        assertEquals(GpuVendor.NVIDIA, GpuVendor.classify(null, "NVIDIA GeForce RTX 3080/PCIe/SSE2", 0x10DE));
        assertEquals(GpuVendor.INTEL, GpuVendor.classify("Intel", "Intel(R) UHD Graphics 630", -1));
        assertEquals(GpuVendor.SOFTWARE, GpuVendor.classify("Mesa", "llvmpipe (LLVM 18.1.8, 256 bits)", -1));
        assertEquals(GpuVendor.SOFTWARE, GpuVendor.classify(null, "Mesa OffScreen", -1));
        assertEquals(GpuVendor.UNKNOWN, GpuVendor.classify(null, null, -1));
    }

    @Test
    @DisplayName("unknown AMD devices stay unknown instead of being guessed")
    void unknownStaysUnknown() {
        AmdGpuIdentifier.Match match = AmdGpuIdentifier.classifyAmd("AMD Radeon Something New 9999", -1);
        assertEquals(AmdArch.UNKNOWN, match.arch());
        assertTrue(match.confidence() < 0.75);

        GpuIdentity identity = new GpuIdentity(GpuVendor.AMD, "AMD Radeon Something New 9999",
                match.arch(), match.confidence(), -1, "driver", false, -1, -1);
        assertFalse(identity.isArchConfident());
    }

    private static AmdGpuIdentifier.Match confident(String renderer) {
        AmdGpuIdentifier.Match match = AmdGpuIdentifier.classifyAmd(renderer, -1);
        assertTrue(match.confidence() >= GpuIdentity.CONFIDENT,
                () -> "expected a confident match for \"" + renderer + "\" but got " + match);
        return match;
    }
}
