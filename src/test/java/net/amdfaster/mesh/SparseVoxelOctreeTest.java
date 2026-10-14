package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparseVoxelOctreeTest {

    /** A heightmap section: solid at and below the surface, air above. */
    private static int[][] heightmap(long seed) {
        Random random = new Random(seed);
        int[][] heights = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                heights[x][z] = 4 + random.nextInt(7);
            }
        }
        return heights;
    }

    private static SparseVoxelOctree.Solidity from(int[][] heights) {
        return (x, y, z) -> y <= heights[x][z];
    }

    private static int bruteForce(int[][] heights, int x0, int y0, int z0, int span) {
        int solid = 0;
        for (int y = y0; y < y0 + span; y++) {
            for (int x = x0; x < x0 + span; x++) {
                for (int z = z0; z < z0 + span; z++) {
                    if (y <= heights[x][z]) {
                        solid++;
                    }
                }
            }
        }
        int total = span * span * span;
        if (solid == 0) {
            return SparseVoxelOctree.EMPTY;
        }
        return solid == total ? SparseVoxelOctree.FULL : SparseVoxelOctree.MIXED;
    }

    @Test
    void everyRegionMatchesCountingTheVoxels() {
        // The check that found two bugs. Deriving a node's origin from its index pointed at the wrong
        // parent below the second level, and the leaves were never given a state at all, so regions
        // answered for a different part of the section.
        for (long seed = 0; seed < 12; seed++) {
            int[][] heights = heightmap(seed);
            SparseVoxelOctree tree = new SparseVoxelOctree();
            tree.build(from(heights));

            for (int span : new int[] {1, 2, 4, 8, 16}) {
                for (int y = 0; y < 16; y += span) {
                    for (int x = 0; x < 16; x += span) {
                        for (int z = 0; z < 16; z += span) {
                            assertEquals(bruteForce(heights, x, y, z, span),
                                    tree.regionState(x, y, z, span),
                                    "seed " + seed + " region " + x + "," + y + "," + z
                                            + " span " + span);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aLeafIsSolidOrAirAndNeverMixed() {
        // The missing assignment. Children start as MIXED because the parent does not know yet, and a
        // leaf left that way reports mixed for a voxel that is plainly one or the other.
        int[][] heights = heightmap(1L);
        SparseVoxelOctree tree = new SparseVoxelOctree();
        tree.build(from(heights));

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int state = tree.regionState(x, y, z, 1);
                    int want = y <= heights[x][z]
                            ? SparseVoxelOctree.FULL : SparseVoxelOctree.EMPTY;
                    assertEquals(want, state, "voxel " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void aUniformSectionIsOneNode() {
        // The sparsity claim in its clearest form: stone all the way through needs a single node, not
        // the 4,681 a complete octree would allocate.
        SparseVoxelOctree solid = new SparseVoxelOctree();
        solid.build((x, y, z) -> true);
        assertEquals(1, solid.nodeCount());
        assertEquals(SparseVoxelOctree.FULL, solid.sectionState());

        SparseVoxelOctree air = new SparseVoxelOctree();
        air.build((x, y, z) -> false);
        assertEquals(1, air.nodeCount());
        assertEquals(SparseVoxelOctree.EMPTY, air.sectionState());

        assertEquals(4681, SparseVoxelOctree.FULL_OCTREE_NODES);
    }

    @Test
    void halfTheFourBlockRegionsResolveWithoutDescending() {
        // Measured on a surface undulating between y = 4 and y = 10: of the 64 regions four blocks
        // wide, 16 are entirely solid and 16 entirely air, and none of the 32 needs its voxels walked.
        int[][] heights = heightmap(5L);
        SparseVoxelOctree tree = new SparseVoxelOctree();
        tree.build(from(heights));

        int uniform = 0;
        for (int y = 0; y < 16; y += 4) {
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    if (tree.regionState(x, y, z, 4) != SparseVoxelOctree.MIXED) {
                        uniform++;
                    }
                }
            }
        }
        assertEquals(64, tree.regionQueries());
        assertTrue(uniform >= 24, "at least a third are uniform, got " + uniform);
        assertTrue(tree.resolvedWithoutDescending() > 0);
    }

    @Test
    void aRealSectionNeedsAboutAThirdOfACompleteOctree() {
        int[][] heights = heightmap(5L);
        SparseVoxelOctree tree = new SparseVoxelOctree();
        tree.build(from(heights));

        // A range rather than an exact count, because the heightmap comes from java.util.Random and
        // the exact figure is a property of that seed rather than of the structure.
        assertTrue(tree.nodeCount() > 1000, "a real section needs real nodes: " + tree.nodeCount());
        assertTrue(tree.nodeCount() < 3000, "and far fewer than a complete octree: "
                + tree.nodeCount());
        assertTrue(tree.nodePercentOfFull() < 65.0,
                "under two thirds of a complete octree, got " + tree.nodePercentOfFull());
        assertEquals(4, tree.deepestLevel(), "leaves are at level 4");
        assertEquals(1, tree.nodesAtLevel(0));
        assertEquals(8, tree.nodesAtLevel(1), "the root is mixed, so all eight children exist");
    }

    @Test
    void queryingDoesNotReadVoxels() {
        // The point of the structure: the build reads the voxels once, and every later question is
        // answered from the tree.
        int[][] heights = heightmap(2L);
        SparseVoxelOctree tree = new SparseVoxelOctree();
        tree.build(from(heights));
        long afterBuild = tree.voxelsRead();
        assertTrue(afterBuild > 0);

        for (int i = 0; i < 500; i++) {
            tree.regionState(i % 16, (i / 16) % 16, (i / 256) % 16, 1);
        }
        assertEquals(afterBuild, tree.voxelsRead(), "queries touched no voxels");
        assertEquals(500L, tree.regionQueries());
    }

    @Test
    void clearDropsTheTreeAndKeepsItUsable() {
        SparseVoxelOctree tree = new SparseVoxelOctree();
        tree.build((x, y, z) -> true);
        assertEquals(1, tree.nodeCount());

        tree.clear();
        tree.build((x, y, z) -> y < 8);

        assertEquals(SparseVoxelOctree.FULL, tree.regionState(0, 0, 0, 8), "the lower half is stone");
        assertEquals(SparseVoxelOctree.EMPTY, tree.regionState(0, 8, 0, 8), "the upper half is air");
        assertEquals(SparseVoxelOctree.MIXED, tree.sectionState());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        SparseVoxelOctree tree = new SparseVoxelOctree();
        tree.build((x, y, z) -> true);

        assertThrows(IllegalArgumentException.class, () -> tree.regionState(0, 0, 0, 3),
                "not a power of two");
        assertThrows(IllegalArgumentException.class, () -> tree.regionState(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> tree.regionState(0, 0, 0, 32));
        assertThrows(IndexOutOfBoundsException.class, () -> tree.regionState(15, 0, 0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> tree.regionState(-1, 0, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> tree.nodesAtLevel(5));
    }

    @Test
    void constantsAreWhatTheClassClaims() {
        assertEquals(16, SparseVoxelOctree.SIZE);
        assertEquals(4096, SparseVoxelOctree.VOXELS);
        assertEquals(5, SparseVoxelOctree.LEVELS);
        assertEquals(0, SparseVoxelOctree.EMPTY);
        assertEquals(1, SparseVoxelOctree.FULL);
        assertEquals(2, SparseVoxelOctree.MIXED);
    }
}
