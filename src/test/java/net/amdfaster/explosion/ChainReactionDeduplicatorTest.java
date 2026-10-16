package net.amdfaster.explosion;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainReactionDeduplicatorTest {

    private static final int STONE = ExplosionBlockCache.pack(10, 64, 20);
    private static final float STONE_RESISTANCE = 6.0f;

    @Test
    void theSecondBlastReusesWhatTheFirstResolved() {
        // Adjacent blasts overlap heavily and each one walks its own rays through blocks its neighbours just
        // walked.
        ChainReactionDeduplicator chain = new ChainReactionDeduplicator();
        AtomicInteger resolutions = new AtomicInteger();

        chain.beginBlast();
        assertEquals(STONE_RESISTANCE, chain.resistanceAt(STONE, p -> {
            resolutions.incrementAndGet();
            return STONE_RESISTANCE;
        }), 1e-6f);

        chain.beginBlast();
        chain.resistanceAt(STONE, p -> {
            resolutions.incrementAndGet();
            return STONE_RESISTANCE;
        });

        assertEquals(1, resolutions.get(), "resolved once for the whole chain");
        assertEquals(1L, chain.resolved());
    }

    @Test
    void aDestroyedBlockIsResolvedAgain() {
        // The position is air now, and a later blast asking about it has to be told that rather than being
        // given the resistance of a block that is no longer there. Keeping the stale value would let an
        // explosion push through a wall that is no longer there.
        ChainReactionDeduplicator chain = new ChainReactionDeduplicator();
        AtomicInteger resolutions = new AtomicInteger();

        chain.resistanceAt(STONE, p -> { resolutions.incrementAndGet(); return STONE_RESISTANCE; });
        chain.destroy(STONE);
        chain.resistanceAt(STONE, p -> { resolutions.incrementAndGet(); return 0.0; });

        assertEquals(2, resolutions.get());
        assertEquals(1L, chain.destroyed());
        assertEquals(0, chain.size());
    }

    @Test
    void destroyingSomethingNotCachedIsNotAnError() {
        ChainReactionDeduplicator chain = new ChainReactionDeduplicator();

        chain.destroy(STONE);

        assertEquals(0L, chain.destroyed());
    }

    @Test
    void theChainIsDroppedAtTheEndOfTheTick() {
        // Carrying it into the next tick would serve values from a world that has since changed -- blocks
        // placed, water flowed, pistons moved.
        ChainReactionDeduplicator chain = new ChainReactionDeduplicator();
        AtomicInteger resolutions = new AtomicInteger();

        chain.resistanceAt(STONE, p -> { resolutions.incrementAndGet(); return STONE_RESISTANCE; });
        chain.endTick();
        chain.resistanceAt(STONE, p -> { resolutions.incrementAndGet(); return STONE_RESISTANCE; });

        assertEquals(2, resolutions.get(), "resolved again in the new tick");
        assertEquals(0, chain.size() - 1);
    }

    @Test
    void aHundredTntResolveBarelyMoreThanOne() {
        // A hundred TNT detonating in the same tick resolves the same volume over and over, and the number of
        // distinct blocks barely grows past the first blast's.
        ChainReactionDeduplicator chain = new ChainReactionDeduplicator();
        int distinct = 500;

        for (int blast = 0; blast < 100; blast++) {
            chain.beginBlast();
            for (int block = 0; block < ChainReactionDeduplicator.LOOKUPS_PER_BLAST / 60; block++) {
                int packed = block % distinct;
                chain.resistanceAt(packed, p -> 6.0);
            }
        }

        assertEquals(100L, chain.blasts());
        assertEquals(distinct, chain.size(), "the distinct set, not a hundred times it");
        assertTrue(chain.hitPercent() > 95.0);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ChainReactionDeduplicator chain = new ChainReactionDeduplicator();

        assertThrows(NullPointerException.class, () -> chain.resistanceAt(STONE, null));
    }

    @Test
    void aFreshChainReportsZeroNotNaN() {
        // A deduplicator that has seen nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        ChainReactionDeduplicator chain = new ChainReactionDeduplicator();

        assertEquals(0.0, chain.hitPercent());
        assertEquals(0.0, chain.savedPercent());
        assertEquals(0.0, chain.msResolved(), 1e-12);
        assertEquals(0L, chain.blasts());
        assertEquals(0L, chain.lookups());
        assertEquals(0, chain.size());
        assertEquals(408, ChainReactionDeduplicator.BLOCKS_PER_BLAST);
        assertEquals(24_336, ChainReactionDeduplicator.LOOKUPS_PER_BLAST);
    }
}
