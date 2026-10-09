package net.amdfaster.mc;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStateCacheTest {

    /** A resolver that counts how often the level was actually consulted. */
    private static final class Counting implements BlockStateCache.Resolver {
        int calls;
        private final Map<Long, Integer> truth = new HashMap<>();

        /**
         * Records what the level would say about a coordinate.
         *
         * <p>Returns nothing, and that is the fix rather than a style choice: this used to
         * {@code return this.truth.put(...)}, and {@code Map.put} returns the *previous* value,
         * which is null for a key being inserted for the first time. Unboxing that null to the
         * declared {@code int} threw NullPointerException from every test that seeded the map.
         */
        void put(int x, int y, int z, int flags) {
            this.truth.put(BlockStateCache.positionKey(x, y, z), flags);
        }

        /** What the resolver holds, read straight from the map so it bypasses the cache. */
        int truthValue(int x, int y, int z) {
            Integer flags = this.truth.get(BlockStateCache.positionKey(x, y, z));
            return flags == null ? 0 : flags;
        }

        @Override
        public int resolve(int x, int y, int z) {
            this.calls++;
            Integer flags = this.truth.get(BlockStateCache.positionKey(x, y, z));
            return flags == null ? 0 : flags;
        }
    }

    @Test
    void theOriginDoesNotReadAsAnEmptySlot() {
        // The bug this pins. The first version tagged keys with the high bit and used
        // Long.MIN_VALUE as the empty-slot sentinel. At the origin all three coordinates are zero,
        // so the tagged encoding *was* Long.MIN_VALUE: the block at world origin matched an empty
        // slot and came back with uninitialised flags, which decode as "not air, not opaque, emits
        // nothing". Its own geometry would be emitted while its neighbours' faces against it were
        // suppressed -- a hole at (0,0,0), which is exactly where a new world puts the player.
        //
        // The sentinel is zero now, and positionKey always sets bit 63, so no coordinate can reach
        // it. This test asserts the observable behaviour rather than the bit pattern, so it fails if
        // either half is undone.
        Counting resolver = new Counting();
        resolver.put(0, 0, 0, BlockStateCache.pack(false, true, 3));

        BlockStateCache cache = new BlockStateCache();
        int flags = cache.flags(0, 0, 0, resolver);

        assertEquals(1, resolver.calls, "the origin must be resolved, not read from an empty slot");
        assertFalse(BlockStateCache.isAir(flags), "the origin is a real block");
        assertTrue(BlockStateCache.isOpaque(flags), "the origin is opaque");
        assertEquals(3, BlockStateCache.lightEmission(flags));

        // flags() returns the packed flags, not a hit indicator -- the first version of this
        // assertion compared it against 1 and so was checking nothing about caching at all.
        assertEquals(flags, cache.flags(0, 0, 0, resolver), "the second read agrees with the first");
        assertEquals(1, resolver.calls, "and it must come from the cache the second time");
    }

    @Test
    void noCoordinateEncodesToTheEmptySentinel() {
        // Bit 63 is set on every key, and zero has it clear, so the sentinel is unreachable by
        // construction rather than by luck. Checked at the extremes of the 21-bit range as well as
        // at zero, since a masked encoding is only obviously safe if the negative end wraps where
        // it is supposed to.
        int[] probes = {0, 1, -1, 16, -16, 1048575, -1048576, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : probes) {
            for (int y : probes) {
                for (int z : probes) {
                    long key = BlockStateCache.positionKey(x, y, z);
                    assertNotEquals(0L, key, "positionKey(" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    @Test
    void distinctCoordinatesGetDistinctKeys() {
        // 21 disjoint bit fields, so distinct coordinates cannot collide. Asserted empirically over
        // a range that includes the boundaries and the wrap, because "disjoint" is the argument and
        // this is the check that would catch an off-by-one in a field width.
        Map<Long, String> seen = new HashMap<>();
        int[] probes = {0, 1, -1, 15, -16, 1048575, -1048576};
        for (int x : probes) {
            for (int y : probes) {
                for (int z : probes) {
                    long key = BlockStateCache.positionKey(x, y, z);
                    String here = x + "," + y + "," + z;
                    String before = seen.put(key, here);
                    assertTrue(before == null, here + " collides with " + before);
                }
            }
        }
    }

    @Test
    void theCacheAlwaysAnswersWhatTheResolverWould() {
        // Correctness over speed. Every coordinate in a swept volume must come back with the flags
        // the resolver holds for it, on a miss and on a hit alike, with a table far too small to
        // hold the volume -- so collisions happen constantly and every one of them has to evict
        // rather than answer with someone else's value.
        Counting resolver = new Counting();
        for (int x = -6; x < 6; x++) {
            for (int y = -6; y < 6; y++) {
                for (int z = -6; z < 6; z++) {
                    resolver.put(x, y, z, BlockStateCache.pack((x + y + z) % 7 == 0,
                            (x * y * z) % 3 != 0, Math.abs(x + y) % 16));
                }
            }
        }

        // 8 entries against 1 728 coordinates: the table cannot hold a meaningful fraction, so
        // collisions and evictions happen on essentially every lookup, which is the case that has
        // to stay correct. Kept small on purpose -- this sweeps the volume several times and
        // asserts on every voxel, and the message is built lazily so a passing assertion does not
        // pay for a string it will never use.
        BlockStateCache cache = new BlockStateCache(8);
        int checked = 0;
        for (int pass = 0; pass < 3; pass++) {
            for (int x = -6; x < 6; x++) {
                for (int y = -6; y < 6; y++) {
                    for (int z = -6; z < 6; z++) {
                        int flags = cache.flags(x, y, z, resolver);
                        int truth = resolver.truthValue(x, y, z);
                        final int cx = x;
                        final int cy = y;
                        final int cz = z;
                        assertEquals(BlockStateCache.isAir(truth), BlockStateCache.isAir(flags),
                                () -> "air at " + cx + "," + cy + "," + cz);
                        assertEquals(BlockStateCache.isOpaque(truth), BlockStateCache.isOpaque(flags),
                                () -> "opaque at " + cx + "," + cy + "," + cz);
                        assertEquals(BlockStateCache.lightEmission(truth),
                                BlockStateCache.lightEmission(flags),
                                () -> "emission at " + cx + "," + cy + "," + cz);
                        checked++;
                    }
                }
            }
        }
        assertEquals(3 * 12 * 12 * 12, checked, "the whole volume was swept three times");
        assertTrue(resolver.calls > cache.entries(),
                "the table is smaller than the volume, so misses must have happened");
    }

    @Test
    void aSecondPassIsServedFromTheCache() {
        // The whole point: reading a section asks for the same block six times, once per
        // orientation, and separately asks whether each neighbour is opaque. Only the first of those
        // should reach the level.
        Counting resolver = new Counting();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    resolver.put(x, y, z, BlockStateCache.pack(false, true, 0));
                }
            }
        }

        BlockStateCache cache = new BlockStateCache();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    cache.flags(x, y, z, resolver);
                }
            }
        }
        int afterFirstPass = resolver.calls;
        assertEquals(16 * 16 * 16, afterFirstPass, "the first pass resolves everything once");

        // Six orientations per voxel, the pattern meshing actually uses.
        for (int orientation = 0; orientation < 6; orientation++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        cache.flags(x, y, z, resolver);
                    }
                }
            }
        }

        // Not "zero resolutions": the table is direct-mapped, so 4096 coordinates in 4096 slots
        // cannot all survive -- roughly 1/e of them do, and every eviction is a legitimate
        // re-resolve. What has to hold is that the six repeat passes are overwhelmingly served from
        // the cache rather than from the level, which is the entire reason this class exists.
        int repeatCalls = resolver.calls - afterFirstPass;
        int repeatLookups = 6 * 16 * 16 * 16;
        // The threshold comes from modelling this table against this sweep: at the default size a
        // 16x16x16 section answers about 93% of its repeat lookups from cache, so a quarter is a
        // generous bound that still fails loudly if the table is sized back down to the working
        // set -- at 4096 entries the same sweep hits only 23% and this assertion rejects it.
        assertTrue(repeatCalls < repeatLookups / 4,
                "the six orientation passes resolved " + repeatCalls + " of " + repeatLookups
                        + " lookups at the level; the cache is not doing its job");
        assertEquals(repeatLookups, cache.hits() + repeatCalls,
                "every repeat lookup is either a hit or a resolve, never both and never neither");
        assertTrue(cache.hits() > cache.misses(), "hits must dominate over a section's lifetime");
    }

    @Test
    void clearForcesReResolution() {
        // Staleness is the only way this cache can be wrong, so invalidation has to actually drop
        // the values and not just the keys: a key cleared but a value left behind would answer a
        // re-resolved coordinate from the previous world.
        Counting resolver = new Counting();
        resolver.put(4, 4, 4, BlockStateCache.pack(false, true, 15));
        BlockStateCache cache = new BlockStateCache();

        assertTrue(BlockStateCache.isOpaque(cache.flags(4, 4, 4, resolver)));
        assertEquals(1, resolver.calls);
        cache.flags(4, 4, 4, resolver);
        assertEquals(1, resolver.calls, "cached");

        // The world changed under us.
        resolver.put(4, 4, 4, BlockStateCache.pack(true, false, 0));
        cache.clear();

        int flags = cache.flags(4, 4, 4, resolver);
        assertEquals(2, resolver.calls, "clear must force a re-resolve");
        assertTrue(BlockStateCache.isAir(flags), "and pick up the new value");
        assertFalse(BlockStateCache.isOpaque(flags));
        assertEquals(0, cache.hits(), "clear resets the counters");
    }

    @Test
    void theTableIsRoundedUpToAPowerOfTwo() {
        // The index is a mask, not a remainder: integer modulo costs about as much as a
        // double-precision operation on RDNA, and this runs once per block per orientation.
        assertTrue(BlockStateCache.DEFAULT_ENTRIES >= 4 * 16 * 16 * 16,
                "the table must be several times a section, or it evicts against itself");
        assertEquals(64, new BlockStateCache(33).entries());
        assertEquals(64, new BlockStateCache(64).entries());
        assertEquals(128, new BlockStateCache(65).entries());
        assertEquals(1, new BlockStateCache(1).entries());
        assertEquals(BlockStateCache.DEFAULT_ENTRIES, new BlockStateCache().entries());
        assertTrue((BlockStateCache.DEFAULT_ENTRIES & (BlockStateCache.DEFAULT_ENTRIES - 1)) == 0);
    }

    @Test
    void thePackedFlagsRoundTrip() {
        for (int emission = 0; emission <= 15; emission++) {
            for (int bits = 0; bits < 4; bits++) {
                boolean air = (bits & 1) != 0;
                boolean opaque = (bits & 2) != 0;
                int flags = BlockStateCache.pack(air, opaque, emission);
                assertEquals(air, BlockStateCache.isAir(flags), "air=" + air + " opaque=" + opaque);
                assertEquals(opaque, BlockStateCache.isOpaque(flags),
                        "air=" + air + " opaque=" + opaque);
                assertEquals(emission, BlockStateCache.lightEmission(flags));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> BlockStateCache.pack(false, false, 16));
        assertThrows(IllegalArgumentException.class, () -> BlockStateCache.pack(false, false, -1));
    }

    @Test
    void airAndNotOpaqueAreNotTheSameThing() {
        // Air, and "solid but transparent to light", must stay distinguishable. Collapsing them
        // would make a block that is neither read as air and vanish from the mesh -- a slab or a
        // stair, which is exactly the geometry a player notices missing.
        int air = BlockStateCache.pack(true, false, 0);
        int seeThrough = BlockStateCache.pack(false, false, 0);

        assertTrue(BlockStateCache.isAir(air));
        assertFalse(BlockStateCache.isOpaque(air));
        assertFalse(BlockStateCache.isAir(seeThrough), "a slab is not air");
        assertFalse(BlockStateCache.isOpaque(seeThrough), "but it does not hide a neighbour's face");
        assertNotEquals(air, seeThrough, "and the two must not pack to the same value");
    }
}
