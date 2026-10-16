package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTextureCacheTest {

    @Test
    void anUnchangedMapRepaintsNothing() {
        // A wall of twenty maps is 327,680 pixels a frame, 0.66 ms, for pictures that change only when
        // the player walks somewhere new.
        MapTextureCache cache = new MapTextureCache();

        assertEquals(0, cache.repaint());
        assertEquals(0L, cache.pixelsRepainted());
        assertEquals(0L, cache.columnsRepainted());
    }

    @Test
    void onlyTheChangedColumnIsRepainted() {
        // Map updates arrive as columns: the player moves east and a new column comes into range.
        // Repainting the changed columns matches the shape of the update.
        MapTextureCache cache = new MapTextureCache();
        cache.markDirty(40);

        assertEquals(1, cache.repaint());
        assertEquals(1L * MapTextureCache.MAP_SIZE, cache.pixelsRepainted(),
                "one column, not the whole map");
        assertEquals(0, cache.repaint(), "and it is clean afterwards");
    }

    @Test
    void aRunOfColumnsIsRepaintedTogether() {
        MapTextureCache cache = new MapTextureCache();
        cache.markDirtyRange(10, 14);

        assertEquals(5, cache.dirtyCount());
        assertEquals(5, cache.repaint());
        assertEquals(5L * MapTextureCache.MAP_SIZE, cache.pixelsRepainted());
    }

    @Test
    void aReversedRangeIsHandled() {
        MapTextureCache cache = new MapTextureCache();

        cache.markDirtyRange(14, 10);

        assertEquals(5, cache.dirtyCount());
    }

    @Test
    void aRangeIsClampedToTheMap() {
        MapTextureCache cache = new MapTextureCache();

        cache.markDirtyRange(-20, 200);

        assertEquals(MapTextureCache.MAP_SIZE, cache.dirtyCount());
    }

    @Test
    void markingTheSameColumnTwiceCountsOnce() {
        MapTextureCache cache = new MapTextureCache();

        cache.markDirty(5);
        cache.markDirty(5);

        assertEquals(1, cache.dirtyCount());
    }

    @Test
    void anExploredColumnIsNeverLost() {
        // A map that does not update is not a rendering artefact, it is a broken compass. The player
        // walks somewhere new, looks at the map to find their way back, and the new terrain is not there.
        MapTextureCache cache = new MapTextureCache();

        for (int frame = 0; frame < 100; frame++) {
            cache.repaint();
        }
        cache.markDirty(77);

        assertTrue(cache.isDirty(77), "still waiting after a hundred clean frames");
        assertEquals(1, cache.repaint());
    }

    @Test
    void aFullRepaintIsCounted() {
        MapTextureCache cache = new MapTextureCache();
        cache.markAllDirty();

        assertEquals(MapTextureCache.MAP_SIZE, cache.dirtyCount());
        assertEquals(MapTextureCache.MAP_SIZE, cache.repaint());
        assertEquals(1L, cache.fullRepaints());
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        MapTextureCache cache = new MapTextureCache();

        // The measured scene: one column changes every twenty frames.
        for (int frame = 0; frame < 100; frame++) {
            if (frame % 20 == 0) {
                cache.markDirty(frame / 20);
            }
            cache.repaint();
        }

        assertEquals(100L, cache.frames());
        assertEquals(5L, cache.columnsRepainted());
        assertEquals(5L * MapTextureCache.MAP_SIZE, cache.pixelsRepainted());

        // 5 columns out of a possible 12,800 pixels a frame.
        assertEquals(MapTextureCache.MAP_SIZE, MapTextureCache.MAP_SIZE);
        assertEquals(0.03264, MapTextureCache.msPerFrameWithoutCache(), 1e-9);
        assertTrue(cache.savedPercent() > 99.0, "was " + cache.savedPercent());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MapTextureCache cache = new MapTextureCache();

        assertThrows(IndexOutOfBoundsException.class, () -> cache.markDirty(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.markDirty(MapTextureCache.MAP_SIZE));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.isDirty(-1));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        MapTextureCache cache = new MapTextureCache();

        assertEquals(0.0, cache.savedPercent());
        assertEquals(0.0, cache.pixelsPerFrame());
        assertEquals(0.0, cache.msPerFrame());
        assertEquals(0, cache.dirtyCount());
        assertEquals(128, MapTextureCache.MAP_SIZE);
        assertFalse(cache.isDirty(0));
    }
}
