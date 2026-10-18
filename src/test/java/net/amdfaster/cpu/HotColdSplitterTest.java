package net.amdfaster.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.cpu.HotColdSplitter.Field;
import net.amdfaster.cpu.HotColdSplitter.Split;
import org.junit.jupiter.api.Test;

class HotColdSplitterTest {

    /** The batching record: seven 4 byte fields, of which halfWidth and height are not read per frame. */
    private static final Field[] ENTITY_INSTANCE = {
            new Field("modelKey", 4, true),
            new Field("x", 4, true),
            new Field("y", 4, true),
            new Field("z", 4, true),
            new Field("yaw", 4, true),
            new Field("light", 4, true),
            new Field("halfWidth", 4, false),
            new Field("height", 4, false),
    };

    /** The 32 byte GPU instance: only position and yaw are needed to place a draw. */
    private static final Field[] GPU_INSTANCE = {
            new Field("x", 4, true),
            new Field("y", 4, true),
            new Field("z", 4, true),
            new Field("yaw", 4, true),
            new Field("light", 4, false),
            new Field("halfWidth", 4, false),
            new Field("height", 4, false),
            new Field("padding", 4, false),
    };

    @Test
    void theCacheLineIs64Bytes() {
        assertEquals(64, HotColdSplitter.CACHE_LINE_BYTES);
    }

    @Test
    void splittingTheBatchingRecordIsNotWorthIt() {
        // The honest answer, and the one this class exists to give. 32 bytes fits two records per line,
        // and the hot 24 bytes also fits two -- so the split would add a second array to keep in step and
        // buy exactly nothing. Reporting a saving here would be reporting a byte count rather than a
        // cache effect.
        Split split = HotColdSplitter.plan(ENTITY_INSTANCE, 0);

        assertEquals(24, split.hotBytes());
        assertEquals(8, split.coldBytes());
        assertEquals(32, split.combinedBytes());
        assertEquals(2, split.hotPerLine());
        assertEquals(2, split.combinedPerLine());
        assertEquals(0, split.extraPerLine());
        assertFalse(split.worthSplitting());
        assertEquals(0.0, split.linesAvoidedPercent(), 1e-12);
        assertFalse(HotColdSplitter.worthSplitting(ENTITY_INSTANCE, 0));
    }

    @Test
    void addingTheIndexMakesItWorseNotBetter() {
        // The index only exists to reach the cold half, so it is 4 more hot bytes for nothing: 28 bytes
        // still fits two per line. This is why the planner adds it only when there is something cold.
        Split split = HotColdSplitter.plan(ENTITY_INSTANCE, 4);

        assertEquals(28, split.hotBytes());
        assertEquals(8, split.coldBytes());
        assertEquals(2, split.hotPerLine(), "unchanged, and now with an index to chase");
        assertFalse(split.worthSplitting());
    }

    @Test
    void theGpuInstanceSplitDoesCrossTheLineBoundary() {
        // Only position and yaw are needed to place a draw, so light, halfWidth, height and the padding
        // are cold. 20 hot bytes fits three per line against two, which is a third fewer lines walked.
        Split split = HotColdSplitter.plan(GPU_INSTANCE, 4);

        assertEquals(20, split.hotBytes(), "16 bytes of fields plus a 4 byte index");
        assertEquals(16, split.coldBytes());
        assertEquals(32, split.combinedBytes());
        assertEquals(3, split.hotPerLine());
        assertEquals(2, split.combinedPerLine());
        assertEquals(1, split.extraPerLine());
        assertTrue(split.worthSplitting());
        assertEquals(100.0 / 3.0, split.linesAvoidedPercent(), 1e-9);
    }

    @Test
    void theLineBoundariesAreWhereTheBenefitLives() {
        // A field is worth moving to the cold half only if moving it crosses one of these. Between them
        // the split is pure overhead, which is what the batching record above falls into.
        assertEquals(16, 64 / 4);
        assertEquals(4, 64 / 16);
        assertEquals(3, 64 / 20);
        assertEquals(2, 64 / 24);
        assertEquals(2, 64 / 28, "24 and 28 are the same answer, so shrinking between them buys nothing");
        assertEquals(2, 64 / 32);
        assertEquals(1, 64 / 36);
    }

    @Test
    void maxHotBytesIsTheNumberToDesignAgainst() {
        assertEquals(64, HotColdSplitter.maxHotBytesFor(1));
        assertEquals(32, HotColdSplitter.maxHotBytesFor(2));
        assertEquals(16, HotColdSplitter.maxHotBytesFor(4));
        assertEquals(12, HotColdSplitter.maxHotBytesFor(5));
        assertEquals(8, HotColdSplitter.maxHotBytesFor(8));
        assertEquals(4, HotColdSplitter.maxHotBytesFor(16));
    }

    @Test
    void anAllHotRecordHasNoColdHalfAndNoIndex() {
        Field[] allHot = {
                new Field("x", 4, true),
                new Field("y", 4, true),
                new Field("z", 4, true),
                new Field("yaw", 4, true),
        };

        Split split = HotColdSplitter.plan(allHot, 4);

        assertEquals(16, split.hotBytes(), "no index, because there is nothing to reach");
        assertEquals(0, split.coldBytes());
        assertEquals(16, split.combinedBytes());
        assertEquals(4, split.hotPerLine());
        assertFalse(split.worthSplitting(), "nothing to split out");
    }

    @Test
    void anAllColdRecordIsDegenerateButNotAnError() {
        Field[] allCold = {new Field("name", 32, false)};

        Split split = HotColdSplitter.plan(allCold, 4);

        assertEquals(4, split.hotBytes(), "only the index");
        assertEquals(32, split.coldBytes());
        assertEquals(16, split.hotPerLine());
        assertEquals(2, split.combinedPerLine());
        assertTrue(split.worthSplitting(), "the hot record is just an index, so every line it walks is useful");
    }

    @Test
    void anEmptyRecordCostsNothing() {
        Split split = HotColdSplitter.plan(new Field[0], 4);

        assertEquals(0, split.hotBytes());
        assertEquals(0, split.coldBytes());
        assertEquals(64, split.hotPerLine(), "a zero byte record divides into the whole line");
        assertFalse(split.worthSplitting());
        assertEquals(0.0, split.linesAvoidedPercent(), 1e-12);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HotColdSplitter.plan(null, 4));
        assertThrows(IllegalArgumentException.class, () -> HotColdSplitter.plan(ENTITY_INSTANCE, -1));
        assertThrows(IllegalArgumentException.class,
                () -> HotColdSplitter.plan(new Field[] {null}, 4));
        assertThrows(IllegalArgumentException.class,
                () -> HotColdSplitter.plan(new Field[] {new Field(null, 4, true)}, 4));
        assertThrows(IllegalArgumentException.class,
                () -> HotColdSplitter.plan(new Field[] {new Field("x", 0, true)}, 4));
        assertThrows(IllegalArgumentException.class, () -> HotColdSplitter.maxHotBytesFor(0));
    }
}
