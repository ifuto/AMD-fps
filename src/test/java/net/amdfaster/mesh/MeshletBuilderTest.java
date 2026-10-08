package net.amdfaster.mesh;

import net.amdfaster.light.LightValue;
import net.amdfaster.light.VertexLight;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshletBuilderTest {

    /** Two adjacent 1x1 quads on the z=0 plane, sharing the edge x=1. */
    private static Quad left() {
        return Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0xFF112233, 0x00F000F0);
    }

    private static Quad right() {
        return Quad.uniform(Orientation.NEG_Z, 1, 0, 0, 2, 1, 0, 0f, 0f, 1f, 1f, 0xFF112233, 0x00F000F0);
    }

    @Test
    void identicalCornersAreDeduplicated() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        assertEquals(4, builder.vertexCount());
        builder.add(left());
        assertEquals(4, builder.vertexCount(), "the same corner must not be stored twice");

        Meshlet m = builder.build();
        assertEquals(2, m.quadCount());
        assertEquals(4, m.vertexCount());
        assertEquals(4, m.triangleCount());
    }

    @Test
    void adjacentBlocksDoNotShareVerticesBecauseUvsDiffer() {
        // The dedup key is (position, uv), and every block face carries its own 0..1 uv, so the
        // corner two neighbouring blocks share still gets two different uv values and is stored
        // twice. That is correct -- merging them would smear the texture -- but it means the
        // win has to come from greedy meshing producing fewer, larger quads in the first place.
        // Storing a per-quad uv origin+scale instead of per-corner uv would let positions alone
        // be the dedup key; see the follow-up noted on MeshletBuilder.
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        builder.add(right());

        Meshlet m = builder.build();
        assertEquals(2, m.quadCount());
        assertEquals(8, m.vertexCount());
    }

    @Test
    void boundsCoverEveryCorner() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        builder.add(right());
        Meshlet m = builder.build();

        assertEquals(0, m.minX());
        assertEquals(2, m.maxX());
        assertEquals(0, m.minY());
        assertEquals(1, m.maxY());
        assertEquals(0, m.minZ());
        assertEquals(0, m.maxZ());
    }

    @Test
    void packedBoundsRoundTrips() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        builder.add(right());
        Meshlet m = builder.build();

        int packed = m.packedBounds();
        assertEquals(m.minX(), Meshlet.unpackMinX(packed));
        assertEquals(m.minY(), Meshlet.unpackMinY(packed));
        assertEquals(m.minZ(), Meshlet.unpackMinZ(packed));
        assertEquals(m.maxX(), Meshlet.unpackMaxX(packed));
        assertEquals(m.maxY(), Meshlet.unpackMaxY(packed));
        assertEquals(m.maxZ(), Meshlet.unpackMaxZ(packed));
    }

    @Test
    void packedBoundsSurvivesTheTopOfTheRange() {
        // Section coordinates run 0..16 inclusive, so 16 needs all 5 bits and must not wrap to 0.
        MeshletBuilder builder = new MeshletBuilder(Orientation.POS_Y);
        builder.add(Quad.uniform(Orientation.POS_Y, 15, 16, 15, 16, 16, 16, 0f, 0f, 1f, 1f, 0, 0));
        Meshlet m = builder.build();

        assertEquals(15, m.minX());
        assertEquals(16, m.maxX());
        assertEquals(16, m.minY());
        assertEquals(16, m.maxY());

        int packed = m.packedBounds();
        assertEquals(15, Meshlet.unpackMinX(packed));
        assertEquals(16, Meshlet.unpackMaxX(packed));
        assertEquals(16, Meshlet.unpackMinY(packed));
        assertEquals(16, Meshlet.unpackMaxY(packed));
        assertEquals(15, Meshlet.unpackMinZ(packed));
        assertEquals(16, Meshlet.unpackMaxZ(packed));
    }

    @Test
    void rejectsQuadsOfAnotherOrientation() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        assertThrows(IllegalArgumentException.class,
                () -> builder.add(Quad.uniform(Orientation.POS_Z, 0, 0, 1, 1, 1, 1, 0f, 0f, 1f, 1f, 0, 0)));
    }

    @Test
    void rejectsDegenerateQuads() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        assertThrows(IllegalArgumentException.class,
                () -> builder.add(Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 0, 0, 0f, 0f, 1f, 1f, 0, 0)));
    }

    @Test
    void fillsAtTheDocumentedLimit() {
        // 62 quads = 124 triangles, just under the 126-triangle optimum from the meshlet literature.
        assertEquals(124, Meshlet.MAX_TRIANGLES);
        assertTrue(Meshlet.MAX_TRIANGLES <= 126);
        assertEquals(248, Meshlet.MAX_QUADS * Quad.VERTICES, "must stay inside 8-bit indices");
        assertTrue(Meshlet.MAX_QUADS * Quad.VERTICES <= Meshlet.MAX_VERTICES);

        MeshletBuilder builder = new MeshletBuilder(Orientation.POS_Y);
        for (int i = 0; i < Meshlet.MAX_QUADS; i++) {
            assertFalse(builder.isFull(), "full too early at " + i);
            int x = i % 16;
            int z = i / 16;
            builder.add(Quad.uniform(Orientation.POS_Y, x, 16, z, x + 1, 16, z + 1,
                    0f, 0f, 1f, 1f, 0, 0));
        }
        assertTrue(builder.isFull());
        Meshlet m = builder.build();
        assertEquals(Meshlet.MAX_QUADS, m.quadCount());
        assertTrue(m.vertexCount() <= Meshlet.MAX_VERTICES,
                "vertex count " + m.vertexCount() + " overflows 8-bit indices");
    }

    @Test
    void buildResetsTheBuilder() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        builder.build();
        assertTrue(builder.isEmpty());
        assertEquals(0, builder.vertexCount());

        builder.add(right());
        Meshlet second = builder.build();
        assertEquals(1, second.quadCount());
        assertEquals(4, second.vertexCount());
        assertEquals(1, second.minX());
    }

    @Test
    void gpuBuffersHaveTheDeclaredLayout() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        builder.add(right());
        Meshlet m = builder.build();

        // Derived from the meshlet rather than hardcoded: the vertex count depends on how many
        // corners dedup, and neighbouring blocks do not share any (their uv differs).
        assertEquals(m.vertexCount() * Meshlet.POSITION_STRIDE, m.positionBytes());
        assertEquals(m.vertexCount() * Meshlet.ATTRIBUTE_STRIDE, m.attributeBytes());
        assertEquals(m.quadCount() * Meshlet.INDEX_BYTES_PER_QUAD, m.indexBytes());
        assertTrue(m.positionBytes() % Meshlet.POSITION_STRIDE == 0);

        ByteBuffer positions = ByteBuffer.allocate(m.positionBytes()).order(ByteOrder.LITTLE_ENDIAN);
        m.writePositions(positions);
        assertEquals(0, positions.remaining());

        ByteBuffer attributes = ByteBuffer.allocate(m.attributeBytes()).order(ByteOrder.LITTLE_ENDIAN);
        m.writeAttributes(attributes);
        assertEquals(0, attributes.remaining());

        ByteBuffer indices = ByteBuffer.allocate(m.indexBytes());
        m.writeIndices(indices);
        assertEquals(0, indices.remaining());
    }

    @Test
    void indicesExpandToATriangleListWithoutPrimitiveRestart() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        Meshlet m = builder.build();

        ByteBuffer indices = ByteBuffer.allocate(m.indexBytes());
        m.writeIndices(indices);
        byte[] out = indices.array();

        byte i0 = (byte) m.vertexIndex(0, 0);
        byte i1 = (byte) m.vertexIndex(0, 1);
        byte i2 = (byte) m.vertexIndex(0, 2);
        byte i3 = (byte) m.vertexIndex(0, 3);

        assertEquals(6, out.length);
        assertEquals(i0, out[0]);
        assertEquals(i1, out[1]);
        assertEquals(i2, out[2]);
        assertEquals(i0, out[3]);
        assertEquals(i2, out[4]);
        assertEquals(i3, out[5]);
    }

    @Test
    void positionsAreLittleEndianShorts() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        Meshlet m = builder.build();

        ByteBuffer buffer = ByteBuffer.allocate(m.positionBytes()).order(ByteOrder.LITTLE_ENDIAN);
        m.writePositions(buffer);
        buffer.flip();

        for (int v = 0; v < m.vertexCount(); v++) {
            assertEquals((short) m.positionX(v), buffer.getShort(), "vertex " + v + " x");
            assertEquals((short) m.positionY(v), buffer.getShort(), "vertex " + v + " y");
            assertEquals((short) m.positionZ(v), buffer.getShort(), "vertex " + v + " z");
            assertEquals((short) 0, buffer.getShort(), "vertex " + v + " padding");
        }
    }

    @Test
    void bigEndianBuffersAreRejected() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        Meshlet m = builder.build();

        ByteBuffer bigEndian = ByteBuffer.allocate(m.positionBytes()).order(ByteOrder.BIG_ENDIAN);
        assertThrows(IllegalArgumentException.class, () -> m.writePositions(bigEndian));
    }

    @Test
    void attributesCarryTilingUvOutsideTheUnitSquare() {
        // A greedy quad spanning 4 blocks tiles its texture, so uv must exceed 0..1.
        MeshletBuilder builder = new MeshletBuilder(Orientation.POS_Y);
        builder.add(Quad.uniform(Orientation.POS_Y, 0, 16, 0, 4, 16, 4, 0f, 0f, 4f, 4f, 0, 0));
        Meshlet m = builder.build();

        boolean foundBeyondOne = false;
        for (int v = 0; v < m.vertexCount(); v++) {
            if (m.u(v) > 1f || m.v(v) > 1f) {
                foundBeyondOne = true;
            }
        }
        assertTrue(foundBeyondOne, "greedy quads must be able to tile their texture");
    }

    /** A quad on the z=0 plane carrying four distinct corner lights. */
    private static Quad fourLights() {
        return new Quad(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0xFF112233,
                VertexLight.packLight(LightValue.pack(0, 15), 3),
                VertexLight.packLight(LightValue.pack(4, 15), 2),
                VertexLight.packLight(LightValue.pack(8, 11), 1),
                VertexLight.packLight(LightValue.pack(12, 7), 0));
    }

    @Test
    void aUniformQuadLightsEveryCornerTheSame() {
        Quad quad = left();
        for (int corner = 0; corner < Quad.VERTICES; corner++) {
            assertEquals(quad.lightA(), quad.cornerLight(corner), "corner " + corner);
        }
        assertEquals(0x00F000F0, quad.lightA());
    }

    @Test
    void eachCornerKeepsItsOwnLight() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        Quad quad = fourLights();
        builder.add(quad);
        Meshlet m = builder.build();

        assertEquals(4, m.vertexCount(), "four distinct corners");
        for (int corner = 0; corner < Quad.VERTICES; corner++) {
            int vertex = m.vertexIndex(0, corner);
            assertEquals(quad.cornerLight(corner), m.light(vertex), "corner " + corner);
            // And the light is a real packed word, not a placeholder. The four corners were built
            // with sky 15,15,11,7 and occlusion 3,2,1,0.
            int[] expectedSky = {15, 15, 11, 7};
            assertEquals(expectedSky[corner],
                    LightValue.sky(VertexLight.unpackLight(m.light(vertex))), "corner " + corner);
            assertEquals(3 - corner, VertexLight.unpackAo(m.light(vertex)), "corner " + corner);
            assertEquals(corner == 0 ? 0 : corner == 1 ? 4 : corner == 2 ? 8 : 12,
                    LightValue.block(VertexLight.unpackLight(m.light(vertex))), "corner " + corner);
        }
    }

    @Test
    void cornersThatDifferOnlyInLightAreNotDeduplicated() {
        // The corruption this guards against is invisible until a seam appears in the wrong place.
        // Two corners can share a position and a uv and still be lit differently -- where a lit
        // face meets a shaded one is exactly that -- and merging them gives both whichever light
        // the mesher happened to visit first.
        int bright = VertexLight.packLight(LightValue.pack(15, 15), 3);
        int dark = VertexLight.packLight(LightValue.pack(0, 0), 0);

        MeshletBuilder same = new MeshletBuilder(Orientation.NEG_Z);
        same.add(Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0, bright));
        same.add(Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0, bright));
        assertEquals(4, same.vertexCount(), "identical in every respect, so they merge");

        MeshletBuilder different = new MeshletBuilder(Orientation.NEG_Z);
        different.add(Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0, bright));
        different.add(Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0, dark));
        assertEquals(8, different.vertexCount(), "same place, same uv, different light: two vertices");

        Meshlet m = different.build();
        assertEquals(bright, m.light(m.vertexIndex(0, 0)));
        assertEquals(dark, m.light(m.vertexIndex(1, 0)));
    }

    @Test
    void theLightStreamHasTheDeclaredLayout() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        builder.add(right());
        Meshlet m = builder.build();

        assertEquals(4, Meshlet.LIGHT_STRIDE);
        assertEquals(m.vertexCount() * Meshlet.LIGHT_STRIDE, m.lightBytes());
        assertEquals(0, m.lightBytes() % Meshlet.LIGHT_STRIDE);
        // Four bytes divides a 64-byte line exactly, so no vertex straddles one.
        assertEquals(0, 64 % Meshlet.LIGHT_STRIDE);

        ByteBuffer lights = ByteBuffer.allocate(m.lightBytes()).order(ByteOrder.LITTLE_ENDIAN);
        m.writeLights(lights);
        assertEquals(0, lights.remaining(), "the stream must be filled exactly");

        lights.flip();
        for (int i = 0; i < m.vertexCount(); i++) {
            assertEquals(m.light(i), lights.getInt(), "vertex " + i);
        }
    }

    @Test
    void theLightStreamRejectsABigEndianBuffer() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(left());
        Meshlet m = builder.build();
        ByteBuffer bigEndian = ByteBuffer.allocate(m.lightBytes());
        assertEquals(ByteOrder.BIG_ENDIAN, bigEndian.order());
        assertThrows(IllegalArgumentException.class, () -> m.writeLights(bigEndian));
    }

    @Test
    void rebuildingStartsTheVertexLightsFresh() {
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(fourLights());
        Meshlet first = builder.build();

        builder.add(left());
        Meshlet second = builder.build();
        assertEquals(4, second.vertexCount());
        assertEquals(left().lightA(), second.light(0), "no light left over from the previous build");
        assertEquals(4, first.vertexCount(), "the first meshlet is unaffected by the reset");
    }
}
