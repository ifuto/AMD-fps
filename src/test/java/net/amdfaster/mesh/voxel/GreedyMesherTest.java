package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyMesherTest {

    private static List<MergedFace> mesh(VoxelView view) {
        List<MergedFace> faces = new ArrayList<>();
        GreedyMesher.mesh(view, faces::add);
        return faces;
    }

    private static long count(List<MergedFace> faces, Orientation o) {
        return faces.stream().filter(f -> f.orientation() == o).count();
    }

    private static int area(List<MergedFace> faces, Orientation o) {
        return faces.stream().filter(f -> f.orientation() == o).mapToInt(MergedFace::area).sum();
    }

    @Test
    void singleBlockEmitsSixUnitFaces() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);

        List<MergedFace> faces = mesh(view);
        assertEquals(6, faces.size());
        for (MergedFace f : faces) {
            assertEquals(1, f.area(), f.toString());
        }
        for (Orientation o : Orientation.values()) {
            assertEquals(1, count(faces, o), o + " should have exactly one face");
        }
    }

    @Test
    void aSolidCubeMergesIntoSixQuads() {
        // 8 opaque blocks x 6 faces = 48, minus the 24 hidden between touching neighbours = 24
        // visible, and each side of the cube is one coplanar 2x2 run.
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(4, 4, 4, 6, 6, 6, 1, true);

        List<MergedFace> faces = mesh(view);
        assertEquals(6, faces.size(), "a 2x2x2 cube is six 2x2 quads");
        for (MergedFace f : faces) {
            assertEquals(4, f.area(), f.toString());
        }
    }

    @Test
    void interiorFacesBetweenOpaqueNeighboursAreCulled() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(4, 4, 4, 6, 6, 6, 1, true);

        List<MergedFace> faces = mesh(view);
        assertEquals(24, faces.stream().mapToInt(MergedFace::area).sum(),
                "only the 24 outer unit faces survive");
    }

    @Test
    void transparentNeighboursDoNotCullFaces() {
        // Two touching blocks where the neighbour does not hide faces: all 12 faces survive.
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, false);
        view.set(5, 4, 4, 1, false);

        List<MergedFace> faces = mesh(view);
        assertEquals(12, faces.stream().mapToInt(MergedFace::area).sum());
        // The two shared faces are still there, one per block.
        assertEquals(2, area(faces, Orientation.POS_X));
        assertEquals(2, area(faces, Orientation.NEG_X));
    }

    @Test
    void aFullFloorBecomesSixQuads() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(0, 0, 0, 16, 1, 16, 7, true);

        List<MergedFace> faces = mesh(view);
        assertEquals(6, faces.size(), "a 16x16 slab is 6 quads, not 1536");
        assertEquals(256, area(faces, Orientation.POS_Y));
        assertEquals(256, area(faces, Orientation.NEG_Y));
        assertEquals(16, area(faces, Orientation.POS_X), "the side of a one-block-tall slab is 1x16");
        assertEquals(16, area(faces, Orientation.NEG_X));
        assertEquals(16, area(faces, Orientation.POS_Z));
        assertEquals(16, area(faces, Orientation.NEG_Z));
        for (MergedFace f : faces) {
            assertEquals(7, f.key());
        }
    }

    @Test
    void aSolidSectionShellMergesToSixQuads() {
        // Every interior face is hidden by an opaque neighbour, so only the shell survives --
        // and each of its six sides is one coplanar run.
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(0, 0, 0, 16, 16, 16, 1, true);

        List<MergedFace> faces = mesh(view);
        assertEquals(6, faces.size());
        assertEquals(1536, faces.stream().mapToInt(MergedFace::area).sum());
        for (MergedFace f : faces) {
            assertEquals(256, f.area(), f.toString());
        }
    }

    @Test
    void differentKeysDoNotMerge() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        // Two side by side blocks with different keys: the shared 1x2 side cannot merge.
        view.set(4, 4, 4, 1, true);
        view.set(4, 4, 5, 2, true);

        List<MergedFace> faces = mesh(view);
        // The two faces the blocks share are hidden by the opaque neighbour, so along Z each
        // block keeps only its outer face.
        assertEquals(2, count(faces, Orientation.POS_X), "different keys must not merge");
        assertEquals(2, count(faces, Orientation.NEG_X));
        assertEquals(2, count(faces, Orientation.POS_Y));
        assertEquals(2, count(faces, Orientation.NEG_Y));
        assertEquals(1, count(faces, Orientation.POS_Z));
        assertEquals(1, count(faces, Orientation.NEG_Z));
    }

    @Test
    void sameKeyNeighboursDoMerge() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);
        view.set(4, 4, 5, 1, true);

        List<MergedFace> faces = mesh(view);
        assertEquals(6, faces.size(), "six merged quads, one per orientation");
        // Both blocks are at x=4 and y=4, so on the X sides the merge runs along Z (1x2)...
        assertEquals(1, count(faces, Orientation.POS_X));
        assertEquals(2, area(faces, Orientation.POS_X));
        assertEquals(1, count(faces, Orientation.NEG_X));
        assertEquals(2, area(faces, Orientation.NEG_X));
        // ...and on the Y sides it runs along Z as well, because v is the Z axis there.
        assertEquals(1, count(faces, Orientation.POS_Y));
        assertEquals(2, area(faces, Orientation.POS_Y));
        assertEquals(1, count(faces, Orientation.NEG_Y));
        // Along Z only the two outer faces survive; the shared one is hidden.
        assertEquals(1, count(faces, Orientation.POS_Z));
        assertEquals(1, count(faces, Orientation.NEG_Z));
    }

    @Test
    void volumeBoundaryEmitsFaces() {
        // A block hard against the volume edge: out of bounds counts as air, so its faces survive.
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(0, 0, 0, 1, true);

        List<MergedFace> faces = mesh(view);
        assertEquals(6, faces.size());
        assertTrue(faces.stream().anyMatch(f -> f.orientation() == Orientation.NEG_X && f.plane() == 0));
        assertTrue(faces.stream().anyMatch(f -> f.orientation() == Orientation.NEG_Y && f.plane() == 0));
        assertTrue(faces.stream().anyMatch(f -> f.orientation() == Orientation.NEG_Z && f.plane() == 0));
    }

    @Test
    void mergedFaceAreaAlwaysEqualsTheVisibleUnitFaceCount() {
        // The invariant greedy meshing has to hold: merging changes how many quads there are,
        // never how much surface is drawn.
        Random random = new Random(20261007L);
        for (int trial = 0; trial < 12; trial++) {
            ArrayVoxelView view = new ArrayVoxelView(8, 8, 8);
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    for (int x = 0; x < 8; x++) {
                        if (random.nextInt(100) < 35) {
                            view.set(x, y, z, 1 + random.nextInt(3), random.nextBoolean());
                        }
                    }
                }
            }

            int expected = 0;
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    for (int x = 0; x < 8; x++) {
                        if (view.key(x, y, z, Orientation.POS_X) == VoxelView.NO_GEOMETRY) {
                            continue;
                        }
                        for (Orientation o : Orientation.values()) {
                            if (!view.isOpaque(x + o.normalX(), y + o.normalY(), z + o.normalZ())) {
                                expected++;
                            }
                        }
                    }
                }
            }

            List<MergedFace> faces = mesh(view);
            int merged = faces.stream().mapToInt(MergedFace::area).sum();
            assertEquals(expected, merged, "trial " + trial + ": merged area lost or invented surface");

            for (MergedFace f : faces) {
                assertTrue(f.area() > 0, f.toString());
                assertTrue(f.uSize() > 0 && f.vSize() > 0, f.toString());
                assertNotEquals(VoxelView.NO_GEOMETRY, f.key(), f.toString());
            }
        }
    }

    @Test
    void mergingNeverIncreasesTheQuadCountOverOnePerFace() {
        Random random = new Random(99L);
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (random.nextInt(100) < 40) {
                        view.set(x, y, z, 1, true);
                    }
                }
            }
        }

        List<MergedFace> faces = mesh(view);
        int merged = faces.size();
        int surface = faces.stream().mapToInt(MergedFace::area).sum();
        assertTrue(merged < surface,
                "greedy meshing must produce strictly fewer quads than unit faces: "
                        + merged + " quads for " + surface + " unit faces");
        // On a uniform random field a plane's mask is mostly short runs, so the guaranteed
        // reduction is modest; the big wins are on structured geometry (see the slab and shell
        // tests above). Assert a floor, not a target.
        assertTrue(merged * 10 < surface * 9,
                "expected at least a 10% reduction on a random blob: "
                        + merged + " quads for " + surface + " unit faces");
    }
}
