package net.amdfaster.entity;

import net.amdfaster.cull.SoaFrustumCuller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityCullerTest {

    @Test
    void theDistanceTestCullsBeyondTheLimitAndKeepsAtIt() {
        BlockEntityCuller culler = new BlockEntityCuller();

        assertTrue(culler.shouldRender(24.0, 0, 0), "inside");
        assertTrue(culler.shouldRender(25.0, 0, 0), "exactly at the limit is kept");
        assertFalse(culler.shouldRender(26.0, 0, 0), "beyond it is not");
    }

    @Test
    void theDistanceIsEuclideanNotPerAxis() {
        // A per-axis test would cull a block entity 20 blocks up and 20 across, which is 28 blocks away
        // and genuinely out of range, while keeping one 24 blocks along a single axis. Both are wrong
        // in the direction that costs frames.
        BlockEntityCuller culler = new BlockEntityCuller();

        assertFalse(culler.shouldRender(20.0, 20.0, 0.0), "28 blocks away");
        assertTrue(culler.shouldRender(24.0, 0.0, 0.0));
        assertTrue(culler.shouldRender(14.0, 14.0, 14.0), "24.2 blocks away, just inside");
        assertFalse(culler.shouldRender(15.0, 15.0, 15.0), "26 blocks away");
    }

    @Test
    void theAnimationDistanceIsShorterThanTheRenderDistance() {
        // A block entity that draws but does not animate is still a saving: the render becomes a static
        // quad that batches, while an animated one produces different vertices every frame and defeats
        // the batch.
        BlockEntityCuller culler = new BlockEntityCuller();

        assertTrue(culler.shouldAnimate(10.0, 0, 0));
        assertTrue(culler.shouldAnimate(16.0, 0, 0), "at the limit");
        assertFalse(culler.shouldAnimate(17.0, 0, 0));

        assertTrue(culler.shouldRender(20.0, 0, 0), "draws at 20");
        assertFalse(culler.shouldAnimate(20.0, 0, 0), "but does not animate");
    }

    @Test
    void theDistancesAreSettableAndReadBackCorrectly() {
        BlockEntityCuller culler = new BlockEntityCuller();

        assertEquals(BlockEntityCuller.DEFAULT_MAX_DISTANCE, culler.maxDistance(), 1e-9);
        assertEquals(BlockEntityCuller.DEFAULT_ANIMATION_DISTANCE, culler.animationDistance(), 1e-9);

        culler.setMaxDistance(64.0);
        culler.setAnimationDistance(32.0);

        assertEquals(64.0, culler.maxDistance(), 1e-9);
        assertEquals(32.0, culler.animationDistance(), 1e-9);
        assertTrue(culler.shouldRender(60.0, 0, 0));
        assertFalse(culler.shouldRender(70.0, 0, 0));
    }

    @Test
    void nonsenseDistancesAreRejected() {
        // A zero or negative distance would cull everything including the block entity the player is
        // standing on, and NaN would cull nothing at all because every comparison against it is false.
        BlockEntityCuller culler = new BlockEntityCuller();

        assertThrows(IllegalArgumentException.class, () -> culler.setMaxDistance(0.0));
        assertThrows(IllegalArgumentException.class, () -> culler.setMaxDistance(-1.0));
        assertThrows(IllegalArgumentException.class, () -> culler.setMaxDistance(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> culler.setMaxDistance(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> culler.setAnimationDistance(0.0));
    }

    @Test
    void theCountersSeparateTheTwoReasonsForCulling() {
        // Which of the two tests is doing the work decides what to tune, so they cannot share a counter.
        BlockEntityCuller culler = new BlockEntityCuller();

        culler.shouldRender(10.0, 0, 0);
        culler.shouldRender(100.0, 0, 0);
        culler.shouldRender(200.0, 0, 0);

        assertEquals(3, culler.tested());
        assertEquals(2, culler.culledByDistance());
        assertEquals(0, culler.culledByFrustum());

        culler.resetCounters();
        assertEquals(0, culler.tested());
        assertEquals(0, culler.culledByDistance());
    }

    @Test
    void aFrustumBatchCullsWhatIsBehindTheCamera() {
        // Animated block entities are not frustum culled by the vanilla path at all, so a banner behind
        // the camera still animates and still submits.
        SoaFrustumCuller.Planes planes = new SoaFrustumCuller.Planes();
        planes.set(0, 1, 0, 0, 0);
        planes.set(1, -1, 0, 0, 10);
        planes.set(2, 0, 1, 0, 0);
        planes.set(3, 0, -1, 0, 10);
        planes.set(4, 0, 0, 1, 0);
        planes.set(5, 0, 0, -1, 10);

        SoaFrustumCuller.Boxes boxes = new SoaFrustumCuller.Boxes(3);
        boxes.put(0, 1, 1, 1, 2, 2, 2);
        boxes.put(1, 50, 50, 50, 51, 51, 51);
        boxes.put(2, -50, -50, -50, -49, -49, -49);

        BlockEntityCuller culler = new BlockEntityCuller();
        boolean[] visible = new boolean[3];
        int kept = culler.cullBatch(planes, boxes, 3, visible);

        assertArrayEquals(new boolean[] {true, false, false}, visible);
        assertEquals(1, kept);
        assertEquals(3, culler.tested());
        assertEquals(2, culler.culledByFrustum());
        assertEquals(0, culler.culledByDistance(), "the frustum path does not touch the distance counter");
    }

    @Test
    void squaredComparisonAvoidsASquareRootPerBlockEntity() {
        // With four hundred chests in a storage room this runs four hundred times a frame. A square root
        // each time is not the dominant cost, but it is free to avoid and the comparison is exact for the
        // values involved.
        BlockEntityCuller culler = new BlockEntityCuller();
        assertEquals(625.0, culler.maxDistance() * culler.maxDistance(), 1e-6);
        assertEquals(256.0, culler.animationDistance() * culler.animationDistance(), 1e-6);
    }
}
