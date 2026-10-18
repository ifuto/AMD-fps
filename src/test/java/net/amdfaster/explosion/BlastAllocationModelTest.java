package net.amdfaster.explosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.explosion.BlastAllocationModel.Blast;
import org.junit.jupiter.api.Test;

class BlastAllocationModelTest {

    @Test
    void oneBlastAllocates13KilobytesOfPositionsAndNoneAfterwards() {
        Blast blast = BlastAllocationModel.perBlast();

        assertEquals(13168, blast.vanillaDistinct(), "823 blocks x 16 bytes");
        assertEquals(389376, blast.vanillaPerLookup(), "24,336 lookups x 16 bytes");
        assertEquals(6584, blast.packedFirstBlast(), "823 entries x 8 bytes");
        assertEquals(0, blast.packedSteady(), "the array is reused, so nothing is allocated again");

        assertEquals(2.0, blast.distinctRatio(), 1e-9, "the conservative comparison");
        assertEquals(59.139732685297695, blast.perLookupRatio(), 1e-12,
                "a ray reaching an already listed block would otherwise make another position");
    }

    @Test
    void youngCollectionFrequencyIsRateOverEden() {
        // A young collection fires when eden fills, so halving the rate doubles the interval. Individual
        // allocation cost barely enters into it -- a TLAB bump is a pointer increment.
        long eden = BlastAllocationModel.edenBytes(4096L * 1024 * 1024, 30);
        assertEquals(1288490188L, eden, "30 per cent of 4 GB");

        double rate = 800L * 1024 * 1024;
        double full = BlastAllocationModel.youngCollectionsPerSecond((long) rate, eden);
        double half = BlastAllocationModel.youngCollectionsPerSecond((long) (rate / 2), eden);

        assertEquals(0.6510416666666666, full, 1e-9);
        assertEquals(full / 2.0, half, 1e-9);
    }

    @Test
    void theYoungGenerationPercentageMovesItMoreThanTheExplosionDoes() {
        // G1 sizes young down to about 5 per cent by default and the common tuning raises it to 30 or 40,
        // which is a sixfold difference in collection frequency for the same allocation rate.
        long heap = 4096L * 1024 * 1024;
        long small = BlastAllocationModel.edenBytes(heap, 5);
        long tuned = BlastAllocationModel.edenBytes(heap, 30);

        assertEquals(6.0, tuned / (double) small, 1e-6, "integer division leaves it a hair above 6");

        long rate = 800L * 1024 * 1024;
        assertEquals(3.90625, BlastAllocationModel.youngCollectionsPerSecond(rate, small), 1e-6);
        assertEquals(0.6510416666666666, BlastAllocationModel.youngCollectionsPerSecond(rate, tuned), 1e-9);
    }

    @Test
    void thisIsNotALargeShareOfTheGamesGarbage() {
        // The number that keeps the claim honest. A hundred blasts a second at the distinct count is
        // 0.16 per cent of 800 MB/s, and even the per-lookup bound stays under 5 per cent.
        Blast blast = BlastAllocationModel.perBlast();

        assertEquals(0.16, BlastAllocationModel.percentOfGameRate(100, blast.vanillaDistinct()), 0.01);
        assertEquals(4.64, BlastAllocationModel.percentOfGameRate(100, blast.vanillaPerLookup()), 0.01);
        assertEquals(0.0157, BlastAllocationModel.percentOfGameRate(10, blast.vanillaDistinct()), 0.0001);
        assertEquals(0.0, BlastAllocationModel.percentOfGameRate(0, blast.vanillaDistinct()), 1e-12);

        assertTrue(BlastAllocationModel.percentOfGameRate(100, blast.vanillaPerLookup()) < 5.0,
                "even the pessimistic bound is a small share, so the argument has to be about where the "
                        + "allocation lands rather than how much there is");
    }

    @Test
    void theDestroyListEscapesSoTheJitCannotRemoveIt() {
        // Escape analysis scalar-replaces an object only while it stays inside the method that made it.
        // The destroy list is a List<BlockPos> handed to the level, so every position in it is stored in
        // a collection and passed out -- globally escaping, allocated for real, and no compiler setting
        // changes that.
        assertTrue(BlastAllocationModel.canScalarReplace(false, false, false));
        assertFalse(BlastAllocationModel.canScalarReplace(true, false, false), "stored in a field");
        assertFalse(BlastAllocationModel.canScalarReplace(false, true, false), "put in a collection");
        assertFalse(BlastAllocationModel.canScalarReplace(false, false, true), "returned to a caller");
        assertTrue(BlastAllocationModel.destroyListEscapes());
    }

    @Test
    void anAllocationTheJitMissesCostsAboutDouble() {
        // 1.919 ns and no bytes against 3.781 ns and 16. Small per object, and the collection frequency
        // effect is what shows up in a frame time.
        assertEquals(1.919, BlastAllocationModel.NS_SCALAR_REPLACED, 1e-12);
        assertEquals(3.781, BlastAllocationModel.NS_NOT_REPLACED, 1e-12);
        assertEquals(3.781 / 1.919, BlastAllocationModel.scalarReplacementSlowdown(), 1e-12);
        assertEquals(1.97, BlastAllocationModel.scalarReplacementSlowdown(), 0.01);
    }

    @Test
    void theConstantsAgreeWithTheClassesTheyComeFrom() {
        assertEquals(BlastDamageBuffer.BLOCKS_PER_BLAST, BlastAllocationModel.BLOCKS_PER_BLAST);
        assertEquals(ChainReactionDeduplicator.LOOKUPS_PER_BLAST, BlastAllocationModel.LOOKUPS_PER_BLAST);
        assertEquals(BlastDamageBuffer.PACKED_BYTES_PER_ENTRY, BlastAllocationModel.PACKED_BYTES);
        assertEquals(823, BlastAllocationModel.BLOCKS_PER_BLAST);
        assertEquals(24336, BlastAllocationModel.LOOKUPS_PER_BLAST);
        assertEquals(800L * 1024 * 1024, BlastAllocationModel.GAME_RATE_BYTES_PER_SECOND);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlastAllocationModel.youngCollectionsPerSecond(-1, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> BlastAllocationModel.youngCollectionsPerSecond(1024, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlastAllocationModel.edenBytes(0, 30));
        assertThrows(IllegalArgumentException.class,
                () -> BlastAllocationModel.edenBytes(1024, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlastAllocationModel.edenBytes(1024, 101));
        assertThrows(IllegalArgumentException.class,
                () -> BlastAllocationModel.percentOfGameRate(-1, 16));
        assertThrows(IllegalArgumentException.class,
                () -> BlastAllocationModel.percentOfGameRate(1, -16));
    }
}
