package net.amdfaster.draw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawBatchOrderTest {

    @Test
    void depthSurvivesAPackAndUnpackRoundTrip() {
        for (float value : new float[] {-1e6f, -1000.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1000.0f, 1e6f}) {
            assertEquals(value, DrawBatchOrder.sortableToDepth(DrawBatchOrder.depthToSortable(value)), 0.0f,
                    "depth " + value);
        }
    }

    @Test
    void theDepthMappingIsMonotonicAcrossZero() {
        // This is the bug the mapping exists to avoid. Negative floats order backwards by their raw bits,
        // so packing bits directly would draw the far half of the frame first and give early Z nothing to
        // reject. Inverting every bit of a negative, rather than all but the sign, is what puts it right.
        float[] ascending = {-1e6f, -1000.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1000.0f, 1e6f};
        long previous = -1L;
        for (float value : ascending) {
            long sortable = DrawBatchOrder.depthToSortable(value) & 0xffff_ffffL;
            assertTrue(sortable > previous,
                    "depth " + value + " maps to " + sortable + " which does not exceed " + previous);
            previous = sortable;
        }
    }

    @Test
    void sortingGroupsByPipelineThenTextureThenDepth() {
        long[] keys = {
            DrawBatchOrder.key(0, 5, 3.0f),
            DrawBatchOrder.key(1, 2, 1.0f),
            DrawBatchOrder.key(0, 5, 1.0f),
            DrawBatchOrder.key(0, 3, 2.0f),
            DrawBatchOrder.key(1, 2, 0.5f),
            DrawBatchOrder.key(0, 5, 2.0f),
        };
        DrawBatchOrder.sort(keys);

        assertEquals(0, DrawBatchOrder.pipelineOf(keys[0]));
        assertEquals(3, DrawBatchOrder.textureOf(keys[0]), "texture 3 sorts before texture 5");
        assertEquals(0, DrawBatchOrder.pipelineOf(keys[1]));
        assertEquals(5, DrawBatchOrder.textureOf(keys[1]));
        assertEquals(1.0f, DrawBatchOrder.sortableToDepth((int) keys[1]), 0.0f, "nearest first in the group");
        assertEquals(2.0f, DrawBatchOrder.sortableToDepth((int) keys[2]), 0.0f);
        assertEquals(3.0f, DrawBatchOrder.sortableToDepth((int) keys[3]), 0.0f);
        assertEquals(1, DrawBatchOrder.pipelineOf(keys[4]), "the other pipeline comes last");
        assertEquals(1, DrawBatchOrder.pipelineOf(keys[5]));
    }

    @Test
    void sortingReducesStateChanges() {
        // Five changes down to two on six draws. The point is not the absolute number but that the ratio
        // improves without bound as the frame grows: four thousand draws sharing four textures go from
        // thousands of changes to three.
        long[] unsorted = {
            DrawBatchOrder.key(0, 5, 3.0f),
            DrawBatchOrder.key(1, 2, 1.0f),
            DrawBatchOrder.key(0, 5, 1.0f),
            DrawBatchOrder.key(0, 3, 2.0f),
            DrawBatchOrder.key(1, 2, 0.5f),
            DrawBatchOrder.key(0, 5, 2.0f),
        };
        int before = DrawBatchOrder.stateChanges(unsorted, unsorted.length);

        long[] sorted = unsorted.clone();
        DrawBatchOrder.sort(sorted);
        int after = DrawBatchOrder.stateChanges(sorted, sorted.length);

        assertEquals(5, before);
        assertEquals(2, after);
        assertEquals(5, DrawBatchOrder.worstCaseChanges(6));
    }

    @Test
    void groupingDoesNotBreakFrontToBackOrder() {
        // Depth is the innermost key on purpose. Grouping by state and sorting by depth are not in
        // conflict, and losing the depth order would cost more in overdraw than the state changes saved.
        long[] keys = {
            DrawBatchOrder.key(0, 1, 9.0f),
            DrawBatchOrder.key(0, 1, 1.0f),
            DrawBatchOrder.key(0, 1, 5.0f),
        };
        DrawBatchOrder.sort(keys);

        assertEquals(1.0f, DrawBatchOrder.sortableToDepth((int) keys[0]), 0.0f);
        assertEquals(5.0f, DrawBatchOrder.sortableToDepth((int) keys[1]), 0.0f);
        assertEquals(9.0f, DrawBatchOrder.sortableToDepth((int) keys[2]), 0.0f);
        assertEquals(0, DrawBatchOrder.stateChanges(keys, 3), "and nothing is rebound");
    }

    @Test
    void aPipelineBindIsNotCountedTwiceForTheTextureItImplies() {
        // A pipeline change rebinds the texture as part of itself, so counting both would overstate the
        // saving the sort appears to make.
        long[] keys = {
            DrawBatchOrder.key(0, 1, 1.0f),
            DrawBatchOrder.key(1, 2, 1.0f),
        };
        assertEquals(1, DrawBatchOrder.stateChanges(keys, 2), "one pipeline change, not two events");
    }

    @Test
    void theSameTextureUnderDifferentPipelinesIsTwoChanges() {
        long[] keys = {
            DrawBatchOrder.key(0, 7, 1.0f),
            DrawBatchOrder.key(1, 7, 1.0f),
        };
        assertEquals(1, DrawBatchOrder.stateChanges(keys, 2));
    }

    @Test
    void shortAndEmptyListsCostNothing() {
        assertEquals(0, DrawBatchOrder.stateChanges(new long[0], 0));
        assertEquals(0, DrawBatchOrder.stateChanges(new long[] {DrawBatchOrder.key(0, 0, 1.0f)}, 1));
        assertEquals(0, DrawBatchOrder.worstCaseChanges(0));
        assertEquals(0, DrawBatchOrder.worstCaseChanges(1));
    }

    @Test
    void onlyTheRequestedPrefixIsSorted() {
        long[] keys = {
            DrawBatchOrder.key(0, 9, 1.0f),
            DrawBatchOrder.key(0, 1, 1.0f),
            DrawBatchOrder.key(0, 5, 1.0f),
        };
        DrawBatchOrder.sort(keys, 2);

        assertEquals(1, DrawBatchOrder.textureOf(keys[0]), "the first two are ordered");
        assertEquals(9, DrawBatchOrder.textureOf(keys[1]));
        assertEquals(5, DrawBatchOrder.textureOf(keys[2]), "and the third is left alone");
    }

    @Test
    void idsOutsideTheFieldWidthsAreRejected() {
        // An id that does not fit would spill into the neighbouring field and silently change another
        // draw's pipeline, which is a rendering bug with no error attached.
        assertThrows(IllegalArgumentException.class, () -> DrawBatchOrder.key(256, 0, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> DrawBatchOrder.key(-1, 0, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> DrawBatchOrder.key(0, DrawBatchOrder.MAX_TEXTURES, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> DrawBatchOrder.key(0, -1, 1.0f));
    }

    @Test
    void sortingBeyondTheArrayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DrawBatchOrder.sort(new long[2], 3));
    }

    @Test
    void theFieldWidthsAddUpToSomethingThatFitsInALong() {
        assertEquals(256, DrawBatchOrder.MAX_PIPELINES);
        assertEquals(1_048_576, DrawBatchOrder.MAX_TEXTURES);
        assertEquals(60, DrawBatchOrder.PIPELINE_BITS + DrawBatchOrder.TEXTURE_BITS + 32,
                "60 bits, so the key is never negative and a signed long sort orders it correctly");
    }

    @Test
    void theFieldsDoNotOverlap() {
        long key = DrawBatchOrder.key(255, DrawBatchOrder.MAX_TEXTURES - 1, 1.0f);

        assertEquals(255, DrawBatchOrder.pipelineOf(key));
        assertEquals(DrawBatchOrder.MAX_TEXTURES - 1, DrawBatchOrder.textureOf(key));
        assertEquals(1.0f, DrawBatchOrder.sortableToDepth((int) key), 0.0f);
    }
}
