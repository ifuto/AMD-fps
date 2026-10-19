package net.amdfaster.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.cull.CameraCutDetector;
import org.junit.jupiter.api.Test;

/**
 * The frame gather, the passes and the answer, without Minecraft anywhere near it.
 *
 * <p>This is the point of keeping the logic out of the mixin: the mixin reads a camera and a level and
 * calls three methods, and everything that decides anything is here, where it can be run and checked. A
 * culler that removes the wrong entity is a hole in the world, so what it keeps and what it drops is the
 * behaviour worth pinning down.
 */
class EntityCullRuntimeTest {

    private static final float[] RIGHT = {1f, 0f, 0f};
    private static final float[] UP = {0f, 1f, 0f};
    private static final float[] FORWARD = {0f, 0f, 1f};

    /**
     * A near plane the size the game actually uses: 0.1 blocks away and about 79 degrees across, so its
     * half extents are 0.07 and 0.039. Using round numbers here instead would give a near-170-degree
     * frustum that culls nothing sideways, and the tests would pass while testing nothing.
     */
    private static final float NEAR = 0.1f;
    private static final float HALF_WIDTH = 0.07f;
    private static final float HALF_HEIGHT = 0.039375f;

    /** A camera at the origin facing +Z. */
    private static EntityCullRuntime frame(EntityWorldRuntime world, long frameId) {
        EntityCullRuntime cull = new EntityCullRuntime(world, new CameraCutDetector());
        cull.beginFrame(frameId, 0f, 0f, 0f, RIGHT, UP, FORWARD, HALF_WIDTH, HALF_HEIGHT, NEAR, 64f);
        return cull;
    }

    @Test
    void anEntityInFrontOfTheCameraSurvivesAndOneBehindItDoesNot() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 1L);
        cull.offer(11, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.offer(22, 0f, 0f, -10f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();

        assertEquals(2, cull.testedTotal());
        assertEquals(1, cull.survivorCount());
        assertTrue(cull.survived(11));
        assertFalse(cull.survived(22));
        assertTrue(cull.hasResult());
        assertEquals(1L, cull.frames());
        assertEquals(1L, cull.culledTotal());
    }

    @Test
    void anEntityPastTheDistanceLimitIsDropped() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 1L);
        // Inside the frustum, but further than the 64-block limit the frame was given.
        cull.offer(33, 0f, 0f, 200f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();
        assertFalse(cull.survived(33));
        assertEquals(0, cull.survivorCount());
    }

    @Test
    void theDistanceLimitIsWhateverTheFrameAskedFor() {
        // The limit follows the game's render distance rather than being a constant of the culler, so the
        // same entity is kept at 200 when the client is drawing that far and dropped when it is not.
        EntityWorldRuntime far = new EntityWorldRuntime();
        EntityCullRuntime cull = new EntityCullRuntime(far, new CameraCutDetector());
        cull.beginFrame(1L, 0f, 0f, 0f, RIGHT, UP, FORWARD, HALF_WIDTH, HALF_HEIGHT, NEAR, 512f);
        cull.offer(44, 0f, 0f, 200f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();
        assertTrue(cull.survived(44));
        assertEquals(512f, far.cullDistance(), 1e-6f);
    }

    @Test
    void aSecondBeginForTheSameFrameDoesNotRunThePassAgain() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 7L);
        cull.offer(55, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();
        // The game asks about every entity in the frame, and only the first of those questions may
        // trigger the pass. Running it per entity would cost more than the drawing it saves.
        cull.beginFrame(7L, 0f, 0f, 0f, RIGHT, UP, FORWARD, HALF_WIDTH, HALF_HEIGHT, NEAR, 64f);
        cull.finishFrame();
        assertEquals(1L, cull.frames());
        assertTrue(cull.survived(55), "the frame already gathered must still answer");
    }

    @Test
    void aNewFrameIdRunsThePassAgainAndReplacesTheAnswer() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 1L);
        cull.offer(66, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();
        assertTrue(cull.survived(66));

        cull.beginFrame(2L, 0f, 0f, 0f, RIGHT, UP, FORWARD, HALF_WIDTH, HALF_HEIGHT, NEAR, 64f);
        cull.offer(77, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();
        assertFalse(cull.survived(66), "last frame's entity is not this frame's");
        assertTrue(cull.survived(77));
        assertEquals(2L, cull.frames());
    }

    @Test
    void aWorldChangeDropsTheResultRatherThanAnsweringFromIt() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 1L);
        cull.offer(88, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();
        assertTrue(cull.survived(88));

        cull.onWorldChange();
        assertFalse(cull.hasResult(), "a dimension's entities say nothing about the next one");
        assertFalse(cull.survived(88));
        assertEquals(0, cull.survivorCount());
    }

    @Test
    void thePassRunsAgainOnceTheFrameTokenStopsMoving() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 1L);
        cull.offer(99, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();

        assertFalse(cull.shouldBegin(1L), "the pass just ran for this frame");
        assertTrue(cull.shouldBegin(2L), "a new frame always runs it");
        // A mod that replaces the render path would stop moving the token, and answering from a set
        // computed an arbitrary time ago is culling against a camera the player left.
        cull.onWorldChange();
        assertTrue(cull.shouldBegin(1L), "nothing has ever been gathered");
    }

    @Test
    void manySurvivorsGrowTheSetWithoutLosingAny() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 1L);
        // 41 by 25, all inside the frustum at these depths, which is past the table's first size of 1024
        // and forces it to grow. At z = 6 the frustum is 4.2 blocks half-wide and 2.4 high, so the grid
        // sits inside it with room to spare.
        int offered = 0;
        for (int ix = 0; ix < 41; ix++) {
            for (int iz = 0; iz < 25; iz++) {
                cull.offer(1000 + offered, (ix - 20) * 0.1f, 0f, 6f + iz * 0.4f, 0f, 0, 0.3f, 1.8f);
                offered++;
            }
        }
        cull.finishFrame();
        assertEquals(offered, cull.survivorCount());
        // Spot checks at both ends and in the middle: an open-addressed table that loses entries on growth
        // loses them somewhere specific, so the ends are where it shows up.
        assertTrue(cull.survived(1000));
        assertTrue(cull.survived(1000 + offered - 1));
        assertTrue(cull.survived(1000 + offered / 2));
        assertFalse(cull.survived(999), "an id that was never offered");
        assertFalse(cull.survived(1000 + offered), "nor one just past the end");
    }

    @Test
    void theSameEntityOfferedTwiceIsCountedOnce() {
        EntityCullRuntime cull = frame(new EntityWorldRuntime(), 1L);
        cull.offer(123, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.offer(123, 0f, 0f, 10f, 0f, 0, 0.3f, 1.8f);
        cull.finishFrame();
        // Two entries in the buffer, both surviving, but one entity -- and a set that counted it twice
        // would report a survivor count the draw path could not reconcile with the world.
        assertEquals(1, cull.survivorCount());
        assertTrue(cull.survived(123));
    }
}
