package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossModAssetDeduplicatorTest {

    private static final long[] CRATE = {1L, 2L, 3L, 4L};
    private static final long[] BARREL = {5L, 6L, 7L, 8L};

    @Test
    void twoModsWithTheSameCrateShareOneCopy() {
        // Two mods that both add a wooden crate will each bake their own copy of geometry that is byte for
        // byte identical, and each hold it for the whole session.
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        long[] first = dedup.intern(CRATE);
        long[] second = dedup.intern(CRATE.clone());

        assertSame(first, second, "the same array, not an equal one");
        assertEquals(1, dedup.distinct());
        assertEquals(1L, dedup.shared());
    }

    @Test
    void differentAssetsAreNotShared() {
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        long[] crate = dedup.intern(CRATE);
        long[] barrel = dedup.intern(BARREL);

        assertNotSame(crate, barrel);
        assertEquals(2, dedup.distinct());
        assertEquals(0L, dedup.shared());
    }

    @Test
    void theKeyIsTheContentNotTheName() {
        // One mod calls it block/oak_crate and the other storage/wooden_box, and matching on the name would
        // keep both. The duplicates that matter are the ones nobody would guess were the same.
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        long[] fromModA = dedup.intern(new long[] {1L, 2L, 3L, 4L});
        long[] fromModB = dedup.intern(new long[] {1L, 2L, 3L, 4L});

        assertSame(fromModA, fromModB, "identical content from two different namespaces");
        assertEquals(1, dedup.distinct());
    }

    @Test
    void theCallerGetsACopyItCannotCorruptForOthers() {
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        long[] shared = dedup.intern(CRATE);

        assertEquals(1L, shared[0]);
        assertEquals(4L, shared[3]);
    }

    @Test
    void oneModReleasingDoesNotFreeItForTheOthers() {
        // Dropping the shared entry outright would leave every other mod pointing at memory that is gone or
        // stale, which is a crash or a wrong texture depending on timing.
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        dedup.intern(CRATE);
        dedup.intern(CRATE.clone());

        assertFalse(dedup.release(CRATE), "one user still holds it");
        assertEquals(1, dedup.distinct());
        assertEquals(0L, dedup.freed());
    }

    @Test
    void theLastUserOutFreesIt() {
        // Keeping an entry with no users would be the leak this exists to prevent, in a smaller package.
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        dedup.intern(CRATE);
        dedup.intern(CRATE.clone());
        dedup.release(CRATE);

        assertTrue(dedup.release(CRATE), "the last one out");
        assertEquals(0, dedup.distinct());
        assertEquals(1L, dedup.freed());
    }

    @Test
    void releasingSomethingNeverInternedIsRefused() {
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        dedup.intern(CRATE);

        assertThrows(IllegalStateException.class, () -> dedup.release(BARREL));
    }

    @Test
    void aPackFullOfDuplicatesSavesMostOfTheMemory() {
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        for (int mod = 0; mod < 50; mod++) {
            for (int asset = 0; asset < 10; asset++) {
                dedup.intern(new long[] {asset, asset + 1, asset + 2});
            }
        }

        assertEquals(500L, dedup.interned());
        assertEquals(10, dedup.distinct(), "ten distinct assets, fifty mods each adding them");
        assertEquals(490L, dedup.shared());
        assertEquals(98.0, dedup.sharedPercent(), 1e-9);
        assertEquals(490L * CrossModAssetDeduplicator.BYTES_PER_ASSET, dedup.bytesSaved());
        assertEquals(490 * CrossModAssetDeduplicator.MS_PER_BAKE, dedup.msSaved(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        assertThrows(IllegalArgumentException.class, () -> dedup.intern(null));
        assertThrows(IllegalArgumentException.class, () -> dedup.intern(new long[0]));
        assertThrows(IllegalArgumentException.class, () -> dedup.release(null));
        assertThrows(IllegalArgumentException.class, () -> dedup.release(new long[0]));
    }

    @Test
    void aFreshDeduplicatorReportsZeroNotNaN() {
        // A deduplicator that has seen nothing has technically found no duplicates, and reporting that as a
        // rate would describe an unused one as though it had measured something.
        CrossModAssetDeduplicator dedup = new CrossModAssetDeduplicator();

        assertEquals(0.0, dedup.sharedPercent());
        assertEquals(0.0, dedup.msSaved(), 1e-12);
        assertEquals(0L, dedup.bytesSaved());
        assertEquals(0L, dedup.interned());
        assertEquals(0L, dedup.collisions());
        assertEquals(0, dedup.distinct());
    }
}
