package net.amdfaster.redstone;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerCacheTest {

    /** A world of signal strengths that counts how often it is asked. */
    private static final class World implements PowerCache.PowerSource {
        final Map<Integer, Integer> signals = new HashMap<>();
        int calls;

        void set(int x, int y, int z, int signal) {
            this.signals.put(PowerCache.pack(x, y, z), signal);
        }

        @Override
        public int signalAt(int x, int y, int z) {
            this.calls++;
            return this.signals.getOrDefault(PowerCache.pack(x, y, z), 0);
        }
    }

    @Test
    void anUnpoweredPositionIsCachedLikeAnyOther() {
        // The case that a zero-as-empty-marker would silently break. Zero is the most common signal
        // strength there is, and treating it as absent would re-read every unpowered block on every
        // query, which is most of them, and the cache would look like it was working.
        World world = new World();
        PowerCache cache = new PowerCache();

        assertEquals(0, cache.signalAt(world, 1, 64, 1));
        assertEquals(1, world.calls);
        assertEquals(0, cache.signalAt(world, 1, 64, 1));
        assertEquals(1, world.calls, "the second read came from the cache");
        assertEquals(1, cache.size());
        assertEquals(1L, cache.hits());
    }

    @Test
    void recordPowerUpdatesWithoutReReadingTheWorld() {
        // The caller already holds the value it just computed. Dropping the entry instead would be
        // correct and would cost a world lookup for something sitting in a local variable.
        World world = new World();
        world.set(2, 64, 2, 9);
        PowerCache cache = new PowerCache();

        assertEquals(9, cache.signalAt(world, 2, 64, 2));
        assertEquals(1, world.calls);

        cache.recordPower(2, 64, 2, 3);
        assertEquals(3, cache.signalAt(world, 2, 64, 2));
        assertEquals(1, world.calls, "still one read; the new value came from the recording");
        assertEquals(1L, cache.recordings());
    }

    @Test
    void aRecordedValueForAnUnknownPositionIsStoredNotDropped() {
        World world = new World();
        PowerCache cache = new PowerCache();

        cache.recordPower(5, 64, 5, 7);

        assertEquals(1, cache.size());
        assertEquals(7, cache.signalAt(world, 5, 64, 5));
        assertEquals(0, world.calls, "and the world was never asked");
    }

    @Test
    void invalidatingMakesTheNextReadAskAgain() {
        // The correctness case. A wire that holds its power after the lever is off is a door that
        // stays open, and nothing in a log points at a cache.
        World world = new World();
        world.set(0, 64, 0, 12);
        PowerCache cache = new PowerCache();

        cache.signalAt(world, 0, 64, 0);
        world.set(0, 64, 0, 0);
        assertEquals(12, cache.signalAt(world, 0, 64, 0), "still the cached value");

        cache.invalidateAt(0, 64, 0);
        assertEquals(0, cache.signalAt(world, 0, 64, 0), "and the current one afterwards");
        assertEquals(2, world.calls);
    }

    @Test
    void invalidationKeepsTheRestOfTheCircuitFindable() {
        World world = new World();
        PowerCache cache = new PowerCache();
        int[][] positions = new int[500][3];
        for (int i = 0; i < positions.length; i++) {
            positions[i][0] = i % 25 - 12;
            positions[i][1] = 64;
            positions[i][2] = i / 25 - 10;
            world.set(positions[i][0], 64, positions[i][2], i % 16);
            cache.signalAt(world, positions[i][0], 64, positions[i][2]);
        }
        assertEquals(positions.length, world.calls);

        for (int i = 0; i < positions.length; i += 3) {
            cache.invalidateAt(positions[i][0], 64, positions[i][2]);
        }
        for (int i = 0; i < positions.length; i++) {
            int before = world.calls;
            assertEquals(i % 16, cache.signalAt(world, positions[i][0], 64, positions[i][2]));
            if (i % 3 == 0) {
                assertEquals(before + 1, world.calls, "position " + i + " was invalidated");
            } else {
                assertEquals(before, world.calls, "position " + i + " was not, so backshift lost it");
            }
        }
    }

    @Test
    void aCascadeGivesTheSameAnswerCachedOrNot() {
        // A line of wire settling from a powered end. Run twice, once with the cache answering and
        // once with the world answering every time, and the resulting power levels must be identical.
        // A cache that returns a stale strength does not produce a wrong number here, it produces a
        // circuit that settles somewhere else.
        int n = 30;
        int[] withCache = settle(n, true);
        int[] without = settle(n, false);

        for (int i = 0; i < n; i++) {
            assertEquals(without[i], withCache[i], "wire " + i + " settled differently");
        }
        assertEquals(15, without[0]);
        // A line of 30 does not carry to the end. Signal falls by one per block from 15, so it runs
        // out at fifteen blocks and the rest of the line stays at zero. Asserting that the far end
        // was powered would have been asserting something redstone does not do.
        assertEquals(1, without[14], "fifteen blocks out, one strength left");
        assertEquals(0, without[15], "and it stops there");
        assertEquals(0, without[n - 1]);
    }

    private static int[] settle(int n, boolean useCache) {
        int[] power = new int[n];
        power[0] = 15;
        World world = new World();
        PowerCache cache = new PowerCache();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                for (int d = -1; d <= 1; d += 2) {
                    int j = i + d;
                    if (j < 0 || j >= n) {
                        continue;
                    }
                    int neighbour;
                    if (useCache) {
                        world.set(j, 64, 0, power[j]);
                        cache.invalidateAt(j, 64, 0);
                        neighbour = cache.signalAt(world, j, 64, 0);
                    } else {
                        neighbour = power[j];
                    }
                    if (neighbour - 1 > power[i]) {
                        power[i] = neighbour - 1;
                        changed = true;
                    }
                }
            }
        }
        return power;
    }

    @Test
    void vanillaRedundancyIsReproduced() {
        // The figures in the class comment, counted rather than quoted. A line re-reads each position
        // about four times; a sheet about seven and a half.
        int[] line = countLine(30);
        assertEquals(116, line[0], "lookups for a 30 long line");
        assertEquals(30, line[1], "distinct positions");

        int[] sheet = countSheet(16, 16);
        assertEquals(1920, sheet[0], "lookups for a 16 by 16 sheet");
        assertEquals(256, sheet[1]);
        assertEquals(7.5, (double) sheet[0] / sheet[1], 0.01);
    }

    private static int[] countLine(int n) {
        int[] power = new int[n];
        power[0] = 15;
        int lookups = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                for (int d = -1; d <= 1; d += 2) {
                    int j = i + d;
                    if (j < 0 || j >= n) {
                        continue;
                    }
                    lookups++;
                    if (power[j] - 1 > power[i]) {
                        power[i] = power[j] - 1;
                        changed = true;
                    }
                }
            }
        }
        return new int[] {lookups, n};
    }

    private static int[] countSheet(int w, int h) {
        int[][] grid = new int[h][w];
        grid[0][0] = 15;
        int lookups = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] delta : deltas) {
                        int nx = x + delta[0];
                        int ny = y + delta[1];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                            continue;
                        }
                        lookups++;
                        if (grid[ny][nx] - 1 > grid[y][x]) {
                            grid[y][x] = grid[ny][nx] - 1;
                            changed = true;
                        }
                    }
                }
            }
        }
        return new int[] {lookups, w * h};
    }

    @Test
    void aSignalOutsideZeroToFifteenIsRejected() {
        // ABSENT is 16, so a signal of 16 or more would be indistinguishable from an empty slot and
        // the cache would treat a powered position as never having been read.
        World world = new World();
        PowerCache cache = new PowerCache();

        assertThrows(IllegalArgumentException.class, () -> cache.recordPower(0, 64, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> cache.recordPower(0, 64, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> cache.signalAt(
                (x, y, z) -> 99, 0, 64, 0));
    }

    @Test
    void positionsInsideACircuitDoNotPackAlike() {
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                for (int y = 60; y <= 68; y++) {
                    keys.add(PowerCache.pack(x, y, z));
                }
            }
        }
        assertEquals(65 * 65 * 9, keys.size(), "every position in a generous circuit volume is distinct");
    }

    @Test
    void fillingPastTheCapacityGrowsRatherThanHanging() {
        // probe on a full table with a missing key walks every slot and comes back to where it
        // started. Another class in this project did exactly that and hung the thread; CI caught it
        // by timing out rather than failing.
        World world = new World();
        PowerCache cache = new PowerCache(16);

        for (int i = 0; i < 20000; i++) {
            world.set(i % 200 - 100, 64, i / 200 - 50, i % 16);
            cache.signalAt(world, i % 200 - 100, 64, i / 200 - 50);
        }

        assertEquals(20000L, cache.lookups());
        assertTrue(cache.size() > 0);
    }

    @Test
    void clearDropsEverything() {
        World world = new World();
        world.set(0, 64, 0, 5);
        PowerCache cache = new PowerCache();
        cache.signalAt(world, 0, 64, 0);
        cache.clear();

        assertEquals(0, cache.size());
        cache.signalAt(world, 0, 64, 0);
        assertEquals(2, world.calls, "both asked");
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        PowerCache cache = new PowerCache();
        assertEquals(0.0, cache.hitRatePercent());
        assertEquals(0L, cache.lookups());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PowerCache(8));
    }
}
