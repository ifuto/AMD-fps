package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelPermutationTableTest {

    @Test
    void theTableCoversEveryCombination() {
        // A table missing an entry does not fail loudly -- the lookup returns whatever is at that index, and a
        // block somewhere in the world draws the wrong faces.
        VoxelPermutationTable table = new VoxelPermutationTable();

        assertEquals(VoxelPermutationTable.PERMUTATIONS, table.size());

        for (int mask = 0; mask < VoxelPermutationTable.PERMUTATIONS; mask++) {
            int[] indices = table.mesh(mask);
            assertEquals(VoxelPermutationTable.facesIn(mask) * VoxelPermutationTable.INDICES_PER_FACE,
                    indices.length, "mask " + mask);
        }
    }

    @Test
    void theSameCombinationReturnsTheSameEntry() {
        // Taking the vertices from one table entry means the ones on a shared edge are the same vertex by
        // construction, which is what stops the hairline crack along every block edge.
        VoxelPermutationTable table = new VoxelPermutationTable();

        int[] first = table.mesh(0b000011);
        int[] second = table.mesh(0b000011);

        assertSame(first, second, "one entry, not two copies");
    }

    @Test
    void aBlockWithNoVisibleFacesHasNoGeometry() {
        VoxelPermutationTable table = new VoxelPermutationTable();

        assertEquals(0, table.mesh(0).length);
        assertEquals(0, VoxelPermutationTable.facesIn(0));
    }

    @Test
    void aFullyExposedBlockDrawsSixFaces() {
        VoxelPermutationTable table = new VoxelPermutationTable();

        int[] indices = table.mesh(0b111111);

        assertEquals(6 * VoxelPermutationTable.INDICES_PER_FACE, indices.length);
        assertEquals(6L, table.facesEmitted());
    }

    @Test
    void theCornersAreSharedRatherThanRepeated() {
        // An entry holds the eight corners once and indexes into them per face, rather than holding four per
        // face. Indices are a byte and positions are not, so the total is smaller.
        assertEquals(4, VoxelPermutationTable.verticesSaved(3), "twelve down to eight");
        assertEquals(16, VoxelPermutationTable.verticesSaved(6), "twenty-four down to eight");
        assertEquals(0, VoxelPermutationTable.verticesSaved(2), "and no saving at two faces");
    }

    @Test
    void theTableHoldsEveryFaceOfEveryCombination() {
        VoxelPermutationTable table = new VoxelPermutationTable();

        // Each of the six faces appears in half of the sixty-four combinations.
        assertEquals(6 * 32 * VoxelPermutationTable.INDICES_PER_FACE, table.totalIndices());
    }

    @Test
    void aSectionOfBlocksIsMostlyLookups() {
        VoxelPermutationTable table = new VoxelPermutationTable();

        for (int block = 0; block < 1000; block++) {
            table.mesh(block % VoxelPermutationTable.PERMUTATIONS);
        }

        assertEquals(1000L, table.lookups());
        assertEquals(1000L, table.blocksMeshed());
        assertEquals(33.33, table.savedPercent(3), 0.01);
        assertEquals(66.67, table.savedPercent(6), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        VoxelPermutationTable table = new VoxelPermutationTable();

        assertThrows(IllegalArgumentException.class, () -> table.mesh(-1));
        assertThrows(IllegalArgumentException.class, () -> table.mesh(64));
    }

    @Test
    void aFreshTableReportsZeroNotNaN() {
        // A table that has meshed nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        VoxelPermutationTable table = new VoxelPermutationTable();

        assertEquals(0.0, table.savedPercent(6));
        assertEquals(0L, table.lookups());
        assertEquals(0L, table.facesEmitted());
        assertTrue(table.size() > 0, "the table itself is built at construction");
    }
}
