package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property under test is that the draw count depends on where the entities are and not on the
 * order they were handed over. Minecraft's entity list is not in spatial order and is not stable
 * across frames, so a batcher whose output depends on that order produces a frame cost that varies
 * for no reason the player can see.
 *
 * <p>The counts below were reproduced by a separate implementation of both batchers before being
 * written down.
 */
class SpatialEntityBatcherTest {

    /** 20 clusters, one per cell, 64 blocks apart, 50 entities each. */
    private static final int CLUSTERS = 20;
    private static final int PER_CLUSTER = 50;

    private static EntityBuffer clusters(boolean roundRobin) {
        EntityBuffer buffer = new EntityBuffer();
        if (roundRobin) {
            for (int round = 0; round < PER_CLUSTER; round++) {
                for (int cluster = 0; cluster < CLUSTERS; cluster++) {
                    add(buffer, cluster, round);
                }
            }
        } else {
            for (int cluster = 0; cluster < CLUSTERS; cluster++) {
                for (int round = 0; round < PER_CLUSTER; round++) {
                    add(buffer, cluster, round);
                }
            }
        }
        return buffer;
    }

    private static void add(EntityBuffer buffer, int cluster, int round) {
        buffer.add(1, cluster * 64f + (round % 4) * 0.5f, 64f,
                cluster * 64f + (round % 3) * 0.5f, 0f, 0, 0.25f, 0.5f);
    }

    @Test
    void aPileInsideOneCellIsOneDraw() {
        EntityBuffer buffer = new EntityBuffer();
        for (int i = 0; i < 100; i++) {
            buffer.add(1, 4f + (i % 8) * 0.5f, 64f, 4f + (i / 8) * 0.5f, 0f, 0, 0.25f, 0.5f);
        }
        SpatialBatchSet set = new SpatialEntityBatcher().build(buffer);
        assertEquals(100, set.instanceCount());
        assertEquals(1, set.drawCallCount(), "one model in one cell is one call");
        assertTrue(set.isContiguous());
    }

