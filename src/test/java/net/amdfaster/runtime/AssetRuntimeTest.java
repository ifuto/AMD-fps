package net.amdfaster.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetRuntimeTest {

    @Test
    void onlyWorldSpecificCachesAreRegistered() {
        // Models and textures come from resource packs rather than from the world, so clearing them on a
        // world change would drop memory that mods are still pointing at.
        AssetRuntime runtime = new AssetRuntime();

        assertEquals(1, runtime.registeredCaches(), "only the NBT cache is world-specific");
    }

    @Test
    void aWorldChangeClearsTheNbtCache() {
        AssetRuntime runtime = new AssetRuntime();

        runtime.nbt().intern("minecraft:chest");
        assertEquals(1, runtime.nbt().size());

        assertEquals(1, runtime.onWorldChange());
        assertEquals(0, runtime.nbt().size());
        assertTrue(runtime.clearFailures().isEmpty());
    }

    @Test
    void aWorldChangeKeepsTheSharedAssets() {
        // They are still valid and still referenced after a world change, so dropping them would force every
        // model to be baked again for no reason.
        AssetRuntime runtime = new AssetRuntime();

        long[] asset = {1L, 2L, 3L};
        runtime.assets().intern(asset);
        assertEquals(1, runtime.assets().distinct());

        runtime.onWorldChange();

        assertEquals(1, runtime.assets().distinct(), "the assets survived the world change");
    }

    @Test
    void twoModsWithTheSameAssetShareOneCopy() {
        AssetRuntime runtime = new AssetRuntime();

        long[] first = runtime.assets().intern(new long[] {1L, 2L, 3L});
        long[] second = runtime.assets().intern(new long[] {1L, 2L, 3L});

        assertSame(first, second);
        assertEquals(1, runtime.assets().distinct());
        assertEquals(1L, runtime.assets().shared());
    }

    @Test
    void aCacheCanRegisterItself() {
        // Registered rather than cleared by name, so a cache added later is cleared by registering itself
        // rather than by someone remembering to add it to a list somewhere else.
        AssetRuntime runtime = new AssetRuntime();
        int[] cleared = {0};

        runtime.registry().register(new net.amdfaster.mem.WorldChangeRegistry.Clearable() {
            @Override
            public String cacheName() {
                return "testCache";
            }

            @Override
            public void clearForWorldChange() {
                cleared[0]++;
            }
        });

        assertEquals(2, runtime.registeredCaches());
        assertEquals(2, runtime.onWorldChange());
        assertEquals(1, cleared[0]);
    }

    @Test
    void aFailingCacheDoesNotStopTheOthers() {
        // Leaving them holding the old world is exactly the failure the clearing exists to prevent.
        AssetRuntime runtime = new AssetRuntime();
        int[] cleared = {0};

        runtime.registry().register(new net.amdfaster.mem.WorldChangeRegistry.Clearable() {
            @Override
            public String cacheName() {
                return "broken";
            }

            @Override
            public void clearForWorldChange() {
                throw new IllegalStateException("this cache will not clear");
            }
        });
        runtime.registry().register(new net.amdfaster.mem.WorldChangeRegistry.Clearable() {
            @Override
            public String cacheName() {
                return "working";
            }

            @Override
            public void clearForWorldChange() {
                cleared[0]++;
            }
        });

        assertEquals(2, runtime.onWorldChange(), "the working one still cleared");
        assertEquals(1, cleared[0]);
        assertEquals(1, runtime.clearFailures().size());
        assertTrue(runtime.clearFailures().get(0).startsWith("broken:"),
                "and the failure names the cache");
    }

    @Test
    void theHeapIsSampledRatherThanGuessed() {
        AssetRuntime runtime = new AssetRuntime();

        assertFalse(runtime.sampleHeap(1024L * 1024, 4L * 1024 * 1024 * 1024),
                "a quarter of the heap is not a warning");
        assertTrue(runtime.sampleHeap(3_900L * 1024 * 1024, 4L * 1024 * 1024 * 1024),
                "and nearly all of it is");
    }

    @Test
    void theSavingIsCountedAcrossBothCaches() {
        AssetRuntime runtime = new AssetRuntime();

        for (int i = 0; i < 20; i++) {
            runtime.assets().intern(new long[] {1L, 2L, 3L});
        }
        for (int i = 0; i < 20; i++) {
            runtime.nbt().intern("minecraft:chest");
        }

        assertTrue(runtime.bytesSaved() > 0);
        assertTrue(runtime.sharedPercent() > 90.0,
                "nineteen of twenty assets were duplicates: " + runtime.sharedPercent());
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has seen nothing has technically found no duplicates, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        AssetRuntime runtime = new AssetRuntime();

        assertEquals(0.0, runtime.sharedPercent());
        assertEquals(0.0, runtime.msSaved(), 1e-12);
        assertEquals(0L, runtime.bytesSaved());
        assertEquals(0, runtime.assets().distinct());
        assertEquals(0, runtime.nbt().size());
        assertTrue(runtime.clearFailures().isEmpty());
    }
}
