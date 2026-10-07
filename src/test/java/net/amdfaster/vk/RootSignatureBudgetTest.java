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
    void theDetailNamesWhatCostWhat() {
        String detail = RootSignatureBudget.check(32, 2).detail();
        assertTrue(detail.contains("pushConstants=8"), detail);
        assertTrue(detail.contains("descriptorSets=2"), detail);
        assertTrue(detail.contains("= 10 DWORDs"), detail);
        assertTrue(detail.contains("budget 13"), detail);
    }
}
