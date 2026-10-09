package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightUpdateBatcherTest {

    private static final int LENGTH = 40;
    private static final LightEngine.OpacitySource AIR = (x, y, z) -> 0;

    private static LightEngine corridor() {
        return new LightEngine(new LightField(0, 0, 0, LENGTH, 1, 1));
    }

    private static int reachOf(LightEngine engine, int centre) {
        int reach = 0;
        while (centre + reach + 1 < LENGTH && engine.field().get(centre + reach + 1, 0, 0) > 0) {
            reach++;
        }
        return reach;
    }

    @Test
    void aChangeThatCannotAffectLightIsNotQueued() {
        // A piston pushing stone around is the common case: hundreds of block changes per tick, none of
        // them emitting. Queuing work for those would be pure overhead in exactly the situation the
        // batcher exists to make cheap.
        LightUpdateBatcher batcher = new LightUpdateBatcher();
        for (int i = 0; i < 300; i++) {
            batcher.addChange(i, 64, 0, 0, 0);
        }
        assertEquals(300, batcher.changesReceived());
        assertEquals(300, batcher.updatesCoalesced());
        assertEquals(0, batcher.pendingCount());
        assertTrue(batcher.isEmpty());
    }

    @Test
    void repeatChangesToTheSameBlockFoldIntoOne() {
        // A lever flipped twice within one batch, or a torch broken and replaced, should cost one
        // update. Without folding, the second change re-does the whole removal and re-addition of a
        // region the first one just computed.
        LightUpdateBatcher batcher = new LightUpdateBatcher();
        batcher.addChange(5, 0, 0, 0, 14);
        batcher.addChange(5, 0, 0, 14, 9);
        batcher.addChange(5, 0, 0, 9, 11);

        assertEquals(3, batcher.changesReceived());
        assertEquals(2, batcher.updatesCoalesced());
        assertEquals(1, batcher.pendingCount(), "three changes to one block are one update");
    }

    @Test
    void removalHappensBeforeAdditionAndTheOrderIsObservable() {
        // Not a detail. A source only ever raises what it reaches, so adding the dimmer new level
        // first does nothing -- the field still holds the old brighter value -- and the subsequent
        // removal then clears the whole region and leaves it dark. Doing it in this order gives the
        // correct narrower pool of light; the reverse gives nothing at all.
        LightEngine engine = corridor();
        LightUpdateBatcher batcher = new LightUpdateBatcher();
        batcher.addChange(20, 0, 0, 0, 10);
        batcher.process(engine, AIR);
        // A level-10 source lights nine blocks either side, not ten: the source cell itself holds
        // the 10 and each step away costs one, so the tenth block out is already at zero.
        assertEquals(9, reachOf(engine, 20), "the first state");

        LightUpdateBatcher dimmed = new LightUpdateBatcher();
        dimmed.addChange(20, 0, 0, 10, 5);
        dimmed.process(engine, AIR);

        assertEquals(4, reachOf(engine, 20), "the dimmer source's own reach, not the old one's");
        assertEquals(5, engine.field().get(20, 0, 0));
        assertEquals(0, engine.field().get(26, 0, 0), "and the old reach is genuinely gone");
    }

    @Test
    void theBudgetCutsABatchShortAndLeavesTheRestPending() {
        // A light update has no natural upper bound, and running an unbounded one inside a tick is felt
        // as a hitch rather than as low FPS. Counting the budget in cells visited rather than wall
        // clock keeps it meaningful across machines, since cell count is what the cost is proportional
        // to.
        LightEngine engine = corridor();
        LightUpdateBatcher batcher = new LightUpdateBatcher(1);
        for (int i = 0; i < 6; i++) {
            batcher.addChange(i * 3, 0, 0, 0, 14);
        }
        assertEquals(6, batcher.pendingCount());

        int completed = batcher.process(engine, AIR);
        assertEquals(1, completed, "one column per call once the budget is spent");
        assertEquals(5, batcher.pendingCount(), "the rest waits for the next frame");
        // Every call but the last stopped with work still waiting, so each of those counted.
        assertEquals(5, batcher.budgetExhausted());

        while (!batcher.isEmpty()) {
            batcher.process(engine, AIR);
        }
        assertEquals(0, batcher.pendingCount());
        for (int i = 0; i < 6; i++) {
            assertEquals(14, engine.field().get(i * 3, 0, 0), "every source was eventually placed at " + i);
        }
    }

    @Test
    void processingAnEmptyQueueDoesNothing() {
        LightEngine engine = corridor();
        LightUpdateBatcher batcher = new LightUpdateBatcher();
        assertEquals(0, batcher.process(engine, AIR));
        assertEquals(0, batcher.batchesStarted());
        assertEquals(0, batcher.cellsPropagated());
    }

    @Test
    void theCostIsReportedInCellsSoItCanBeBudgetedAgainst() {
        LightEngine engine = corridor();
        LightUpdateBatcher batcher = new LightUpdateBatcher();
        batcher.addChange(20, 0, 0, 0, 15);
        batcher.process(engine, AIR);

        assertTrue(batcher.cellsPropagated() > 0);
        assertEquals(batcher.cellsPropagated(), engine.cellsVisited(),
                "the batcher's accounting agrees with the engine's");
    }

    @Test
    void aBatchOfSourcesIsCheaperThanPlacingThemOneAtATime() {
        // The coalescing payoff. Six sources in one corridor overlap heavily, so propagating them
        // together visits far fewer cells than propagating each and letting each one redo the shared
        // ground.
        LightEngine separate = corridor();
        for (int i = 0; i < 6; i++) {
            separate.addSource(i * 3, 0, 0, 14, AIR);
        }

        LightEngine together = corridor();
        // Set every source first, then propagate once, which is what coalescing amounts to.
        for (int i = 0; i < 6; i++) {
            together.field().set(i * 3, 0, 0, 14);
        }
        together.propagate(AIR);

        for (int x = 0; x < LENGTH; x++) {
            assertEquals(separate.field().get(x, 0, 0), together.field().get(x, 0, 0), "same light at x=" + x);
        }
        assertTrue(together.cellsVisited() < separate.cellsVisited(),
                "batched visited " + together.cellsVisited() + " against " + separate.cellsVisited());
    }

    @Test
    void aColumnKeyGroupsTheBlocksThatShareAnUpdate() {
        assertEquals(LightUpdateBatcher.columnOf(0, 0), LightUpdateBatcher.columnOf(15, 15));
        // -1 >> 4 is -1, so block -1 lives in section -1 and not section 0; -16 is the first block
        // of that same section. Getting the shift wrong would fold the negative octant into one.
        assertEquals(LightUpdateBatcher.columnOf(-1, -1), LightUpdateBatcher.columnOf(-16, -16));
        assertTrue(LightUpdateBatcher.columnOf(0, 0) != LightUpdateBatcher.columnOf(-1, -1));
        assertTrue(LightUpdateBatcher.columnOf(0, 0) != LightUpdateBatcher.columnOf(16, 0));
    }

    @Test
    void clearDropsPendingWorkButKeepsTheCounters() {
        LightUpdateBatcher batcher = new LightUpdateBatcher();
        batcher.addChange(0, 0, 0, 0, 9);
        batcher.clear();
        assertTrue(batcher.isEmpty());
        assertEquals(1, batcher.changesReceived(), "the run's totals survive");

        assertThrows(IllegalArgumentException.class, () -> new LightUpdateBatcher(0));
        assertEquals(LightUpdateBatcher.DEFAULT_BUDGET, new LightUpdateBatcher().budget());
    }
}
