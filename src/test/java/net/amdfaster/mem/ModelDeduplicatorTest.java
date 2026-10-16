package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDeduplicatorTest {

    private static float[] geometry(float offset) {
        float[] g = new float[20];
        for (int v = 0; v < 4; v++) {
            g[v * 5] = offset + v;
            g[v * 5 + 1] = offset;
            g[v * 5 + 2] = offset;
            g[v * 5 + 3] = v < 2 ? 0f : 1f;
            g[v * 5 + 4] = v == 0 || v == 3 ? 0f : 1f;
        }
        return g;
    }

    private static long[] quad(float offset, int colour, int light, int tint) {
        int[] colours = {colour, colour, colour, colour};
        int[] lights = {light, light, light, light};
        return ModelDeduplicator.packQuad(geometry(offset), colours, lights, tint);
    }

    @Test
    void theSameQuadInternsToTheSameInstance() {
        // Identity, not equality. Interning that returned an equal but distinct array would pass every
        // equality test and save nothing, which is the whole point of the class.
        ModelDeduplicator dedup = new ModelDeduplicator();

        long[] first = dedup.internQuad(quad(0f, 0xFFFFFF, 15, -1));
        long[] second = dedup.internQuad(quad(0f, 0xFFFFFF, 15, -1));

        assertSame(first, second, "the same object, so the memory is shared");
        assertEquals(1, dedup.quadCount());
        assertEquals(1L, dedup.quadDuplicates());
    }

    @Test
    void aDifferentTintIndexIsADifferentQuad() {
        // Merging these draws a grass block with a stone colour. Not a memory bug, a rendering one.
        ModelDeduplicator dedup = new ModelDeduplicator();

        long[] grass = dedup.internQuad(quad(0f, 0xFFFFFF, 15, 0));
        long[] stone = dedup.internQuad(quad(0f, 0xFFFFFF, 15, -1));

        assertNotSame(grass, stone);
        assertEquals(2, dedup.quadCount());
    }

    @Test
    void aDifferentLightIsADifferentQuad() {
        // Merging these draws a lit face as dark.
        ModelDeduplicator dedup = new ModelDeduplicator();

        long[] lit = dedup.internQuad(quad(0f, 0xFFFFFF, 15, -1));
        long[] dark = dedup.internQuad(quad(0f, 0xFFFFFF, 0, -1));

        assertNotSame(lit, dark);
        assertEquals(2, dedup.quadCount());
    }

    @Test
    void aDifferentColourIsADifferentQuad() {
        ModelDeduplicator dedup = new ModelDeduplicator();

        assertNotSame(dedup.internQuad(quad(0f, 0xFFFFFF, 15, -1)),
                dedup.internQuad(quad(0f, 0xFF0000, 15, -1)));
        assertEquals(2, dedup.quadCount());
    }

    @Test
    void aDifferentPositionIsADifferentQuad() {
        ModelDeduplicator dedup = new ModelDeduplicator();

        assertNotSame(dedup.internQuad(quad(0f, 0xFFFFFF, 15, -1)),
                dedup.internQuad(quad(0.0625f, 0xFFFFFF, 15, -1)),
                "a sixteenth of a block apart is not the same quad");
        assertEquals(2, dedup.quadCount());
    }

    @Test
    void negativeZeroAndZeroStayDistinct() {
        // They compare equal as floats, so a deduplicator that compared values would merge them. A quad
        // built with -0.0 is not necessarily the quad built with 0.0, and treating them as the same is
        // the subtle merge this class must not do.
        float[] a = geometry(0f);
        float[] b = geometry(0f);
        b[1] = -0.0f;
        assertTrue(0.0f == -0.0f, "they are equal as floats");

        ModelDeduplicator dedup = new ModelDeduplicator();
        long[] first = dedup.internQuad(
                ModelDeduplicator.packQuad(a, new int[] {0, 0, 0, 0}, new int[] {0, 0, 0, 0}, -1));
        long[] second = dedup.internQuad(
                ModelDeduplicator.packQuad(b, new int[] {0, 0, 0, 0}, new int[] {0, 0, 0, 0}, -1));

        assertNotSame(first, second, "distinct bit patterns stay distinct");
        assertEquals(2, dedup.quadCount());
    }

    @Test
    void twentyThousandRepeatedQuadsCollapseToAHandful() {
        // The shape of the real data: tens of thousands of block states, each baking the same fence arm
        // or stair variant. FerriteCore reports multipart instances going from about 200,000 to 1,500.
        ModelDeduplicator dedup = new ModelDeduplicator(4096);
        Random random = new Random(5L);

        for (int i = 0; i < 20_000; i++) {
            dedup.internQuad(quad(i % 50, 0xFFFFFF, i % 16, -1));
        }

        assertEquals(800, dedup.quadCount(), "50 positions times 16 light levels");
        assertEquals(20_000L, dedup.quadLookups());
        assertEquals(96.0, dedup.quadSavedPercent(), 0.01);
    }

    @Test
    void distinctQuadsAllSurviveGrowth() {
        ModelDeduplicator dedup = new ModelDeduplicator(16);
        Set<long[]> seen = new HashSet<>();

        for (int i = 0; i < 3000; i++) {
            seen.add(dedup.internQuad(quad(i, 0xFFFFFF, 15, -1)));
        }

        assertEquals(3000, dedup.quadCount(), "nothing was lost while growing");
        assertEquals(3000, seen.size(), "and every one is a distinct object");

        // The canonical instances are still the ones handed out.
        for (int i = 0; i < 3000; i++) {
            assertSame(dedup.internQuad(quad(i, 0xFFFFFF, 15, -1)),
                    dedup.internQuad(quad(i, 0xFFFFFF, 15, -1)), "quad " + i);
        }
    }

    @Test
    void instancesDeduplicateSeparatelyFromQuads() {
        ModelDeduplicator dedup = new ModelDeduplicator();

        for (int i = 0; i < 1000; i++) {
            dedup.internInstance(new long[] {i % 25, i % 7});
        }

        assertEquals(175, dedup.instanceCount(), "25 by 7 distinct predicates");
        assertEquals(825L, dedup.instanceDuplicates());
        assertEquals(0, dedup.quadCount(), "and the quad table is untouched");
    }

    @Test
    void growingMovesBothTables() {
        // The two tables share a capacity, so either growth has to move both or the other's indices
        // would point into a table of the old size.
        ModelDeduplicator dedup = new ModelDeduplicator(16);

        for (int i = 0; i < 100; i++) {
            dedup.internInstance(new long[] {i});
        }
        for (int i = 0; i < 100; i++) {
            dedup.internQuad(quad(i, 0xFFFFFF, 15, -1));
        }

        assertEquals(100, dedup.instanceCount(), "instances survived the quad growth");
        assertEquals(100, dedup.quadCount());

        for (int i = 0; i < 100; i++) {
            assertSame(dedup.internInstance(new long[] {i}),
                    dedup.internInstance(new long[] {i}), "instance " + i);
        }
    }

    @Test
    void theStoredContentIsCopiedSoTheCallerCannotChangeIt() {
        // Every other holder of the same quad would see the change, which is a corruption that shows up
        // as one block's model changing when another is loaded.
        ModelDeduplicator dedup = new ModelDeduplicator();
        long[] content = quad(0f, 0xFFFFFF, 15, -1);
        long[] interned = dedup.internQuad(content);
        long[] before = interned.clone();

        content[0] = 0xDEADBEEFL;

        assertSame(interned, dedup.internQuad(before), "the original still matches");
        assertEquals(before[0], interned[0], "and the stored copy did not move");
    }

    @Test
    void clearDropsEverything() {
        ModelDeduplicator dedup = new ModelDeduplicator();
        dedup.internQuad(quad(0f, 0xFFFFFF, 15, -1));
        dedup.internInstance(new long[] {1});

        dedup.clear();

        assertEquals(0, dedup.quadCount());
        assertEquals(0, dedup.instanceCount());
    }

    @Test
    void theSavingIsReportedInBytes() {
        ModelDeduplicator dedup = new ModelDeduplicator();
        for (int i = 0; i < 1000; i++) {
            dedup.internQuad(quad(i % 10, 0xFFFFFF, 15, -1));
        }

        assertEquals(10, dedup.quadCount());
        assertEquals(10L * ModelDeduplicator.QUAD_BYTES, dedup.quadBytes());
        assertEquals(1000L * ModelDeduplicator.QUAD_BYTES, dedup.quadBytesWithoutDedup());
        assertEquals(25 * 8, ModelDeduplicator.QUAD_BYTES);
        assertEquals(99.0, dedup.quadSavedPercent(), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ModelDeduplicator dedup = new ModelDeduplicator();

        assertThrows(IllegalArgumentException.class, () -> new ModelDeduplicator(8));
        assertThrows(IllegalArgumentException.class,
                () -> ModelDeduplicator.packQuad(new float[19], new int[4], new int[4], -1));
        assertThrows(IllegalArgumentException.class,
                () -> ModelDeduplicator.packQuad(new float[20], new int[3], new int[4], -1));
        assertThrows(IllegalArgumentException.class,
                () -> dedup.internQuad(new long[ModelDeduplicator.QUAD_LONGS - 1]));
        assertThrows(IllegalArgumentException.class, () -> dedup.internInstance(new long[0]));
    }

    @Test
    void aFreshDeduplicatorReportsZeroNotNaN() {
        ModelDeduplicator dedup = new ModelDeduplicator();
        assertEquals(0.0, dedup.quadSavedPercent());
        assertEquals(0, dedup.quadCount());
        assertEquals(0, dedup.instanceCount());
        assertEquals(25, ModelDeduplicator.QUAD_LONGS);
        assertEquals(5, ModelDeduplicator.FLOATS_PER_VERTEX);
        assertFalse(dedup.quadLookups() > 0);
    }
}
