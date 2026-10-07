package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.Quad;
import net.amdfaster.mesh.SectionMesh;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionMesherTest {

    @Test
    void aSingleBlockProducesASixQuadSection() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);

        SectionMesh mesh = SectionMesher.mesh(view, 0, 0, 0);
        assertEquals(6, mesh.totalQuads());
        assertEquals(12, mesh.totalTriangles());
        assertEquals(6, mesh.totalMeshlets());
        assertEquals(24, mesh.totalVertices());
    }

    @Test
    void aFullFloorProducesSixQuadsAndDistantCamerasDrawHalf() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(0, 0, 0, 16, 1, 16, 7, true);

        SectionMesh mesh = SectionMesher.mesh(view, 0, 0, 0);
        assertEquals(6, mesh.totalQuads());
        assertEquals(12, mesh.totalTriangles());

        assertEquals(12, mesh.visibleTriangles(8, 8, 8));
        assertEquals(6, mesh.visibleTriangles(100, 100, 100));
    }

    @Test
    void textureCoordinatesSpanTheMergedRectangle() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        // uScale/vScale are 1, so a 4x3 merged face tiles its sprite 4 across and 3 down.
        MergedFace face = new MergedFace(Orientation.POS_Y, 5, 2, 3, 6, 6, 9);
        Quad quad = SectionMesher.toQuad(face, view);

        assertEquals(4, quad.u1() - quad.u0(), 1e-6f);
        assertEquals(3, quad.v1() - quad.v0(), 1e-6f);
        assertEquals(9, quad.color());
        assertEquals(Orientation.POS_Y, quad.orientation());
        assertEquals(2, quad.x0());
        assertEquals(6, quad.x1());
        assertEquals(5, quad.y0());
        assertEquals(5, quad.y1());
        assertEquals(3, quad.z0());
        assertEquals(6, quad.z1());
    }

    @Test
    void quadCornersStayInsideTheSection() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(0, 0, 0, 16, 16, 16, 1, false);

        SectionMesh mesh = SectionMesher.mesh(view, 0, 0, 0);
        for (Orientation o : Orientation.values()) {
            for (var meshlet : mesh.meshlets(o)) {
                for (int v = 0; v < meshlet.vertexCount(); v++) {
                    assertTrue(meshlet.positionX(v) >= 0 && meshlet.positionX(v) <= 16,
                            o + " x out of range: " + meshlet.positionX(v));
                    assertTrue(meshlet.positionY(v) >= 0 && meshlet.positionY(v) <= 16,
                            o + " y out of range: " + meshlet.positionY(v));
                    assertTrue(meshlet.positionZ(v) >= 0 && meshlet.positionZ(v) <= 16,
                            o + " z out of range: " + meshlet.positionZ(v));
                }
            }
        }
    }

    @Test
    void sectionOriginIsCarriedThroughToTheBucketMask() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(0, 0, 0, 16, 1, 16, 7, true);

        SectionMesh mesh = SectionMesher.mesh(view, 32, 64, -16);
        assertEquals(32, mesh.originX());
        assertEquals(64, mesh.originY());
        assertEquals(-16, mesh.originZ());
        assertEquals(12, mesh.visibleTriangles(40, 65, -8), "camera inside this section");
        assertEquals(6, mesh.visibleTriangles(100, 200, 100));
    }
}
