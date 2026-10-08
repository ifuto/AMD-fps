package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expectations here were produced by a separate re-implementation of the grouping and diffed before
 * committing. The first draft of that model had the merged-bounds min and max inverted and silently
 * produced one batch where there should be two, which is the kind of error that would have looked
 * perfectly reasonable in a test written from the same wrong mental model.
 */
class EntityBatcherTest {

    private static final float HALF_WIDTH = 0.25f;
    private static final float HEIGHT = 0.5f;

    private static EntityInstance item(float x, float y, float z) {
        return item(x, y, z, 1);
    }

    private static EntityInstance item(float x, float y, float z, int modelKey) {
        return new EntityInstance(modelKey, x, y, z, 0f, 0, HALF_WIDTH, HEIGHT);
    }

    private static EntityBatcher batcher() {
        return new EntityBatcher();
    }

    private static String shape(EntityBatchSet set) {
        StringBuilder sb = new StringBuilder();
        for (EntityBatch batch : set.batches()) {
            sb.append(batch.modelKey()).append('@').append(batch.firstInstance())
                    .append('x').append(batch.instanceCount()).append(' ');
        }
        return sb.toString().trim();
    }

    @Test
    void anEmptyBatcherProducesNothing() {
        EntityBatchSet set = batcher().build();
        assertEquals(0, set.instanceCount());
        assertEquals(0, set.drawCallCount());
        assertEquals(0, set.instanceDataBytes());
        assertTrue(set.isContiguous());
    }

    @Test
    void aPileOfOneModelIsOneDrawCall() {
        // The case this exists for: a hundred dropped items in a hopper farm.
        EntityBatcher batcher = batcher();
        for (int i = 0; i < 100; i++) {
            batcher.add(item(i * 0.1f, 64f, 0f));
        }
        EntityBatchSet set = batcher.build();
        assertEquals(100, set.instanceCount());
        assertEquals(1, set.drawCallCount(), "one model, one call");
        assertEquals(100, set.batches().get(0).instanceCount());
        assertTrue(set.isContiguous());
    }

    @Test
    void differentModelsNeverShareABatch() {
        EntityBatcher batcher = batcher();
        batcher.add(item(0f, 64f, 0f, 1));
        batcher.add(item(0.5f, 64f, 0f, 2));
        batcher.add(item(1f, 64f, 0f, 1));

        EntityBatchSet set = batcher.build();
        assertEquals("1@0x2 2@2x1", shape(set));
        // Instances are reordered so each batch is contiguous, which is what lets one draw read a
        // straight run of the buffer.
        assertEquals(1, set.instances().get(0).modelKey());
        assertEquals(1, set.instances().get(1).modelKey());
        assertEquals(2, set.instances().get(2).modelKey());
    }

    @Test
    void theMergedBoundsEncloseEveryInstance() {
        EntityBatcher batcher = batcher();
        batcher.add(item(0f, 64f, 0f));
        batcher.add(item(1f, 64f, 0f));
        batcher.add(item(2f, 64f, 0.5f));

        EntityBatch batch = batcher.build().batches().get(0);
        assertEquals(-0.25f, batch.minX(), 1e-6f);
        assertEquals(2.25f, batch.maxX(), 1e-6f);
        assertEquals(64f, batch.minY(), 1e-6f);
        assertEquals(64.5f, batch.maxY(), 1e-6f);
        assertEquals(-0.25f, batch.minZ(), 1e-6f);
        assertEquals(0.75f, batch.maxZ(), 1e-6f);
        assertEquals(2.5f, batch.spanX(), 1e-6f);
        assertEquals(0.5f, batch.spanY(), 1e-6f);
        assertEquals(1.0f, batch.spanZ(), 1e-6f);
    }

    @Test
    void aBatchStopsGrowingWhenItsBoundsWouldStretchTooFar() {
        // maxSpan 2 with instances 1 block apart: two fit, the third does not, and the third then
        // seeds a batch the fourth can join.
        EntityBatcher batcher = new EntityBatcher(1024, 2.0f, 8);
        for (int i = 0; i < 4; i++) {
            batcher.add(item(i, 64f, 0f));
        }
        EntityBatchSet set = batcher.build();
        assertEquals("1@0x2 1@2x2", shape(set));
    }

    @Test
    void aBatchStopsGrowingWhenItIsFull() {
        EntityBatcher batcher = new EntityBatcher(2, 1000f, 8);
        for (int i = 0; i < 5; i++) {
            batcher.add(item(i * 0.1f, 64f, 0f));
        }
        assertEquals("1@0x2 1@2x2 1@4x1", shape(batcher.build()));
    }

