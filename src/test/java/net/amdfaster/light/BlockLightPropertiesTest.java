package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockLightPropertiesTest {

    private static final int STONE = 1;
    private static final int AIR = 0;
    private static final int TORCH = 50;
    private static final int LEAVES = 18;
    private static final int WATER = 9;
    private static final int SLAB = 44;

    private static BlockLightProperties populated() {
        BlockLightProperties properties = BlockLightProperties.forMinecraft();
        properties.registerClear(AIR);
        properties.registerOpaque(STONE);
        properties.registerEmitter(TORCH, 14);
        properties.registerSkyFilter(LEAVES);
        properties.register(WATER, 3, 0, false, false);
        properties.register(SLAB, 0, 0, false, true);
        return properties;
    }

    @Test
    void theTableIsTinyBecauseItIsIndexedByBlockIdentityNotPosition() {
        // The distinction that makes this cache and the position cache complementary. This one covers
        // every block kind in the game; the position cache covers the coordinates being meshed. So this
        // stays in L1 for the whole run at a few kilobytes, where a position cache has to be tens of
        // thousands of entries to hold a working set at all.
        BlockLightProperties properties = BlockLightProperties.forMinecraft();
        assertEquals(4096, properties.blockCount());
        assertEquals(16384, properties.tableBytes());
        assertTrue(properties.tableBytes() < LightField.SECTION_BYTES * 8,
                "smaller than the light arrays for eight sections");
    }

    @Test
    void everyPropertySurvivesPackingIntoOneInt() {
        BlockLightProperties properties = populated();

        assertEquals(0, properties.attenuationOf(AIR));
        assertEquals(0, properties.emissionOf(AIR));
        assertFalse(properties.isOpaque(AIR));
        assertFalse(properties.filtersSkylight(AIR));
        assertFalse(properties.usesShapeForOcclusion(AIR));

        assertEquals(15, properties.attenuationOf(STONE));
        assertEquals(0, properties.emissionOf(STONE));
        assertTrue(properties.isOpaque(STONE));

        assertEquals(14, properties.emissionOf(TORCH), "a torch's own level");
        assertEquals(0, properties.attenuationOf(TORCH));
        assertFalse(properties.isOpaque(TORCH), "and it does not block its own light");

        assertEquals(1, properties.attenuationOf(LEAVES));
        assertTrue(properties.filtersSkylight(LEAVES), "leaves break the free downward fall");
        assertFalse(properties.isOpaque(LEAVES));

        assertEquals(3, properties.attenuationOf(WATER));
        assertTrue(properties.usesShapeForOcclusion(SLAB), "a slab does not fill its cell");
    }

    @Test
    void attenuationOfEveryValueRoundTrips() {
        // Four bits, so the top of the range is where an overflow would show.
        BlockLightProperties properties = BlockLightProperties.forMinecraft();
        for (int attenuation = 0; attenuation <= 15; attenuation++) {
            properties.register(attenuation, attenuation, 15 - attenuation, attenuation % 2 == 0,
                    attenuation % 3 == 0);
        }
        for (int attenuation = 0; attenuation <= 15; attenuation++) {
            assertEquals(attenuation, properties.attenuationOf(attenuation), "id " + attenuation);
            assertEquals(15 - attenuation, properties.emissionOf(attenuation), "id " + attenuation);
            assertEquals(attenuation % 2 == 0, properties.filtersSkylight(attenuation));
            assertEquals(attenuation % 3 == 0, properties.usesShapeForOcclusion(attenuation));
        }
    }

    @Test
    void anUnregisteredBlockIsTreatedAsFullyOpaque() {
        // The safe default. Assuming an unknown block lets light through would light caves that should
        // be dark, which is visible and wrong; assuming it blocks costs nothing visible, because an
        // unregistered block is a mod the light table has not been told about.
        BlockLightProperties properties = populated();
        assertTrue(properties.isOpaque(4000));
        assertEquals(15, properties.attenuationOf(4000));
        assertEquals(0, properties.emissionOf(4000));
        assertFalse(properties.isRegistered(4000));
        assertTrue(properties.isRegistered(STONE));

        assertEquals(BlockLightProperties.UNREGISTERED, properties.rawOf(-1),
                "and a negative id, which cannot happen but must not index the array");
        assertFalse(properties.isRegistered(-1));
    }

    @Test
    void aClearBlockCostsTheFloorOfOne() {
        // The lookup and the attenuation rule have to agree. Attenuation 0 stored here and floored to 1
        // by the engine is what makes glass and air equivalent, and it is the pair of facts that the
        // naive formulation breaks.
        BlockLightProperties properties = populated();
        assertEquals(0, properties.attenuationOf(AIR));
        assertEquals(LightEngine.attenuatedLevel(15, properties.attenuationOf(AIR)),
                LightEngine.attenuatedLevel(15, 0));
        assertEquals(14, LightEngine.attenuatedLevel(15, properties.attenuationOf(AIR)));
    }

    @Test
    void lookupsAreCountedSoTheHitBehaviourIsVisible() {
        BlockLightProperties properties = populated();
        assertEquals(0, properties.lookups());
        properties.attenuationOf(STONE);
        properties.emissionOf(TORCH);
        assertEquals(2, properties.lookups(),
                "each property read is one table lookup, and there is no miss path to count");
    }

    @Test
    void theHighestRegisteredIdTracksWhatHasBeenDefined() {
        BlockLightProperties properties = populated();
        assertEquals(TORCH, properties.highestRegisteredId());
        properties.registerOpaque(3000);
        assertEquals(3000, properties.highestRegisteredId());
        properties.registerClear(10);
        assertEquals(3000, properties.highestRegisteredId(), "a lower id does not move it back");
    }

    @Test
    void clearRestoresTheOpaqueDefault() {
        BlockLightProperties properties = populated();
        properties.clear();
        assertEquals(0, properties.highestRegisteredId());
        assertTrue(properties.isOpaque(STONE));
        assertFalse(properties.isRegistered(AIR));
        assertEquals(0, properties.lookups());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BlockLightProperties(0));
        BlockLightProperties properties = BlockLightProperties.forMinecraft();
        assertThrows(IllegalArgumentException.class, () -> properties.register(1, 16, 0, false, false));
        assertThrows(IllegalArgumentException.class, () -> properties.register(1, 0, -1, false, false));
        assertThrows(IllegalArgumentException.class, () -> properties.register(4096, 0, 0, false, false));
        assertThrows(IllegalArgumentException.class, () -> properties.register(-1, 0, 0, false, false));
    }
}
