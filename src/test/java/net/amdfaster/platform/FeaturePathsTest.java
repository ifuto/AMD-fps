package net.amdfaster.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.amdfaster.platform.FeaturePaths.Choice;
import net.amdfaster.platform.FeaturePaths.Selection;
import org.junit.jupiter.api.Test;

class FeaturePathsTest {

    private static GpuInfo device(AmdArchitecture architecture, String... extensions) {
        return new GpuInfo(0, "Test GPU", 0x1002, 0x73BF, 2, 0, 0, "", "", "",
                architecture, "", null, null, List.of(extensions), List.of());
    }

    @Test
    void theMeshPathNeedsTheExtensionAndTheGeneration() {
        // An exposed extension says the API is there. It does not say the hardware is good at it, and
        // RDNA2 is the first generation that has it -- so the extension alone must not decide.
        Selection rdna4 = FeaturePaths.select(
                device(AmdArchitecture.RDNA4, FeaturePaths.EXT_MESH_SHADER));

        assertTrue(rdna4.path("mesh").enabled());

        Selection rdna1 = FeaturePaths.select(
                device(AmdArchitecture.RDNA1, FeaturePaths.EXT_MESH_SHADER));
        Choice skipped = rdna1.path("mesh");
        assertFalse(skipped.enabled(), "exposed but not a generation the path is faster on");
        assertTrue(skipped.reason().contains("not a generation"), skipped.reason());
        assertTrue(skipped.reason().contains("compute cull"), skipped.reason());

        Selection absent = FeaturePaths.select(device(AmdArchitecture.RDNA4));
        assertFalse(absent.path("mesh").enabled());
        assertTrue(absent.path("mesh").reason().contains("missing"), absent.path("mesh").reason());
    }

    @Test
    void rdna2AndLaterAllGetTheMeshPath() {
        for (AmdArchitecture architecture : new AmdArchitecture[] {
                AmdArchitecture.RDNA2, AmdArchitecture.RDNA3, AmdArchitecture.RDNA35, AmdArchitecture.RDNA4}) {
            Selection selection = FeaturePaths.select(
                    device(architecture, FeaturePaths.EXT_MESH_SHADER));
            assertTrue(selection.path("mesh").enabled(), architecture.displayName());
        }
    }

    @Test
    void aNonRdnaDeviceDoesNotGetItEither() {
        // GCN 5 has no mesh shader hardware, and an unknown device has nothing identified to trust.
        assertFalse(FeaturePaths.select(device(AmdArchitecture.GCN5_VEGA,
                FeaturePaths.EXT_MESH_SHADER)).path("mesh").enabled());
        assertFalse(FeaturePaths.select(device(AmdArchitecture.UNKNOWN,
                FeaturePaths.EXT_MESH_SHADER)).path("mesh").enabled());
    }

    @Test
    void anUnrecordedExtensionCountsAsAbsent() {
        // A probe that failed part way can leave both lists incomplete, and treating an unrecorded
        // extension as present would enable a path the device cannot run.
        GpuInfo device = new GpuInfo(0, "Test GPU", 0x1002, 0x73BF, 2, 0, 0, "", "", "",
                AmdArchitecture.RDNA4, "", null, null, null, null);

        assertFalse(FeaturePaths.has(device, FeaturePaths.EXT_MESH_SHADER));
        assertFalse(FeaturePaths.select(device).path("mesh").enabled());
        assertEquals(0, FeaturePaths.select(device).enabledCount(), "nothing present, nothing enabled");
    }

    @Test
    void everyPathIsDecidedAndSkippedOnesNameTheMissingExtension() {
        Selection none = FeaturePaths.select(device(AmdArchitecture.RDNA4));

        assertEquals(6, none.choices().size());
        assertEquals(0, none.enabledCount());
        assertEquals(List.of("mesh", "multiDraw", "subgroupSizeControl", "descriptorBuffer",
                "dynamicRendering", "reversedZ"), none.skipped());
        for (Choice choice : none.choices()) {
            assertTrue(choice.reason().contains("missing"), choice.path() + ": " + choice.reason());
        }
    }

    @Test
    void aFullyCapableDeviceEnablesEveryPath() {
        Selection all = FeaturePaths.select(device(AmdArchitecture.RDNA4,
                FeaturePaths.EXT_MESH_SHADER,
                FeaturePaths.EXT_MULTI_DRAW,
                FeaturePaths.EXT_SUBGROUP_SIZE_CONTROL,
                FeaturePaths.EXT_DESCRIPTOR_BUFFER,
                FeaturePaths.EXT_DYNAMIC_RENDERING,
                FeaturePaths.EXT_DEPTH_CLIP_CONTROL));

        assertEquals(6, all.enabledCount());
        assertTrue(all.skipped().isEmpty());
        assertTrue(all.path("multiDraw").reason().contains("device-written count"));
        assertTrue(all.path("descriptorBuffer").reason().contains("no per-draw descriptor set updates"));
    }

    @Test
    void losingReversedZIsReportedAsAPrecisionLossNotASlowdown() {
        // The depth format and the projection both assume reversed-Z, so its absence is a different
        // configuration rather than a slower one, and saying "slower" would send someone looking in the
        // wrong place.
        Selection selection = FeaturePaths.select(device(AmdArchitecture.RDNA4));
        Choice reversedZ = selection.path("reversedZ");

        assertFalse(reversedZ.enabled());
        assertTrue(reversedZ.reason().contains("forward-Z"), reversedZ.reason());
        assertTrue(reversedZ.reason().contains("precision"), reversedZ.reason());
        assertFalse(reversedZ.reason().contains("slower"), reversedZ.reason());
    }

    @Test
    void anUnknownPathLooksUpAsNull() {
        Selection selection = FeaturePaths.select(device(AmdArchitecture.RDNA4));

        assertNull(selection.path("rayTracing"), "no such path was decided");
        assertNull(selection.path(""));
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FeaturePaths.select(null));
        assertThrows(IllegalArgumentException.class, () -> FeaturePaths.has(null, "VK_KHR_swapchain"));
        assertThrows(IllegalArgumentException.class,
                () -> FeaturePaths.has(device(AmdArchitecture.RDNA4), null));
    }
}
