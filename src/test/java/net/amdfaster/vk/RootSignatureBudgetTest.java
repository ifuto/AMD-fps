package net.amdfaster.vk;

import net.amdfaster.platform.AmdArchitecture;
import net.amdfaster.vk.RootSignatureBudget.Kind;
import net.amdfaster.vk.RootSignatureBudget.Parameter;
import net.amdfaster.vk.RootSignatureBudget.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootSignatureBudgetTest {

    @Test
    void theBudgetIsTheOneAmdDocuments() {
        assertEquals(13, AmdArchitecture.ROOT_SIGNATURE_DWORD_BUDGET);
    }

    @Test
    void pushConstantsCostOneDwordPerFourBytes() {
        assertEquals(1, new Parameter(Kind.PUSH_CONSTANT_DWORD, 1, "p").dwords());
        assertEquals(8, new Parameter(Kind.PUSH_CONSTANT_DWORD, 8, "p").dwords());
    }

    @Test
    void rootDescriptorsCostTwiceWhatATableDoes() {
        assertEquals(1, new Parameter(Kind.DESCRIPTOR_SET, 1, "s").dwords());
        assertEquals(2, new Parameter(Kind.PUSH_DESCRIPTOR, 1, "d").dwords());
    }

    @Test
    void thirteenDwordsExactlyStillFits() {
        Verdict v = RootSignatureBudget.check(52, 0);
        assertEquals(13, v.dwords());
        assertTrue(v.fits());
        assertEquals(0, v.overBy());
    }

    @Test
    void fourteenDwordsDoesNot() {
        Verdict v = RootSignatureBudget.check(56, 0);
        assertEquals(14, v.dwords());
        assertFalse(v.fits());
        assertEquals(1, v.overBy());
    }

    @Test
    void aPartialDwordIsRoundedUp() {
        assertEquals(13, RootSignatureBudget.check(50, 0).dwords(),
                "50 bytes of push constant is 13 DWORDs, not 12");
    }

    @Test
    void thePlannedWorldPipelineFits() {
        // 32 bytes of push constants (camera-relative origin, section offset, packed cull state)
        // plus two descriptor sets: textures and the meshlet buffer.
        Verdict v = RootSignatureBudget.check(32, 2);
        assertEquals(10, v.dwords());
        assertTrue(v.fits(), v.detail());
    }

    @Test
    void oneMoreSetPushesItOver() {
        Verdict v = RootSignatureBudget.check(48, 2);
        assertEquals(14, v.dwords());
        assertFalse(v.fits());
        assertEquals(1, v.overBy());
    }

    @Test
    void anExplicitLayoutIsAccounted() {
        Verdict v = RootSignatureBudget.check(List.of(
                new Parameter(Kind.PUSH_CONSTANT_DWORD, 6, "pushConstants"),
                new Parameter(Kind.DESCRIPTOR_SET, 2, "textures+meshlets"),
                new Parameter(Kind.PUSH_DESCRIPTOR, 1, "drawConstants")));
        assertEquals(10, v.dwords(), "6 push constants + 2 tables at 1 + 1 root descriptor at 2");
        assertTrue(v.fits());
    }

    @Test
    void pushDescriptorsEatTheBudgetTwiceAsFast() {
        // A push descriptor is a root descriptor, not a table pointer, so it costs 2 DWORDs. Two
        // more of them take this layout from 10 to 14 and it stops fitting -- which is exactly the
        // trap the budget check exists to catch.
        Verdict two = RootSignatureBudget.check(List.of(
                new Parameter(Kind.PUSH_CONSTANT_DWORD, 6, "pushConstants"),
                new Parameter(Kind.DESCRIPTOR_SET, 2, "textures+meshlets"),
                new Parameter(Kind.PUSH_DESCRIPTOR, 2, "drawConstants")));
        assertEquals(12, two.dwords());
        assertTrue(two.fits());

        Verdict three = RootSignatureBudget.check(List.of(
                new Parameter(Kind.PUSH_CONSTANT_DWORD, 6, "pushConstants"),
                new Parameter(Kind.DESCRIPTOR_SET, 2, "textures+meshlets"),
                new Parameter(Kind.PUSH_DESCRIPTOR, 3, "drawConstants")));
        assertEquals(14, three.dwords());
        assertFalse(three.fits());
        assertEquals(1, three.overBy());
    }

    @Test
    void anEmptyLayoutCostsNothing() {
        Verdict v = RootSignatureBudget.check(List.of());
        assertEquals(0, v.dwords());
        assertTrue(v.fits());
    }

    @Test
    void aDynamicBufferCostsTwiceAPlainSetAndFourWithRobustAccess() {
        // The Vulkan rows of AMD's table with no D3D12 equivalent. The offset has to live somewhere the
        // shader can reach it, and robust access adds the bounds checked on every access.
        assertEquals(1, new Parameter(Kind.DESCRIPTOR_SET, 1, "s").dwords());
        assertEquals(2, new Parameter(Kind.DYNAMIC_BUFFER, 1, "d").dwords());
        assertEquals(4, new Parameter(Kind.DYNAMIC_BUFFER_ROBUST, 1, "d").dwords());
    }

    @Test
    void robustAccessCanBeWhatPushesALayoutOverTheBudget() {
        // Same pipeline, same bindings, and the only difference is a device feature. 13 DWORDs fits
        // exactly; turning robust buffer access on makes it 19 and it does not. That is worth knowing
        // before the pipeline is built rather than finding it in a frame capture that shows nothing.
        Verdict off = RootSignatureBudget.check(16, 3, 3, false);
        Verdict on = RootSignatureBudget.check(16, 3, 3, true);

        assertEquals(13, off.dwords());
        assertTrue(off.fits());
        assertEquals(19, on.dwords());
        assertFalse(on.fits());
        assertEquals(6, on.overBy());
        assertTrue(on.detail().contains("dynamicRobust=12"), on.detail());
        assertTrue(off.detail().contains("dynamic=6"), off.detail());
    }

    @Test
    void oneRobustDynamicBindingIsAThirdOfTheWholeBudget() {
        // Four of thirteen DWORDs, before anything else is bound. Which is the argument for one set with
        // a dynamic offset over several, and for knowing which of the two a pipeline actually uses.
        Verdict v = RootSignatureBudget.check(0, 0, 1, true);

        assertEquals(4, v.dwords());
        assertTrue(v.fits());
        assertEquals(4.0 / AmdArchitecture.ROOT_SIGNATURE_DWORD_BUDGET, 4.0 / 13.0, 1e-12);
    }

    @Test
    void theCullPipelinesOwnShapeFitsEitherWay() {
        // CullBindings has 8 bytes of push constants and one descriptor set. Adding a dynamic buffer for
        // the per-frame data stays inside the budget with robust access on or off, so this one is not a
        // trade -- but the same shape with three dynamic buffers is, and that is the case worth catching.
        assertEquals(3, RootSignatureBudget.check(8, 1, 0, true).dwords());
        assertEquals(5, RootSignatureBudget.check(8, 1, 1, false).dwords());
        assertEquals(7, RootSignatureBudget.check(8, 1, 1, true).dwords());
        assertTrue(RootSignatureBudget.check(8, 1, 1, true).fits());

        assertFalse(RootSignatureBudget.check(8, 1, 3, true).fits(), "15 DWORDs");
        assertTrue(RootSignatureBudget.check(8, 1, 3, false).fits(), "9 DWORDs");
    }

    @Test
    void theDetailNamesWhatCostWhat() {
        String detail = RootSignatureBudget.check(32, 2).detail();
        assertTrue(detail.contains("pushConstants=8"), detail);
        assertTrue(detail.contains("descriptorSets=2"), detail);
        assertTrue(detail.contains("= 10 DWORDs"), detail);
        assertTrue(detail.contains("budget 13"), detail);
    }
}
