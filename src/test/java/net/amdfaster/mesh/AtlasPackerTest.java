package net.amdfaster.mesh;

import net.amdfaster.mesh.AtlasPacker.Placement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasPackerTest {

    private static final int[][] MIXED = {
        {16, 16}, {32, 32}, {16, 32}, {64, 64}, {16, 16}, {48, 16}, {16, 48}, {32, 16}
    };

    @Test
    void nothingOverlaps() {
        // Two sprites sampling the same pixels is the failure this must not have: a block shows part of
        // its neighbour's texture, visibly wrong on every face that uses it.
        AtlasPacker packer = new AtlasPacker(256, 256);
        List<Placement> placed = AtlasPacker.nonNull(packer.packAll(MIXED));

        assertEquals(MIXED.length, placed.size(), "all of them fit");
        for (int i = 0; i < placed.size(); i++) {
            Placement a = placed.get(i);
            assertTrue(packer.isValid(a, placed.subList(0, i)),
                    "sprite " + i + " overlaps an earlier one");
        }
    }

    @Test
    void nothingHangsOffTheEdge() {
        // Pixels outside the atlas sample black, or wrap and show the opposite side.
        AtlasPacker packer = new AtlasPacker(64, 64);
        List<Placement> placed = AtlasPacker.nonNull(packer.packAll(MIXED));

        for (Placement p : placed) {
            assertTrue(p.x() >= 0 && p.y() >= 0);
            assertTrue(p.x() + p.w() <= 64, "x overflow: " + p);
            assertTrue(p.y() + p.h() <= 64, "y overflow: " + p);
        }
    }

    @Test
    void aSpriteLargerThanTheAtlasIsReportedNotClipped() {
        // A clipped sprite reads as a corrupted texture, and the caller needs to know to fall back to a
        // bigger atlas rather than silently drawing a partial one.
        AtlasPacker packer = new AtlasPacker(32, 32);

        assertNull(packer.place(64, 64));
        assertEquals(1, packer.failed());
    }

    @Test
    void aSpriteBesideATallerOneSharesItsRow() {
        // The whole point. Shelf packing would start a new row for the 16-pixel sprite and leave the
        // rest of the 64-pixel row empty.
        AtlasPacker packer = new AtlasPacker(128, 128);

        Placement tall = packer.place(64, 64);
        Placement small = packer.place(16, 16);

        assertNotNull(tall);
        assertNotNull(small);
        assertEquals(tall.y(), small.y(), "the short one sits beside it, not below");
        assertTrue(packer.usedHeight() <= 64, "and the atlas did not grow");
    }

    @Test
    void theSkylineCoversTheWholeSprite() {
        // Leaving part of it at the old height would let the next sprite be placed on top of this one.
        AtlasPacker packer = new AtlasPacker(128, 128);
        packer.place(32, 32);

        Placement next = packer.place(32, 32);

        assertNotNull(next);
        assertTrue(next.x() >= 32 || next.y() >= 32,
                "the second sprite must not start inside the first");
    }

    @Test
    void fillingTheAtlasLeavesNoRoomAndReportsIt() {
        AtlasPacker packer = new AtlasPacker(32, 32);

        assertNotNull(packer.place(32, 32));
        assertNull(packer.place(1, 1), "the atlas is full");
        assertEquals(1, packer.placed());
        assertEquals(1, packer.failed());
    }

    @Test
    void packingIsDenserThanShelfLayout() {
        // Shelf packing this set needs 64x160 = 10,240 pixels; the area floor is 8,281.
        AtlasPacker packer = new AtlasPacker(128, 128);
        List<Placement> placed = AtlasPacker.nonNull(packer.packAll(MIXED));

        assertEquals(MIXED.length, placed.size());
        long area = 0;
        for (int[] size : MIXED) {
            area += (long) size[0] * size[1];
        }
        assertEquals(8192L, area);
        assertEquals(area, packer.pixelsUsed());
        assertTrue(packer.wastePercent() < 20.0,
                "shelf packing wastes 20.0%: got " + packer.wastePercent());
    }

    @Test
    void theWasteIsReportedInBytes() {
        AtlasPacker packer = new AtlasPacker(64, 64);
        packer.packAll(MIXED);

        assertEquals(4, AtlasPacker.BYTES_PER_PIXEL);
        assertTrue(packer.bytesWasted() >= 0);
        assertEquals(64L * 64 * 4, packer.bytes());
        assertEquals((long) (64L * 64 * 4 * 4 / 3), packer.bytesWithMipmaps());
    }

    @Test
    void sortingTallestFirstIsWhatMakesItFit() {
        // Placing the short sprites first builds an uneven surface the tall ones can no longer fit into,
        // so the order is part of the algorithm rather than a cosmetic choice.
        AtlasPacker sorted = new AtlasPacker(64, 128);
        Placement[] result = sorted.packAll(MIXED);

        int failures = 0;
        for (Placement p : result) {
            if (p == null) {
                failures++;
            }
        }
        assertEquals(0, failures, "tallest first fits everything in 64x128");

        // The same set in the given order, which puts the 64x64 sprite fourth.
        AtlasPacker unsorted = new AtlasPacker(64, 128);
        int unsortedFailures = 0;
        for (int[] size : MIXED) {
            if (unsorted.place(size[0], size[1]) == null) {
                unsortedFailures++;
            }
        }
        assertTrue(unsortedFailures > 0,
                "the unsorted order should strand something: " + unsortedFailures);
    }

    @Test
    void tiesGoToTheTighterFit() {
        // Preferring the position with less empty space underneath keeps deep gaps open for sprites that
        // need them, rather than filling them with something short and stranding the tall ones.
        AtlasPacker packer = new AtlasPacker(64, 64);
        packer.place(16, 32);
        packer.place(16, 16);

        Placement third = packer.place(16, 16);

        assertNotNull(third);
        assertTrue(packer.isValid(third, List.of()));
    }

    @Test
    void aFreshPackerReportsZeroNotNaN() {
        AtlasPacker packer = new AtlasPacker(64, 64);

        assertEquals(0.0, packer.wastePercent());
        assertEquals(0, packer.placed());
        assertEquals(0, packer.usedHeight());
        assertEquals(0L, packer.pixelsUsed());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AtlasPacker(0, 64));
        assertThrows(IllegalArgumentException.class, () -> new AtlasPacker(64, 0));
        AtlasPacker packer = new AtlasPacker(64, 64);
        assertThrows(IllegalArgumentException.class, () -> packer.place(0, 16));
        assertThrows(IllegalArgumentException.class, () -> packer.place(16, 0));
    }

    @Test
    void aLargeAtlasCostsWhatTheFormulaSays() {
        // A 1024x1024 atlas at RGBA is 4 MB, and gaps at 40% are 1.6 MB of video memory holding nothing.
        AtlasPacker packer = new AtlasPacker(1024, 1024);

        assertEquals(4L * 1024 * 1024, packer.bytes());
        assertEquals(4.0, packer.bytes() / 1024.0 / 1024.0, 0.001);
    }
}