    @Test
    void twoModelsInOneCellAreTwoDraws() {
        // One instanced draw binds one model and one texture, so this is not a choice.
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 4f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        buffer.add(2, 4.5f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        SpatialBatchSet set = new SpatialEntityBatcher().build(buffer);
        assertEquals(2, set.drawCallCount());
        assertEquals(1, set.instanceCount() / 2);
    }

    @Test
    void theSameModelInTwoCellsIsTwoDraws() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 4f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        buffer.add(1, 40f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        assertEquals(2, new SpatialEntityBatcher().build(buffer).drawCallCount());
    }

    @Test
    void arrivalOrderDoesNotChangeTheBatchCount() {
        SpatialEntityBatcher batcher = new SpatialEntityBatcher();
        int grouped = batcher.build(clusters(false)).drawCallCount();
        int roundRobin = batcher.build(clusters(true)).drawCallCount();

        EntityBuffer shuffled = clusters(false);
        List<EntityInstance> list = new ArrayList<>();
        for (int i = 0; i < shuffled.size(); i++) {
            list.add(shuffled.instance(i));
        }
        Collections.shuffle(list, new Random(3));
        EntityBuffer reshuffled = new EntityBuffer();
        for (EntityInstance instance : list) {
            reshuffled.add(instance);
        }
        int afterShuffle = batcher.build(reshuffled).drawCallCount();

        assertEquals(grouped, roundRobin, "round-robin arrival must not cost more draws");
        assertEquals(grouped, afterShuffle, "shuffled arrival must not cost more draws");
        assertEquals(CLUSTERS, grouped, "one batch per occupied cell");
    }

    @Test
    void theGreedyBatcherDoesDependOnArrivalOrderWhichIsWhyThisOneExists() {
        // The contrast, pinned so that a future "simplification" back to greedy grouping fails here
        // rather than showing up as unexplained frame time spikes.
        EntityBatcher greedy = new EntityBatcher();
        greedy.addAll(toList(clusters(false)));
        int grouped = greedy.build().drawCallCount();

        EntityBatcher greedyInterleaved = new EntityBatcher();
        greedyInterleaved.addAll(toList(clusters(true)));
        int roundRobin = greedyInterleaved.build().drawCallCount();

        assertEquals(CLUSTERS, grouped, "grouped arrival is the greedy batcher's good case");
        assertTrue(roundRobin > grouped * 5,
                "interleaved arrival should fragment the greedy batches: " + roundRobin
                        + " vs " + grouped);
    }

    private static List<EntityInstance> toList(EntityBuffer buffer) {
        List<EntityInstance> list = new ArrayList<>(buffer.size());
        for (int i = 0; i < buffer.size(); i++) {
            list.add(buffer.instance(i));
        }
        return list;
    }

    @Test
    void theMergedBoundsEncloseEveryInstance() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 4f, 64f, 4f, 0f, 0, 0.25f, 1.8f);
        buffer.add(1, 6f, 65f, 5f, 0f, 0, 0.5f, 0.5f);
        EntityBatch batch = new SpatialEntityBatcher().build(buffer).batch(0);
        assertEquals(3.75f, batch.minX(), 0f);
        assertEquals(6.5f, batch.maxX(), 0f);
        assertEquals(64f, batch.minY(), 0f);
        // 65.8, from the first entity: its feet are at 64 and it is 1.8 tall. The second entity's
        // top is at 65.5, so it does not reach as high even though it stands a block higher.
        assertEquals(65.8f, batch.maxY(), 1e-5f);
        assertEquals(3.75f, batch.minZ(), 0f);
        assertEquals(5.5f, batch.maxZ(), 0f);
    }

    @Test
    void aCellHoldingMoreThanOneDrawsWorthSplitsIntoTwoBatches() {
        EntityBuffer buffer = new EntityBuffer();
        for (int i = 0; i < 10; i++) {
            buffer.add(1, 4f + i * 0.1f, 64f, 4f, 0f, 0, 0.05f, 0.5f);
        }
        SpatialBatchSet set = new SpatialEntityBatcher(4).build(buffer);
        assertEquals(3, set.drawCallCount(), "10 instances at 4 per draw");
        assertEquals(10, set.instanceCount());
        assertTrue(set.isContiguous());
        assertEquals("1@4 1@4 1@2", set.shape());
    }

    @Test
    void aSplitBatchDoesNotInheritTheBoundsOfTheOneBeforeIt() {
        // Inheriting would carry the first batch's extent forward and the merged box would grow with
        // every split, until the frustum test could never reject it -- which throws away the other
        // half of what batching saves.
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 4.0f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        buffer.add(1, 4.2f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        buffer.add(1, 8.0f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        SpatialBatchSet set = new SpatialEntityBatcher(2).build(buffer);
        assertEquals(2, set.drawCallCount());
        EntityBatch second = set.batch(1);
        assertEquals(7.75f, second.minX(), 0f, "the second batch starts at its own first instance");
        assertEquals(8.25f, second.maxX(), 0f);
    }

    @Test
    void batchingACulledSetOnlySeesTheSurvivors() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 4f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        buffer.add(1, 5000f, 64f, 4f, 0f, 0, 0.25f, 0.5f);

        EntityCuller culler = new EntityCuller();
        culler.cull(buffer, null, 0f, 64f, 0f);
        SpatialBatchSet set = new SpatialEntityBatcher().build(buffer, culler);

        assertEquals(1, set.instanceCount(), "the entity 5000 blocks away is not batched");
        assertEquals(1, set.drawCallCount());
        assertEquals(0, set.instanceIndex(0), "and the survivor is the near one");
    }

    @Test
    void aWorldFullOfUnseenEntitiesCostsNothingToBatch() {
        // The case the user asked about: the cost has to follow what is visible, not what exists.
        EntityBuffer buffer = new EntityBuffer();
        int total = 50000;
        for (int i = 0; i < total; i++) {
            buffer.add(i % 5, 200f + (i % 200) * 1.5f, 64f, 200f + (i / 200) * 1.5f, 0f, 0,
                    0.25f, 0.5f);
        }
        buffer.add(1, 4f, 64f, 4f, 0f, 0, 0.25f, 0.5f);

        EntityCuller culler = new EntityCuller();
        culler.cull(buffer, null, 0f, 64f, 0f);
        SpatialBatchSet set = new SpatialEntityBatcher().build(buffer, culler);

        assertEquals(total + 1, culler.tested());
        assertEquals(1, set.instanceCount(), "only the entity near the camera survives");
        assertEquals(1, set.drawCallCount());
        assertEquals(total, culler.culledByDistance());
    }

    @Test
    void anEmptyBufferProducesNoDraws() {
        SpatialBatchSet set = new SpatialEntityBatcher().build(new EntityBuffer());
        assertEquals(0, set.instanceCount());
        assertEquals(0, set.drawCallCount());
        assertEquals(0, set.instanceDataBytes());
        assertTrue(set.isContiguous());
        assertEquals("", set.shape());
    }

    @Test
    void aBatcherCanBeReusedAcrossFrames() {
        SpatialEntityBatcher batcher = new SpatialEntityBatcher();
        EntityBuffer first = new EntityBuffer();
        first.add(1, 4f, 64f, 4f, 0f, 0, 0.25f, 0.5f);
        assertEquals(1, batcher.build(first).drawCallCount());

        EntityBuffer second = new EntityBuffer();
        second.add(2, 40f, 64f, 40f, 0f, 0, 0.25f, 0.5f);
        SpatialBatchSet rebuilt = batcher.build(second);
        assertEquals(1, rebuilt.drawCallCount(), "the previous frame's batches are gone");
        assertEquals(2, rebuilt.batch(0).modelKey());
        assertEquals(1, rebuilt.instanceCount());
    }

    @Test
    void theConstructorRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new SpatialEntityBatcher(0));
        assertThrows(IllegalArgumentException.class, () -> new SpatialEntityBatcher(-1));
    }
}
