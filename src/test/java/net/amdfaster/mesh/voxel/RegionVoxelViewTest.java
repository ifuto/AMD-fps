package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.voxel.RegionVoxelView.BlockSampler;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionVoxelViewTest {

    /** A sampler backed by a predicate over world coordinates. */
    private static BlockSampler solidWhere(Predicate<long[]> solid) {
        return new BlockSampler() {
            @Override
            public int key(int x, int y, int z, Orientation orientation) {
                return solid.test(new long[] {x, y, z}) ? 1 : VoxelView.NO_GEOMETRY;
            }

            @Override
            public boolean isOpaque(int x, int y, int z) {
                return solid.test(new long[] {x, y, z});
            }
        };
    }

    private static int quads(SectionMesh mesh, Orientation o) {
        return mesh.meshlets(o).length == 0 ? 0
                : java.util.Arrays.stream(mesh.meshlets(o)).mapToInt(m -> m.quadCount()).sum();
    }

    @Test
    void theViewIsSixteenCubedNotEighteen() {
        // A view of 18 cubed would make the mesher iterate the border blocks and emit their faces,
        // duplicating the neighbouring section's geometry -- the same seam from the other side.
        RegionVoxelView view = new RegionVoxelView(solidWhere(c -> false), 32, 64, -16);
        assertEquals(16, view.sizeX());
        assertEquals(16, view.sizeY());
        assertEquals(16, view.sizeZ());
        assertEquals(32, view.originX());
        assertEquals(64, view.originY());
        assertEquals(-16, view.originZ());
    }

    @Test
    void coordinatesAreTranslatedIntoWorldSpace() {
        Set<Long> asked = new HashSet<>();
        BlockSampler probe = new BlockSampler() {
            @Override
            public int key(int x, int y, int z, Orientation orientation) {
                asked.add(((long) x << 40) ^ ((long) z << 20) ^ (long) y);
                return VoxelView.NO_GEOMETRY;
            }

            @Override
            public boolean isOpaque(int x, int y, int z) {
                return false;
            }
        };
        RegionVoxelView view = new RegionVoxelView(probe, 100, 200, 300);
        view.key(0, 0, 0, Orientation.POS_X);
        assertEquals(1, asked.size());
        assertTrue(asked.contains(((long) 100 << 40) ^ ((long) 300 << 20) ^ 200L));
    }

    @Test
    void aSolidNeighbourSectionCullsTheSharedFace() {
        // The section at the origin, plus the section at +X, both solid. Without the border the
        // mesher sees x=16 as out of bounds, calls it air, and grows a wall of quads at the seam.
        BlockSampler world = solidWhere(c -> c[0] >= 0 && c[0] < 32 && c[1] >= 0 && c[1] < 16
                && c[2] >= 0 && c[2] < 16);
        SectionMesh mesh = SectionMesher.mesh(new RegionVoxelView(world, 0, 0, 0), 0, 0, 0);

        assertEquals(0, quads(mesh, Orientation.POS_X), "the +X neighbour is solid, so no face");
        assertEquals(1, quads(mesh, Orientation.NEG_X), "x = -1 is air");
        assertEquals(1, quads(mesh, Orientation.POS_Y));
        assertEquals(1, quads(mesh, Orientation.NEG_Y));
        assertEquals(1, quads(mesh, Orientation.POS_Z));
        assertEquals(1, quads(mesh, Orientation.NEG_Z));
        assertEquals(5, mesh.totalQuads());
    }

    @Test
    void theSameSectionAloneGrowsSixFaces() {
        // The control for the test above: nothing outside the section, so all six sides show.
        BlockSampler world = solidWhere(c -> c[0] >= 0 && c[0] < 16 && c[1] >= 0 && c[1] < 16
                && c[2] >= 0 && c[2] < 16);
        SectionMesh mesh = SectionMesher.mesh(new RegionVoxelView(world, 0, 0, 0), 0, 0, 0);
        assertEquals(6, mesh.totalQuads());
        assertEquals(1, quads(mesh, Orientation.POS_X));
    }

    @Test
    void anInfiniteSolidWorldDrawsNothing() {
        // Nothing is exposed, so nothing is emitted -- and in particular the border blocks are not
        // meshed, which an 18-cubed view would have done.
        SectionMesh mesh = SectionMesher.mesh(new RegionVoxelView(solidWhere(c -> true), 0, 0, 0), 0, 0, 0);
        assertTrue(mesh.isEmpty());
        assertEquals(0, mesh.totalQuads());
    }

    @Test
    void theBottomOfTheWorldStillGetsAFloor() {
        // Below the build limit there is genuinely nothing, so the sampler says "not in world" and
        // the face survives. Without that, the underside of the world is invisible from below.
        BlockSampler world = new BlockSampler() {
            @Override
            public int key(int x, int y, int z, Orientation orientation) {
                return y >= 0 && y < 16 ? 1 : VoxelView.NO_GEOMETRY;
            }

            @Override
            public boolean isOpaque(int x, int y, int z) {
                return y >= 0 && y < 16;
            }

            @Override
            public boolean isInWorld(int x, int y, int z) {
                return y >= 0 && y < 384;
            }
        };
        SectionMesh mesh = SectionMesher.mesh(new RegionVoxelView(world, 0, 0, 0), 0, 0, 0);
        assertEquals(1, quads(mesh, Orientation.NEG_Y), "y = -1 is outside the world, so it is air");
        assertEquals(1, quads(mesh, Orientation.POS_Y), "y = 16 is in the world and empty, so also air");
    }

    @Test
    void outsideTheWorldIsAirNotOpaque() {
        BlockSampler world = new BlockSampler() {
            @Override
            public int key(int x, int y, int z, Orientation orientation) {
                return VoxelView.NO_GEOMETRY;
            }

            @Override
            public boolean isOpaque(int x, int y, int z) {
                return true;   // would say opaque if asked
            }

            @Override
            public boolean isInWorld(int x, int y, int z) {
                return false;
            }
        };
        RegionVoxelView view = new RegionVoxelView(world, 0, 0, 0);
        assertFalse(view.isOpaque(0, 0, 0));
        assertEquals(VoxelView.NO_GEOMETRY, view.key(0, 0, 0, Orientation.POS_X));
    }

    @Test
    void theBorderRequirementIsOneBlock() {
        assertEquals(1, RegionVoxelView.BORDER);
    }

    @Test
    void aNonOriginSectionMeshesTheSameGeometry() {
        // The sampler is in world coordinates, so moving the section origin must not change what
        // comes out -- only where the mesh says it is.
        BlockSampler world = solidWhere(c -> c[0] >= 16 && c[0] < 32 && c[1] >= 0 && c[1] < 16
                && c[2] >= 0 && c[2] < 16);
        SectionMesh atOrigin = SectionMesher.mesh(new RegionVoxelView(world, 16, 0, 0), 16, 0, 0);
        assertEquals(6, atOrigin.totalQuads());
        assertEquals(16, atOrigin.originX());
    }
}
