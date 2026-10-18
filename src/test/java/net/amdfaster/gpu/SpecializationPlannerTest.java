package net.amdfaster.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.gpu.SpecializationPlanner.Plan;
import net.amdfaster.gpu.SpecializationPlanner.Property;
import net.amdfaster.platform.AmdArchitecture;
import org.junit.jupiter.api.Test;

class SpecializationPlannerTest {

    @Test
    void oneToggledPropertyCostsTwoPipelines() {
        Plan plan = SpecializationPlanner.plan("block", new Property("cutout", 2, 12, false));

        assertEquals("block", plan.shader());
        assertArrayEquals(new int[] {0}, plan.assigned());
        assertEquals(1, plan.folded());
        assertEquals(2, plan.pipelines());
        assertEquals(12, plan.instructionsSaved());
        assertEquals(20, plan.specBytes(), "one entry: 8 value bytes + 12 map entry bytes");
        assertTrue(plan.withinBudget());
        assertEquals(0, plan.overflow());
    }

    @Test
    void idsAreAssignedInOrderAndOnlyToFoldedProperties() {
        Plan plan = SpecializationPlanner.plan("block",
                new Property("cutout", 2, 12, false),
                new Property("smoothLighting", 2, 5, false));

        assertArrayEquals(new int[] {0, 1}, plan.assigned());
        assertEquals(4, plan.pipelines(), "2 x 2");
        assertEquals(17, plan.instructionsSaved());
        assertEquals(40, plan.specBytes());
        assertTrue(plan.withinBudget());
    }

    @Test
    void theBudgetStopsThePermutationExplosion() {
        // Three independent toggles want eight pipelines. The third is skipped rather than the plan
        // failing, because the caller asked for as much folding as fits and the first two are still
        // worth having -- refusing all three would leave them branching for no reason.
        Plan plan = SpecializationPlanner.plan("block",
                new Property("cutout", 2, 12, false),
                new Property("smoothLighting", 2, 5, false),
                new Property("tinted", 2, 3, false));

        assertArrayEquals(new int[] {0, 1}, plan.assigned());
        assertEquals(2, plan.folded());
        assertEquals(4, plan.pipelines());
        assertEquals(17, plan.instructionsSaved(), "the skipped property saves nothing");
        assertTrue(plan.withinBudget());
    }

    @Test
    void aSingleValueIsFoldedEvenWhenItGuardsNothing() {
        // One value means the compiler already knows it, so the branch was never really there and
        // folding costs no extra pipeline -- the product does not move.
        Plan plan = SpecializationPlanner.plan("block", new Property("reversedZ", 1, 0, false));

        assertArrayEquals(new int[] {0}, plan.assigned());
        assertEquals(1, plan.pipelines());
        assertEquals(0, plan.instructionsSaved());
        assertTrue(plan.withinBudget());
    }

    @Test
    void aDivergentPropertyIsNeverFolded() {
        // A specialization constant is one value for the whole pipeline. A property whose value differs
        // between lanes of one wave cannot be one, so folding it would bake in one lane's answer for all
        // of them -- a wrong image, not a slow one.
        Plan plan = SpecializationPlanner.plan("block",
                new Property("perVertexAo", 2, 20, true),
                new Property("cutout", 2, 12, false));

        assertArrayEquals(new int[] {0}, plan.assigned());
        assertEquals(1, plan.folded());
        assertEquals(2, plan.pipelines(), "only cutout multiplies");
        assertEquals(12, plan.instructionsSaved());
    }

    @Test
    void fiveValuesDoNotFitAtAll() {
        Plan plan = SpecializationPlanner.plan("block", new Property("blendMode", 5, 30, false));

        assertEquals(0, plan.folded());
        assertEquals(1, plan.pipelines(), "nothing folded, so still one pipeline");
        assertEquals(0, plan.instructionsSaved());
        assertEquals(0, plan.specBytes());
        assertTrue(plan.withinBudget(), "one pipeline is within budget even though nothing folded");
    }

    @Test
    void overflowCountsWhatWouldHaveToBeDropped() {
        Plan within = SpecializationPlanner.plan("block", new Property("cutout", 4, 12, false));
        assertEquals(4, within.pipelines());
        assertTrue(within.withinBudget());
        assertEquals(0, within.overflow());
    }

    @Test
    void anEmptySetIsOnePipelineWithNoSpecializationData() {
        Plan plan = SpecializationPlanner.plan("hiz_reduce");

        assertEquals(0, plan.folded());
        assertEquals(1, plan.pipelines());
        assertEquals(0, plan.specBytes());
        assertEquals(0, plan.instructionsSaved());
        assertTrue(plan.withinBudget());
    }

    @Test
    void theSpecializationPayloadIsTwentyBytesPerEntry() {
        // Every entry is stored at the widest allowed width so one block can hold them all at a fixed
        // stride regardless of what the shader declared, plus a twelve byte map entry naming the id,
        // offset and size.
        assertEquals(8, SpecializationPlanner.MAX_VALUE_BYTES);
        assertEquals(12, SpecializationPlanner.MAP_ENTRY_BYTES);
        assertEquals(0, SpecializationPlanner.specBytes(0));
        assertEquals(20, SpecializationPlanner.specBytes(1));
        assertEquals(60, SpecializationPlanner.specBytes(3));
    }

    @Test
    void theWorkgroupSizeIsWorthFoldingOnEveryRealDevice() {
        assertTrue(SpecializationPlanner.specializeWorkgroupSize(AmdArchitecture.RDNA3));
        assertTrue(SpecializationPlanner.specializeWorkgroupSize(AmdArchitecture.RDNA4));
        assertTrue(SpecializationPlanner.specializeWorkgroupSize(AmdArchitecture.GCN5_VEGA));
        // UNKNOWN recommends no size, so there is nothing to fold and the shader keeps its declared one.
        assertFalse(SpecializationPlanner.specializeWorkgroupSize(AmdArchitecture.UNKNOWN));
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SpecializationPlanner.plan(null));
        assertThrows(IllegalArgumentException.class,
                () -> SpecializationPlanner.plan("block", (Property[]) null));
        assertThrows(IllegalArgumentException.class,
                () -> SpecializationPlanner.plan("block", (Property) null));
        assertThrows(IllegalArgumentException.class,
                () -> SpecializationPlanner.plan("block", new Property(null, 2, 1, false)));
        assertThrows(IllegalArgumentException.class,
                () -> SpecializationPlanner.plan("block", new Property("cutout", 0, 1, false)));
        assertThrows(IllegalArgumentException.class,
                () -> SpecializationPlanner.plan("block", new Property("cutout", 2, -1, false)));
        assertThrows(IllegalArgumentException.class, () -> SpecializationPlanner.specBytes(-1));
        assertThrows(IllegalArgumentException.class,
                () -> SpecializationPlanner.specializeWorkgroupSize(null));
    }
}