    @Test
    void theSearchWindowBoundsHowFarBackAnInstanceLooks() {
        // Two clusters visited alternately. With a window of one, the third instance cannot see the
        // first batch and opens a third; with a window of two it finds it and joins.
        List<EntityInstance> alternating = List.of(item(0f, 64f, 0f), item(50f, 64f, 0f),
                item(0.5f, 64f, 0f));

        EntityBatcher narrow = new EntityBatcher(1024, 2.0f, 1);
        narrow.addAll(alternating);
        assertEquals("1@0x1 1@1x1 1@2x1", shape(narrow.build()));

        EntityBatcher wide = new EntityBatcher(1024, 2.0f, 2);
        wide.addAll(alternating);
        assertEquals("1@0x2 1@2x1", shape(wide.build()));
    }

    @Test
    void theResultIsDeterministicForTheSameInput() {
        List<EntityInstance> input = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            input.add(item((i % 7) * 0.3f, 64f, (i / 7) * 0.3f, 1 + (i % 3)));
        }
        EntityBatcher first = batcher();
        first.addAll(input);
        EntityBatcher second = batcher();
        second.addAll(input);

        assertEquals(shape(first.build()), shape(second.build()));
        assertEquals(first.build().instances(), second.build().instances());
    }

    @Test
    void aBatcherCanBeReusedAfterClear() {
        EntityBatcher batcher = batcher();
        batcher.add(item(0f, 64f, 0f));
        assertEquals(1, batcher.instanceCount());
        batcher.clear();
        assertEquals(0, batcher.instanceCount());
        assertEquals(0, batcher.build().drawCallCount());

        batcher.add(item(1f, 64f, 1f));
        EntityBatchSet rebuilt = batcher.build();
        assertEquals(1, rebuilt.instanceCount());
        assertEquals(1f - HALF_WIDTH, rebuilt.batches().get(0).minX(), 1e-6f);
    }

    @Test
    void theConstructorRejectsNonsenseLimits() {
        assertThrows(IllegalArgumentException.class, () -> new EntityBatcher(0, 1f, 1));
        assertThrows(IllegalArgumentException.class, () -> new EntityBatcher(1, 0f, 1));
        assertThrows(IllegalArgumentException.class, () -> new EntityBatcher(1, 1f, 0));
    }

    @Test
    void anInstanceMustHaveASensibleShape() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityInstance(1, 0f, 0f, 0f, 0f, 0, -1f, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityInstance(1, 0f, 0f, 0f, 0f, 0, 1f, -1f));
    }

    @Test
    void anInstanceBoxUsesItsFeetAsTheFloor() {
        EntityInstance instance = item(3f, 64f, 7f);
        assertEquals(2.75f, instance.minX(), 1e-6f);
        assertEquals(3.25f, instance.maxX(), 1e-6f);
        assertEquals(64f, instance.minY(), 1e-6f, "y is the feet, not the centre");
        assertEquals(64.5f, instance.maxY(), 1e-6f);
        assertEquals(6.75f, instance.minZ(), 1e-6f);
        assertEquals(7.25f, instance.maxZ(), 1e-6f);
    }

    @Test
    void anEmptyBatchIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBatch(1, 0, 0, 0f, 0f, 0f, 1f, 1f, 1f));
    }

    @Test
    void invertedBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBatch(1, 0, 1, 5f, 0f, 0f, 1f, 1f, 1f));
    }

    @Test
    void theIntersectionTestIsConservative() {
        EntityBatch batch = new EntityBatch(1, 0, 1, 0f, 0f, 0f, 2f, 2f, 2f);
        assertTrue(batch.intersects(1f, 1f, 1f, 3f, 3f, 3f), "overlapping");
        assertTrue(batch.intersects(2f, 2f, 2f, 4f, 4f, 4f), "touching counts as intersecting");
        assertFalse(batch.intersects(3f, 0f, 0f, 4f, 1f, 1f), "clearly to the side");
        assertFalse(batch.intersects(0f, 5f, 0f, 1f, 6f, 1f), "clearly above");
    }

    @Test
    void theInstanceDataOffsetFollowsTheLayoutStride() {
        EntityBatch batch = new EntityBatch(1, 7, 3, 0f, 0f, 0f, 1f, 1f, 1f);
        assertEquals(7L * EntityGpuLayout.INSTANCE_BYTES, batch.instanceDataOffset());
    }

    @Test
    void theBatchedInstanceDataSizeFollowsTheInstanceCount() {
        EntityBatcher batcher = batcher();
        for (int i = 0; i < 10; i++) {
            batcher.add(item(i * 0.1f, 64f, 0f));
        }
        assertEquals(10 * EntityGpuLayout.INSTANCE_BYTES, batcher.build().instanceDataBytes());
    }
}
