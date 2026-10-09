package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attenuation rule on its own.
 *
 * <p>Split out from the propagation tests because the rule is a one-line expression that is wrong in a
 * way nothing else reports. Getting it wrong does not crash or produce an obviously broken image; it
 * makes foliage darker than it should be and nothing complains. So the expression is pinned directly,
 * against values derived from a model of Minecraft's rule rather than from the implementation.
 */
class LightAttenuationTest {

    private static final LightEngine.OpacitySource AIR = (x, y, z) -> 0;

    private static int reachThrough(int opacity) {
        LightEngine engine = new LightEngine(new LightField(0, 0, 0, 32, 1, 1));
        engine.addSource(0, 0, 0, LightEngine.MAX_LEVEL, (x, y, z) -> x == 0 ? 0 : opacity);
        int reach = 0;
        while (reach + 1 < 32 && engine.field().get(reach + 1, 0, 0) > 0) {
            reach++;
        }
        return reach;
    }

    @Test
    void everyBlockCostsAtLeastOneLevel() {
        // The floor is the whole rule. Glass (attenuation 0) and air (attenuation 0) cost the same as
        // each other, and leaves (attenuation 1) also cost the same -- so light travels fourteen blocks
        // through all three. A formulation of level - 1 - opacity, which looks equivalent for air,
        // makes leaves cost two and halves the reach through every tree in the game.
        assertEquals(14, reachThrough(0), "air");
        assertEquals(14, reachThrough(1), "leaves: attenuates, but no worse than air");
        assertEquals(7, reachThrough(2));
        assertEquals(4, reachThrough(3), "water");
        assertEquals(2, reachThrough(7));
    }

    @Test
    void anOpacityOfFourteenPassesOneLevelRatherThanBlocking() {
        // The case where the two formulations disagree outright. level - 1 - 14 is 0, which reads as
        // "fully blocked"; max(1, 14) subtracted from 15 is 1, which is one dim block of light. A block
        // at attenuation 14 must let a little through.
        assertEquals(1, reachThrough(14));
    }

    @Test
    void fullOpacityBlocksEntirely() {
        assertEquals(0, reachThrough(15), "stone, dirt, anything fully opaque");
    }

    @Test
    void skylightAtMaximumFallsWithoutAttenuating() {
        // The one asymmetry in the rule. Level-15 skylight entering a non-filtering block from above
        // keeps its level, which is what makes an open field uniformly bright rather than dimming with
        // depth. Modelled: a 16-tall open column under sky holds 15 all the way down.
        LightEngine sky = new LightEngine(new LightField(0, 0, 0, 1, 16, 1), LightEngine.Mode.SKY);
        sky.addSource(0, 15, 0, LightEngine.MAX_LEVEL, AIR);
        for (int y = 0; y < 16; y++) {
            assertEquals(LightEngine.MAX_LEVEL, sky.field().get(0, y, 0), "open sky at y=" + y);
        }
    }

    @Test
    void blockLightInTheSameGeometryFallsByOnePerBlock() {
        // Same volume, same source, different mode. The contrast is what pins the rule: if skylight
        // attenuated like block light the field would be bright at the top and dark at the bottom.
        LightEngine block = new LightEngine(new LightField(0, 0, 0, 1, 16, 1), LightEngine.Mode.BLOCK);
        block.addSource(0, 15, 0, LightEngine.MAX_LEVEL, AIR);
        for (int y = 0; y < 16; y++) {
            assertEquals(Math.max(0, 15 - (15 - y)), block.field().get(0, y, 0), "y=" + y);
        }
    }

    @Test
    void skylightOnlyFallsFreelyThroughNonFilteringBlocks() {
        // Leaves filter. That is why a forest floor is dimmer than an open field even though both are
        // outdoors, and it is the difference between attenuation affecting skylight and not.
        LightEngine.OpacitySource leaves = (x, y, z) -> 1;
        LightEngine sky = new LightEngine(new LightField(0, 0, 0, 1, 16, 1), LightEngine.Mode.SKY);
        sky.addSource(0, 15, 0, LightEngine.MAX_LEVEL, leaves);
        assertEquals(LightEngine.MAX_LEVEL, sky.field().get(0, 15, 0));
        assertEquals(14, sky.field().get(0, 14, 0), "through leaves the free fall does not apply");
        assertEquals(13, sky.field().get(0, 13, 0));
    }

    @Test
    void skylightBelowMaximumBehavesLikeBlockLightInEveryDirection() {
        // The free fall applies only at level 15. Once skylight has been attenuated it spreads like any
        // other light, including upward, which is what lets it wrap around an overhang.
        LightEngine.OpacitySource roof = (x, y, z) -> (x == 0 && y >= 8) ? 15 : 0;
        LightEngine sky = new LightEngine(new LightField(0, 0, 0, 2, 16, 1), LightEngine.Mode.SKY);
        sky.addSource(1, 15, 0, LightEngine.MAX_LEVEL, roof);
        sky.addSource(0, 15, 0, LightEngine.MAX_LEVEL, roof);

        // Column 1 is open, so it is lit to the bottom at full level.
        assertEquals(LightEngine.MAX_LEVEL, sky.field().get(1, 0, 0));
        // Column 0 is roofed from y=8 up, so it gets no skylight from above. Every cell below the roof
        // is lit from the side instead, and because column 1 is open it holds 15 at every height -- so
        // each roofed cell receives 14 from its own horizontal neighbour rather than inheriting from the
        // cell above it. The column reads a uniform 14, which is easy to mistake for light falling
        // without attenuation; it is six separate horizontal steps, one per level.
        assertEquals(14, sky.field().get(0, 7, 0), "just under the roof, lit from the side");
        assertEquals(14, sky.field().get(0, 0, 0), "and the same at the bottom, lit from the side again");
        assertEquals(0, sky.field().get(0, 8, 0), "the roof itself blocks");
    }

    @Test
    void theAttenuationHelperIsPackageVisibleForExactlyThisReason() {
        assertEquals(14, LightEngine.attenuatedLevel(15, 0));
        assertEquals(14, LightEngine.attenuatedLevel(15, 1));
        assertEquals(12, LightEngine.attenuatedLevel(15, 3));
        assertEquals(1, LightEngine.attenuatedLevel(15, 14));
        assertEquals(0, LightEngine.attenuatedLevel(15, 15));
        assertEquals(1, LightEngine.attenuatedLevel(2, 15), "and it never goes negative");
        assertTrue(LightEngine.attenuatedLevel(0, 0) <= 0, "nothing propagates from a dark cell");
    }
}
