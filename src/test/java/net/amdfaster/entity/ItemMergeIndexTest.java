package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemMergeIndexTest {

    @Test
    void aNeighbourInTheSameCellIsFound() {
        ItemMergeIndex index = new ItemMergeIndex();
        index.add(1, 0.5, 64.0, 0.5);
        index.add(2, 0.8, 64.0, 0.8);

        List<Integer> found = index.candidates(0.5, 64.0, 0.5);

        assertTrue(found.contains(1));
        assertTrue(found.contains(2));
    }

    @Test
    void anItemAcrossACellBoundaryIsStillFound() {
        // The merge radius is one metre and the cell is two, so an item at 1.9 and one at 2.1 are close
        // enough to merge but sit in different cells. Missing this is the whole failure mode.
        ItemMergeIndex index = new ItemMergeIndex();
        index.add(1, 1.9, 64.0, 0.0);
        index.add(2, 2.1, 64.0, 0.0);

        assertTrue(index.candidates(1.9, 64.0, 0.0).contains(2), "just across the boundary");
        assertTrue(index.candidates(2.1, 64.0, 0.0).contains(1), "and the other way round");
        assertEquals(0.2, Math.abs(2.1 - 1.9), 1e-9, "well inside the one metre radius");
    }

    @Test
    void theSpanFollowsTheRadius() {
        // Derived, not hard-coded. If the radius were ever raised above the cell size, a fixed span of
        // one cell would silently start missing merges.
        assertEquals(1, ItemMergeIndex.span(), "ceil(1.0 / 2.0)");
        assertTrue(ItemMergeIndex.MERGE_RADIUS <= ItemMergeIndex.CELL_METRES);
    }

    @Test
    void negativeCoordinatesShareACellWithTheirNeighbours() {
        // Floored, not truncated. Truncating would pull everything near the origin toward zero and split
        // the area, missing merges there -- which is exactly where a player builds their farm.
        ItemMergeIndex index = new ItemMergeIndex();
        index.add(1, -0.5, 64.0, -0.5);
        index.add(2, -0.8, 64.0, -0.8);

        assertEquals(
                ItemMergeIndex.bucketOf(-0.5, 64.0, -0.5),
                ItemMergeIndex.bucketOf(-0.8, 64.0, -0.8));
        assertTrue(index.candidates(-0.5, 64.0, -0.5).contains(2));
    }

    @Test
    void distantItemsAreNotCandidates() {
        // The other half of the saving: the caller's distance check still runs, but it runs on far fewer
        // items.
        ItemMergeIndex index = new ItemMergeIndex();
        index.add(1, 0.0, 64.0, 0.0);
        index.add(2, 100.0, 64.0, 0.0);

        List<Integer> found = index.candidates(0.0, 64.0, 0.0);

        assertTrue(found.contains(1));
        assertFalse(found.contains(2), "fifty cells away");
    }

    @Test
    void identicalStacksMerge() {
        ItemMergeIndex index = new ItemMergeIndex();

        assertTrue(index.canMerge(5, 0, 0L, -1, 10, 5, 0, 0L, -1, 20));
        assertEquals(1L, index.merges());
    }

    @Test
    void aDifferentKindDoesNotMerge() {
        ItemMergeIndex index = new ItemMergeIndex();

        assertFalse(index.canMerge(5, 0, 0L, -1, 10, 6, 0, 0L, -1, 10));
        assertEquals(1L, index.refused());
    }

    @Test
    void aDamagedToolDoesNotMergeIntoAnUndamagedOne() {
        // Getting this wrong destroys the damage value, and the player loses an item they can see they
        // had. That is not a performance problem.
        ItemMergeIndex index = new ItemMergeIndex();

        assertFalse(index.canMerge(5, 0, 0L, -1, 1, 5, 17, 0L, -1, 1));
    }

    @Test
    void aTaggedItemDoesNotMergeIntoAPlainOne() {
        // Merging a named or enchanted item into a plain stack destroys the tag.
        ItemMergeIndex index = new ItemMergeIndex();

        assertFalse(index.canMerge(5, 0, 12345L, -1, 1, 5, 0, 0L, -1, 1));
        assertTrue(index.canMerge(5, 0, 12345L, -1, 1, 5, 0, 12345L, -1, 1), "the same tag does");
    }

    @Test
    void itemsWithDifferentOwnersDoNotMerge() {
        // The owner controls who may pick the drop up. Merging across owners would let one player take
        // another's drops early.
        ItemMergeIndex index = new ItemMergeIndex();

        assertFalse(index.canMerge(5, 0, 0L, 7, 10, 5, 0, 0L, 9, 10));
        assertTrue(index.canMerge(5, 0, 0L, 7, 10, 5, 0, 0L, 7, 10));
    }

    @Test
    void mergingPastTheStackLimitIsRefusedEntirely() {
        // Vanilla leaves both stacks alone rather than splitting one, so a partial merge here would
        // change behaviour rather than just speed it up.
        ItemMergeIndex index = new ItemMergeIndex();

        assertTrue(index.canMerge(5, 0, 0L, -1, 32, 5, 0, 0L, -1, 32), "exactly 64");
        assertFalse(index.canMerge(5, 0, 0L, -1, 33, 5, 0, 0L, -1, 32), "65");
        assertEquals(64, ItemMergeIndex.STACK_LIMIT);
    }

    @Test
    void removingAnItemTakesItOutOfTheSearch() {
        ItemMergeIndex index = new ItemMergeIndex();
        index.add(1, 0.0, 64.0, 0.0);
        index.add(2, 0.5, 64.0, 0.0);

        index.remove(1, 0.0, 64.0, 0.0);

        assertFalse(index.candidates(0.5, 64.0, 0.0).contains(1));
        assertEquals(1, index.itemCount());
    }

    @Test
    void aFarmPileIsMuchCheaperToSearch() {
        // The scenario this exists for: two thousand drops from an automatic farm, all landing in a
        // small area, where the scan degenerates to every item against every other.
        ItemMergeIndex index = new ItemMergeIndex();

        for (int i = 0; i < 2000; i++) {
            // Spread over ten metres, so several cells but still one pile.
            index.add(i, (i % 10) * 1.0, 64.0, (i / 10 % 10) * 1.0);
        }

        long withIndex = 0;
        for (int i = 0; i < 2000; i++) {
            withIndex += index.candidates((i % 10) * 1.0, 64.0, (i / 10 % 10) * 1.0).size();
        }
        index.comparisons();
        assertEquals(2000, index.itemCount());

        long without = index.comparisonsWithoutIndex();
        assertEquals(2000L * 1999, without);
        assertTrue(withIndex < without, "bucketing has to help: " + withIndex + " vs " + without);
    }

    @Test
    void aFreshIndexReportsZeroNotNaN() {
        ItemMergeIndex index = new ItemMergeIndex();
        assertEquals(0.0, index.savedPercent());
        assertEquals(0, index.itemCount());
        assertEquals(0L, index.comparisons());
    }
}
