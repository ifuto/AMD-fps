package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyHeightmapTest {

    @Test
    void aNewColumnIsOpenAllTheWayDown() {
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        assertEquals(16, map.columnsX(), "L15");
        assertEquals(16, map.columnsZ(), "L16");
        assertEquals(SkyHeightmap.EMPTY, map.heightAt(0, 0), "L17");
        assertTrue(map.isFullyOpenColumn(0, 0), "L18");
        assertTrue(map.isLit(0, -64, 0), "nothing blocks, so even the world floor is lit");
    }

    @Test
    void raisingRecordsTheHighestBlockSeen() {
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        assertTrue(map.raise(3, 70, 4), "L25");
        assertEquals(70, map.heightAt(3, 4), "L26");
        assertFalse(map.isLit(3, 70, 4), "the block itself is not above itself");
        assertTrue(map.isLit(3, 71, 4), "L28");
        assertFalse(map.isLit(3, 69, 4), "and everything below it is shadowed");

        assertFalse(map.raise(3, 60, 4), "a lower block does not change the height");
        assertEquals(70, map.heightAt(3, 4), "L32");
        assertTrue(map.raise(3, 90, 4), "a higher one does");
        assertEquals(90, map.heightAt(3, 4), "L34");
    }

    @Test
    void columnsAreIndependent() {
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        map.raise(0, 70, 0);
        map.raise(15, 71, 15);

        assertEquals(70, map.heightAt(0, 0), "L43");
        assertEquals(71, map.heightAt(15, 15), "L44");
        assertEquals(SkyHeightmap.EMPTY, map.heightAt(15, 0), "the untouched corner is still open");
        assertTrue(map.isFullyOpenColumn(8, 8), "L46");
    }

    @Test
    void loweringScansTheColumnAndThatIsTheExpensiveDirection() {
        // Placing a block is a comparison. Removing one cannot be, because the new top could be
        // anywhere below, so the column has to be scanned. Counting the scans makes the asymmetry
        // measurable instead of assumed -- and it is why breaking a block near the surface is a heavier
        // light update than placing one.
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        map.raise(2, 70, 2);
        map.raise(2, 68, 2);

        boolean[] blockers = new boolean[128];
        blockers[68] = true;
        blockers[64] = true;

        assertTrue(map.lower(2, 70, 2, (x, y, z) -> y >= 0 && y < 128 && blockers[y]),
                "removing the topmost blocker lowers the column");
        assertEquals(68, map.heightAt(2, 2), "the next blocker down becomes the new top");
        assertEquals(1, map.rescannedColumns(), "L65");

        assertTrue(map.lower(2, 68, 2, (x, y, z) -> y >= 0 && y < 128 && blockers[y]), "L67");
        assertEquals(64, map.heightAt(2, 2), "L68");
        assertEquals(2, map.rescannedColumns(), "L69");
    }

    @Test
    void removingANonTopBlockChangesNothing() {
        // Below the recorded top, so it was already shadowed and no scan is needed. This is the common
        // case -- most blocks a player breaks are underground.
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        map.raise(2, 70, 2);
        assertFalse(map.lower(2, 60, 2, (x, y, z) -> true), "L78");
        assertEquals(70, map.heightAt(2, 2), "L79");
        assertEquals(0, map.rescannedColumns(), "and it did not scan to find that out");
    }

    @Test
    void removingTheLastBlockEmptiesTheColumn() {
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        map.raise(5, 66, 5);
        assertTrue(map.lower(5, 66, 5, null), "L87");
        assertEquals(SkyHeightmap.EMPTY, map.heightAt(5, 5), "L88");
        assertTrue(map.isFullyOpenColumn(5, 5), "L89");
        assertTrue(map.isLit(5, 0, 5), "L90");
    }

    @Test
    void seedingWritesTheOpenColumnWithoutPropagation() {
        // The whole point. Everything above the height is 15 by definition, so it can be written
        // directly instead of being discovered one cell at a time by a flood fill that walks the entire
        // open sky of the loaded world.
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        LightField field = LightField.forSection(0, 4, 0);
        map.raise(7, 68, 7);

        int written = map.seedColumn(field, 7, 7, 79, 64);
        assertEquals(11, written, "y=79 down to y=69, stopping above the block at 68");
        assertEquals(11, map.seededCells(), "L104");
        assertEquals(LightEngine.MAX_LEVEL, field.get(7, 79, 7), "L105");
        assertEquals(LightEngine.MAX_LEVEL, field.get(7, 69, 7), "L106");
        assertEquals(0, field.get(7, 68, 7), "the blocking block itself is not seeded");
        assertEquals(0, field.get(7, 64, 7), "L108");
    }

    @Test
    void seedingAnAlreadyLitColumnWritesNothingAndReportsIt() {
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        LightField field = LightField.forSection(0, 4, 0);
        field.fill(LightEngine.MAX_LEVEL);

        assertEquals(0, map.seedColumn(field, 1, 1, 79, 64), "every cell already held 15");
        assertEquals(0, map.seededCells(), "L118");
        assertEquals(LightEngine.MAX_LEVEL, field.get(1, 70, 1), "and the field is unchanged");
    }

    @Test
    void outOfRangeColumnsAreRefusedRatherThanWrapping() {
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        assertEquals(SkyHeightmap.EMPTY, map.heightAt(-1, 0), "L125");
        assertEquals(SkyHeightmap.EMPTY, map.heightAt(16, 0), "L126");
        assertFalse(map.raise(-1, 70, 0), "L127");
        assertFalse(map.lower(16, 70, 0, null), "L128");
        assertThrows(IllegalArgumentException.class, () -> new SkyHeightmap(0, 0, 0, 16));
    }

    @Test
    void clearReopensEveryColumn() {
        SkyHeightmap map = SkyHeightmap.forSection(0, 0);
        map.raise(1, 70, 1);
        LightField field = LightField.forSection(0, 4, 0);
        map.seedColumn(field, 1, 1, 79, 70);
        assertTrue(map.seededCells() > 0, "L138");

        map.clear();
        assertEquals(SkyHeightmap.EMPTY, map.heightAt(1, 1), "L141");
        assertEquals(0, map.seededCells(), "L142");
        assertEquals(0, map.rescannedColumns(), "L143");
    }
}
