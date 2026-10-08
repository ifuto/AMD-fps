package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexLightPackingTest {

    @Test
    void theOcclusionLevelSitsInBitsTheLightmapLeavesFree() {
        // The whole reason this fits in one word: the lightmap coordinate occupies bits 4..7 and
        // 20..23, so bits 24..31 are unused and two of them hold the occlusion level.
        assertEquals(0x00000000, VertexLight.packLight(LightValue.dark(), 0));
        assertEquals(0x00F000F0, VertexLight.packLight(LightValue.fullBright(), 0));
        assertEquals(0x01000000, VertexLight.packLight(LightValue.dark(), 1));
        assertEquals(0x03F000F0, VertexLight.packLight(LightValue.fullBright(), 3));
    }

    @Test
    void everyCombinationRoundTrips() {
        for (int block = 0; block <= LightValue.MAX; block++) {
            for (int sky = 0; sky <= LightValue.MAX; sky++) {
                int light = LightValue.pack(block, sky);
                for (int ao = VertexLight.AO_MIN; ao <= VertexLight.AO_MAX; ao++) {
                    int packed = VertexLight.packLight(light, ao);
                    assertEquals(light, VertexLight.unpackLight(packed),
                            "light survives untouched at block=" + block + " sky=" + sky + " ao=" + ao);
                    assertEquals(ao, VertexLight.unpackAo(packed), "ao=" + ao);

                    VertexLight back = VertexLight.unpack(packed);
                    assertEquals(light, back.light());
                    assertEquals(ao, back.ao());
                    assertEquals(packed, back.packed(), "repacking is stable");
                }
            }
        }
    }

    @Test
    void packedAgreesWithTheStaticForm() {
        VertexLight vertex = new VertexLight(LightValue.pack(4, 11), 2);
        assertEquals(VertexLight.packLight(vertex.light(), vertex.ao()), vertex.packed());
        assertEquals(vertex, VertexLight.unpack(vertex.packed()));
    }

    @Test
    void theOcclusionLevelChangesTheWordButNotTheLight() {
        int light = LightValue.pack(7, 9);
        for (int ao = VertexLight.AO_MIN; ao <= VertexLight.AO_MAX; ao++) {
            assertEquals(light, VertexLight.unpackLight(VertexLight.packLight(light, ao)));
        }
        // And the words are distinct, or the shader could not tell the corners apart.
        assertEquals(4, java.util.stream.IntStream.rangeClosed(VertexLight.AO_MIN, VertexLight.AO_MAX)
                .map(ao -> VertexLight.packLight(light, ao)).distinct().count());
    }

    @Test
    void anOcclusionLevelOutsideZeroToThreeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> VertexLight.packLight(0, 4));
        assertThrows(IllegalArgumentException.class, () -> VertexLight.packLight(0, -1));
        // Four would need a third bit and would start eating the lightmap's sky channel.
        assertThrows(IllegalArgumentException.class, () -> VertexLight.packLight(0, 1 << 2));
    }

    @Test
    void fullBrightUnoccludedIsTheBrightestWord() {
        int packed = VertexLight.fullBright().packed();
        assertEquals(LightValue.fullBright(), VertexLight.unpackLight(packed));
        assertEquals(VertexLight.AO_MAX, VertexLight.unpackAo(packed));
        for (int block = 0; block <= 15; block++) {
            for (int sky = 0; sky <= 15; sky++) {
                for (int ao = 0; ao <= 3; ao++) {
                    assertTrue(VertexLight.unpackLight(packed)
                                    >= VertexLight.unpackLight(
                                            VertexLight.packLight(LightValue.pack(block, sky), ao)),
                            "nothing is brighter than full bright");
                }
            }
        }
    }
}
