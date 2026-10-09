package net.amdfaster.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionSnapshotTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int DIRT = 3;

    @Test
    void captureReadsTheSectionExactlyOnce() {
        // The whole argument. Meshing one section asks for 24 576 block states -- six face orientations
        // times 4096 cells -- and each of those goes coordinate to chunk to section to palette, deriving
        // the same chunk and the same section the previous call had just derived. Capturing once turns
        // all of them into array loads.
        int[] reads = {0};
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 4, 0);
        long captured = snapshot.capture((x, y, z) -> {
            reads[0]++;
            return STONE;
        });

        assertEquals(SectionSnapshot.CELL_COUNT, captured, "4096 reads, one per cell");
        assertEquals(SectionSnapshot.CELL_COUNT, reads[0], "and no more than that");
        assertEquals(0, snapshot.idReads(), "nothing has been read back from it yet");

        // Meshing can now read as much as it likes without going back to the world.
        for (int i = 0; i < 24576; i++) {
            snapshot.idAt(i & 15, 64 + ((i >> 4) & 15), (i >> 8) & 15);
        }
        assertEquals(24576, snapshot.idReads(), "all served from the snapshot");
        assertEquals(4096, snapshot.captureReads(), "and the world was read 4096 times in total");
    }

    @Test
    void aUniformSectionHoldsNoArray() {
        // Everything above the terrain is air and everything below the first cave is stone. Allocating
        // 16 KB of one repeated number for each of those sections is a waste that adds up across the
        // loaded world, and palette storage has a single-valued mode for exactly this case.
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 4, 0);
        snapshot.capture((x, y, z) -> AIR);

        assertTrue(snapshot.isUniform(), "all air");
        assertEquals(AIR, snapshot.uniformId());
        assertEquals(0, snapshot.bytesHeld(), "no array allocated");
        assertTrue(snapshot.isCaptured(), "but it is a real captured state, not an empty one");
        assertEquals(AIR, snapshot.idAt(0, 64, 0));
        assertEquals(AIR, snapshot.idAt(15, 79, 15));
        assertEquals(1, snapshot.distinctIds(), "one distinct block");
    }

    @Test
    void aMixedSectionHoldsTheArray() {
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 4, 0);
        snapshot.capture((x, y, z) -> y < 66 ? STONE : AIR);

        assertFalse(snapshot.isUniform(), "two kinds of block");
        assertEquals(SectionSnapshot.MISSING, snapshot.uniformId());
        assertEquals(16384, snapshot.bytesHeld(), "4096 ids at four bytes");
        assertEquals(STONE, snapshot.idAt(0, 65, 0));
        assertEquals(AIR, snapshot.idAt(0, 66, 0), "the boundary is exact");
        assertEquals(2, snapshot.distinctIds());
    }

    @Test
    void idsAreIndexedByCellNotByWalkOrder() {
        // The loop order and the index arithmetic have to agree, or every cell reads its neighbour's
        // block. Writing a known pattern and reading it back by coordinate is the only way to see it.
        SectionSnapshot snapshot = SectionSnapshot.forSection(2, 0, -3);
        snapshot.capture((x, y, z) -> (x * 31 + y * 7 + z * 3) & 0xFF);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int wx = 32 + x;
                    int wy = y;
                    int wz = -48 + z;
                    assertEquals((wx * 31 + wy * 7 + wz * 3) & 0xFF, snapshot.idAt(wx, wy, wz),
                            "cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void outsideTheSectionReadsAsMissing() {
        // A meshing pass reaches one block past the border to decide whether a face is hidden, so this
        // happens constantly. Reporting it as missing lets the caller substitute the neighbouring
        // section rather than reading garbage from the array.
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 4, 0);
        snapshot.capture((x, y, z) -> STONE);

        assertEquals(SectionSnapshot.MISSING, snapshot.idAt(-1, 64, 0));
        assertEquals(SectionSnapshot.MISSING, snapshot.idAt(0, 63, 0));
        assertEquals(SectionSnapshot.MISSING, snapshot.idAt(0, 80, 0));
        assertEquals(SectionSnapshot.MISSING, snapshot.idAt(16, 64, 0));
        assertTrue(snapshot.idAt(0, 64, 0) != SectionSnapshot.MISSING, "inside is fine");
    }

    @Test
    void anUncapturedSnapshotReadsAsMissing() {
        // Reading before capturing must not return whatever the arrays happened to hold. A snapshot
        // that answers confidently from uninitialised memory would mesh a section out of block zero.
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 0, 0);
        assertFalse(snapshot.isCaptured());
        assertEquals(SectionSnapshot.MISSING, snapshot.idAt(0, 0, 0));
        assertEquals(0, snapshot.bytesHeld());
        assertEquals(0, snapshot.distinctIds());
    }

    @Test
    void resolutionIsCachedForConsecutiveCellsOfTheSameBlock() {
        // A meshing walk visits cells in a line, and neighbours usually share a block id -- a wall of
        // stone, a floor of dirt. Caching the last resolution turns most of those into a field
        // comparison instead of a lookup, and the hit rate says whether it is earning its keep.
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 4, 0);
        snapshot.capture((x, y, z) -> y < 70 ? STONE : AIR);

        int[] calls = {0};
        SectionSnapshot.Resolver resolver = id -> {
            calls[0]++;
            return id == STONE ? 0xF : 0x0;
        };
        for (int y = 64; y < 70; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(0xF, snapshot.resolve(x, y, z, resolver), "stone at y=" + y);
                }
            }
        }

        // 6 * 256 = 1536 reads, and the id changes only at the very first one.
        assertEquals(1536, snapshot.resolveCacheHits() + snapshot.resolveCacheMisses(), "every read resolved");
        assertEquals(1, calls[0], "the resolver ran once for 1536 cells");
        assertEquals(1, snapshot.resolveCacheMisses(), "one miss, the first cell");
        assertEquals(1535, snapshot.resolveCacheHits());
    }

    @Test
    void aMissingPaletteEntryIsReportedRatherThanInvented() {
        // An index in the packed data can name a palette entry that is not there, and the container
        // answers with its default value rather than failing. A snapshot that stored the raw index and
        // resolved it later has to do the same, or a section that renders fine in vanilla becomes a
        // crash during meshing. This has bitten real implementations.
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 0, 0);
        snapshot.capture((x, y, z) -> (x == 0 && y == 0 && z == 0) ? SectionSnapshot.MISSING : STONE);

        assertEquals(SectionSnapshot.MISSING, snapshot.idAt(0, 0, 0), "reported, not substituted here");
        assertEquals(STONE, snapshot.idAt(1, 0, 0), "and the rest are unaffected");

        // The caller substitutes the default, which is what the container itself does.
        int resolved = snapshot.resolve(0, 0, 0, id -> id == SectionSnapshot.MISSING ? AIR : STONE);
        assertEquals(AIR, resolved, "the caller's default fills the gap");
    }

    @Test
    void recapturingReplacesThePreviousState() {
        SectionSnapshot snapshot = SectionSnapshot.forSection(0, 0, 0);
        snapshot.capture((x, y, z) -> STONE);
        assertTrue(snapshot.isUniform());

        snapshot.capture((x, y, z) -> (x + y + z) % 2 == 0 ? STONE : DIRT);
        assertFalse(snapshot.isUniform(), "the section is no longer uniform");
        assertEquals(16384, snapshot.bytesHeld(), "and now holds an array");
        assertEquals(4096, snapshot.captureReads() / 2, "two captures of 4096 each");

        snapshot.clear();
        assertFalse(snapshot.isCaptured());
        assertEquals(0, snapshot.bytesHeld());
        assertEquals(8192, snapshot.captureReads(), "clearing does not forget what was read");
    }

    @Test
    void aNegativeSectionOriginWorksLikeAnyOther() {
        SectionSnapshot snapshot = SectionSnapshot.forSection(-2, -1, -3);
        assertEquals(-32, snapshot.originX());
        assertEquals(-16, snapshot.originY());
        assertEquals(-48, snapshot.originZ());

        snapshot.capture((x, y, z) -> x + y + z);
        assertEquals(-32 - 16 - 48, snapshot.idAt(-32, -16, -48), "the origin cell");
        assertEquals(-31 - 16 - 48, snapshot.idAt(-31, -16, -48), "and its neighbour");
        assertEquals(SectionSnapshot.MISSING, snapshot.idAt(-33, -16, -48), "one block west is outside");
    }
}
