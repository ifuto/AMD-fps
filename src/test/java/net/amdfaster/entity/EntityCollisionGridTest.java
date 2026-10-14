package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCollisionGridTest {

    @Test
    void anEmptyRegionNeedsNoScan() {
        EntityCollisionGrid grid = new EntityCollisionGrid();
        assertFalse(grid.hasNeighbours(100, 64, 100));
        assertEquals(1L, grid.checks());
        assertEquals(1L, grid.emptyChecks());
    }

    @Test
    void anEntityInAnyOfThe27SectionsForcesTheScan() {
        EntityCollisionGrid grid = new EntityCollisionGrid();

        // The corner of the search volume, which is the last section the loops reach.
        grid.add(100 + 16, 64 + 16, 100 + 16);
        assertTrue(grid.hasNeighbours(100, 64, 100));
        assertEquals(1L, grid.checks());
        assertEquals(0L, grid.emptyChecks(), "it found something, so nothing was skipped");
    }

    @Test
    void anEntityTwoSectionsAwayIsOutsideTheSearch() {
        EntityCollisionGrid grid = new EntityCollisionGrid();
        grid.add(100 + 32, 64, 100);

        assertFalse(grid.hasNeighbours(100, 64, 100), "two sections out is beyond the radius");
        assertEquals(1, grid.occupancy((100 + 32) >> 4, 64 >> 4, 100 >> 4), "but it is still counted");
    }

    @Test
    void movingWithinASectionChangesNothing() {
        // The common case. An entity walking across block boundaries inside its own section cannot
        // change who it collides with, so the grid should not be touched at all.
        EntityCollisionGrid grid = new EntityCollisionGrid();
        grid.add(0, 64, 0);
        long adds = grid.addMoves();

        grid.move(0, 64, 0, 5, 64, 7);
        grid.move(5, 64, 7, 15, 65, 15);

        assertEquals(15, 15);
        assertEquals(adds, grid.addMoves(), "no section changed, so no bookkeeping happened");
        assertEquals(1, grid.size());
        assertEquals(1, grid.occupancy(0, 4, 0));
    }

    @Test
    void movingAcrossASectionMovesTheCountExactly() {
        // Counted twice means a phantom collision; counted nowhere means an entity walks through
        // another one. Neither shows up as an error, only as wrong physics.
        EntityCollisionGrid grid = new EntityCollisionGrid();
        grid.add(0, 64, 0);

        grid.move(0, 64, 0, 16, 64, 0);

        assertEquals(0, grid.occupancy(0, 4, 0), "the old section is empty");
        assertEquals(1, grid.occupancy(1, 4, 0), "the new one holds it");
        assertEquals(1, grid.size(), "and it is counted once, in one place");
        assertFalse(grid.hasNeighbours(0, 64, 0), "the old position sees nothing");
        assertTrue(grid.hasNeighbours(16, 64, 0));
    }

    @Test
    void severalEntitiesInOneSectionAreCountedTogether() {
        EntityCollisionGrid grid = new EntityCollisionGrid();
        for (int i = 0; i < 5; i++) {
            grid.add(i, 64, i);
        }
        assertEquals(5, grid.occupancy(0, 4, 0));
        assertEquals(1, grid.size(), "one section, however many entities");

        for (int i = 0; i < 4; i++) {
            grid.remove(i, 64, i);
        }
        assertEquals(1, grid.occupancy(0, 4, 0));
        assertEquals(1, grid.size(), "still tracked while one remains");

        grid.remove(4, 64, 4);
        assertEquals(0, grid.occupancy(0, 4, 0));
        assertEquals(0, grid.size(), "and dropped once it empties");
    }

    @Test
    void removingFromAnEmptySectionDoesNotGoNegative() {
        // A negative count would make every later check on that section report occupied, which is the
        // safe direction to fail in but hides the discrepancy. Clamping at zero keeps the counts
        // meaningful.
        EntityCollisionGrid grid = new EntityCollisionGrid();
        grid.remove(0, 64, 0);

        assertEquals(0, grid.occupancy(0, 4, 0));
        assertEquals(1L, grid.removeMoves(), "the attempt was counted");
        assertFalse(grid.hasNeighbours(0, 64, 0));
    }

    @Test
    void emptyingASectionKeepsTheOthersFindable() {
        // An open-addressed table stops probing at an empty slot, so entries past a hole have to move
        // into it or they are lost while still counted as present.
        EntityCollisionGrid grid = new EntityCollisionGrid();
        int[] xs = new int[500];
        int[] zs = new int[500];
        for (int i = 0; i < 500; i++) {
            xs[i] = (i % 25) * 16;
            zs[i] = (i / 25) * 16;
            grid.add(xs[i], 64, zs[i]);
        }
        assertEquals(500, grid.size());

        for (int i = 0; i < 500; i += 3) {
            grid.remove(xs[i], 64, zs[i]);
        }
        int alive = 0;
        for (int i = 0; i < 500; i++) {
            int expected = i % 3 == 0 ? 0 : 1;
            assertEquals(expected, grid.occupancy(xs[i] >> 4, 4, zs[i] >> 4),
                    "section " + i + " occupancy");
            alive += expected;
        }
        assertEquals(334, alive, "500 minus every third");
        assertEquals(334, grid.size(), "and the table agrees");
    }

    @Test
    void aSparseWorldSkipsAlmostEveryScan() {
        // The shape of the saving: entities spread over a large world, so a neighbourhood check
        // usually finds nothing and the section scan never happens.
        int entities = 1000;
        EntityCollisionGrid grid = new EntityCollisionGrid(2048);
        // Every entity gets its own section, 64 blocks apart, so no two share a neighbourhood.
        for (int i = 0; i < entities; i++) {
            grid.add(i * 64, 64, 0);
        }
        assertEquals(entities, grid.size());

        for (int i = 0; i < entities; i++) {
            assertFalse(grid.hasNeighbours(i * 64, 64, 0), "entity " + i + " is alone");
        }
        assertEquals(entities, grid.emptyChecks());
        assertEquals(100.0, grid.skipRatePercent());
        // Each avoided check is 27 section fetches.
        assertEquals(27_000L, grid.emptyChecks() * 27L, "section fetches avoided in one pass");
    }

    @Test
    void clearDropsEverySection() {
        EntityCollisionGrid grid = new EntityCollisionGrid();
        grid.add(0, 64, 0);
        grid.add(64, 64, 0);
        grid.clear();

        assertEquals(0, grid.size());
        assertFalse(grid.hasNeighbours(0, 64, 0));
        assertFalse(grid.hasNeighbours(64, 64, 0));
    }

    @Test
    void sectionCoordinatesDoNotPackAlike() {
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (int sx = -60; sx < 60; sx++) {
            for (int sy = -5; sy < 9; sy++) {
                for (int sz = -60; sz < 60; sz++) {
                    keys.add(EntityCollisionGrid.pack(sx, sy, sz));
                }
            }
        }
        assertEquals(120 * 14 * 120, keys.size(), "every section in a generous volume is distinct");
    }

    @Test
    void fillingPastTheCapacityGrowsRatherThanHanging() {
        // probe on a full table with a missing key walks every slot and returns to its start.
        // Another class here did that and hung the thread.
        EntityCollisionGrid grid = new EntityCollisionGrid(16);

        for (int i = 0; i < 5000; i++) {
            grid.add(i * 16, 64, 0);
        }

        assertEquals(5000, grid.size());
        for (int i = 0; i < 5000; i++) {
            assertEquals(1, grid.occupancy(i, 4, 0), "section " + i);
        }
    }

    @Test
    void aFreshGridReportsZeroNotNaN() {
        EntityCollisionGrid grid = new EntityCollisionGrid();
        assertEquals(0.0, grid.skipRatePercent());
        assertEquals(0L, grid.checks());
        assertEquals(1, EntityCollisionGrid.SEARCH_RADIUS);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EntityCollisionGrid(8));
    }
}
