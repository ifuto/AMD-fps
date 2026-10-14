package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDeduplicatorTest {

    private static float[] at(float x, float y, float z) {
        // Identity rotation, translation only, which is what most repeated geometry uses.
        return new float[] {
            1f, 0f, 0f, x,
            0f, 1f, 0f, y,
            0f, 0f, 1f, z,
        };
    }

    @Test
    void repeatedDrawsBecomeOneGroup() {
        InstanceDeduplicator dedup = new InstanceDeduplicator();
        long key = InstanceDeduplicator.groupKey(7, 2, 100, 0);

        int group = -1;
        for (int i = 0; i < 200; i++) {
            group = dedup.add(key, at(i * 2f, 64f, i * 3f));
        }

        assertEquals(200L, dedup.draws());
        assertEquals(1L, dedup.groupCount());
        assertEquals(1, dedup.drawCallsNeeded());
        assertEquals(200, dedup.instanceCount(group), "every draw joined the same group");
        assertEquals(199, dedup.instancedDraws());
        assertEquals(99.5, dedup.drawReductionPercent(), 0.01);
    }

    @Test
    void aDifferentTextureIsADifferentDraw() {
        // The mistake this class exists to prevent. Grouping on what looks similar rather than on what
        // the instance record covers would draw every one of them with whichever texture came first,
        // which is visible rather than merely slow.
        InstanceDeduplicator dedup = new InstanceDeduplicator();

        int first = dedup.add(InstanceDeduplicator.groupKey(7, 2, 100, 0), at(0f, 0f, 0f));
        int other = dedup.add(InstanceDeduplicator.groupKey(7, 2, 101, 0), at(1f, 0f, 0f));
        dedup.add(InstanceDeduplicator.groupKey(7, 2, 100, 0), at(2f, 0f, 0f));

        assertEquals(2, dedup.drawCallsNeeded());
        assertEquals(2L, dedup.groupCount());
        assertNotEquals(first, other, "different textures land in different slots");
        assertEquals(2, dedup.instanceCount(first));
        assertEquals(1, dedup.instanceCount(other));
    }

    @Test
    void aDifferentAnimationStateIsADifferentDraw() {
        // Two frames of the same animation are different draws. Merging them would freeze every
        // instance on whichever frame was added first.
        InstanceDeduplicator dedup = new InstanceDeduplicator();

        for (int frame = 0; frame < 8; frame++) {
            for (int i = 0; i < 10; i++) {
                dedup.add(InstanceDeduplicator.groupKey(3, 0, 5, frame), at(i, frame, 0));
            }
        }

        assertEquals(8, dedup.drawCallsNeeded(), "one draw per animation frame");
        assertEquals(80L, dedup.draws());
        assertEquals(72, dedup.instancedDraws());
    }

    @Test
    void everyFieldInTheKeySeparatesGroups() {
        InstanceDeduplicator dedup = new InstanceDeduplicator();

        dedup.add(InstanceDeduplicator.groupKey(1, 1, 1, 1), at(0, 0, 0));
        dedup.add(InstanceDeduplicator.groupKey(2, 1, 1, 1), at(0, 0, 0));
        dedup.add(InstanceDeduplicator.groupKey(1, 2, 1, 1), at(0, 0, 0));
        dedup.add(InstanceDeduplicator.groupKey(1, 1, 2, 1), at(0, 0, 0));
        dedup.add(InstanceDeduplicator.groupKey(1, 1, 1, 2), at(0, 0, 0));

        assertEquals(5, dedup.drawCallsNeeded(), "each field on its own makes a new group");
    }

    @Test
    void transformsArePreservedExactlyAndInOrder() {
        // A transform that is copied wrongly puts every instance in the wrong place, which is the
        // visible failure mode of getting the grouping right and the data wrong.
        InstanceDeduplicator dedup = new InstanceDeduplicator();
        long key = InstanceDeduplicator.groupKey(4, 1, 9, 0);

        float[][] sent = new float[50][];
        int group = -1;
        for (int i = 0; i < 50; i++) {
            sent[i] = at(i * 1.5f, 64f + i, -i * 0.25f);
            group = dedup.add(key, sent[i]);
        }

        float[] out = new float[50 * 12];
        int written = dedup.copyInstances(group, out);
        assertEquals(600, written);

        for (int i = 0; i < 50; i++) {
            float[] one = new float[12];
            System.arraycopy(out, i * 12, one, 0, 12);
            assertArrayEquals(sent[i], one, 0.0f, "instance " + i);
        }
    }

    @Test
    void aSingletonIsNotWorthInstancing() {
        // Instancing one draw costs an upload and a bind to save nothing, and in a scene of mostly
        // unique geometry that is most of the groups.
        InstanceDeduplicator dedup = new InstanceDeduplicator();

        for (int i = 0; i < 30; i++) {
            dedup.add(InstanceDeduplicator.groupKey(i, 0, 0, 0), at(i, 0, 0));
        }

        assertEquals(30, dedup.singletonGroups());
        for (int i = 0; i < 30; i++) {
            assertFalse(dedup.isWorthInstancing(i), "group " + i);
        }
        assertEquals(30, dedup.drawCallsNeeded());
        assertEquals(0, dedup.instancedDraws());
        assertEquals(0.0, dedup.drawReductionPercent());
    }

    @Test
    void aMixedSceneReducesMostOfItsDraws() {
        // The shape of a real frame: a few hundred chests, a thousand items, four thousand particles,
        // and some unique geometry.
        InstanceDeduplicator dedup = new InstanceDeduplicator(1024);

        for (int i = 0; i < 200; i++) {
            dedup.add(InstanceDeduplicator.groupKey(1, 0, 10, 0), at(i, 64, 0));
        }
        for (int i = 0; i < 1000; i++) {
            dedup.add(InstanceDeduplicator.groupKey(2, 0, 11, 0), at(i, 65, 0));
        }
        for (int i = 0; i < 4000; i++) {
            dedup.add(InstanceDeduplicator.groupKey(3, 1, 12, 0), at(i, 66, 0));
        }
        for (int i = 0; i < 300; i++) {
            dedup.add(InstanceDeduplicator.groupKey(100 + i, 0, 20, 0), at(i, 67, 0));
        }

        assertEquals(5500L, dedup.draws());
        assertEquals(303, dedup.drawCallsNeeded(), "three instanced groups plus 300 unique");
        assertEquals(300, dedup.singletonGroups());
        assertEquals(5197, dedup.instancedDraws());
        assertEquals(94.5, dedup.drawReductionPercent(), 0.1);
        assertEquals(32, InstanceDeduplicator.INSTANCE_BYTES);
    }

    @Test
    void clearStartsANewFrameButKeepsTheSlots() {
        InstanceDeduplicator dedup = new InstanceDeduplicator();
        long key = InstanceDeduplicator.groupKey(5, 0, 1, 0);
        int first = dedup.add(key, at(0, 0, 0));
        dedup.add(key, at(1, 0, 0));
        assertEquals(2, dedup.instanceCount(first));

        dedup.clear();
        assertEquals(0, dedup.drawCallsNeeded(), "no group holds anything");
        assertEquals(0, dedup.instanceCount(first), "the count was reset");

        int again = dedup.add(key, at(5f, 0f, 0f));
        assertEquals(first, again, "and the same key lands in the same slot");
        assertEquals(1, dedup.instanceCount(again));
    }

    @Test
    void groupKeysDoNotCollide() {
        java.util.Set<Long> keys = new java.util.HashSet<>();
        long expected = 0;
        for (int mesh = 0; mesh < 200; mesh++) {
            for (int material = 0; material < 8; material++) {
                for (int texture = 0; texture < 40; texture++) {
                    for (int anim = 0; anim < 4; anim++) {
                        keys.add(InstanceDeduplicator.groupKey(mesh, material, texture, anim));
                        expected++;
                    }
                }
            }
        }
        assertEquals(expected, keys.size(), "256,000 combinations, no collisions");
        assertNotEquals(InstanceDeduplicator.groupKey(0, 0, 1, 0),
                InstanceDeduplicator.groupKey(0, 0, 0, 1),
                "texture and animation occupy different bits");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        InstanceDeduplicator dedup = new InstanceDeduplicator();

        assertThrows(IllegalArgumentException.class,
                () -> dedup.add(0L, new float[11]), "wrong transform length");
        assertThrows(IllegalArgumentException.class,
                () -> dedup.add(0L, new float[] {
                    1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, Float.NaN}));
        assertThrows(IllegalArgumentException.class,
                () -> dedup.add(0L, new float[] {
                    1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, Float.POSITIVE_INFINITY}));
        assertThrows(IllegalArgumentException.class,
                () -> InstanceDeduplicator.groupKey(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> InstanceDeduplicator.groupKey(70000, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> InstanceDeduplicator.groupKey(0, 300, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> InstanceDeduplicator.groupKey(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new InstanceDeduplicator(8));

        int group = dedup.add(0L, at(0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> dedup.copyInstances(group, new float[11]), "destination too small");
        assertThrows(IndexOutOfBoundsException.class, () -> dedup.instanceCount(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> dedup.isWorthInstancing(999));
    }

    @Test
    void aFreshDeduplicatorReportsZeroNotNaN() {
        InstanceDeduplicator dedup = new InstanceDeduplicator();
        assertEquals(0.0, dedup.drawReductionPercent());
        assertEquals(0L, dedup.draws());
        assertEquals(0, dedup.drawCallsNeeded());
        assertEquals(12, InstanceDeduplicator.FLOATS_PER_INSTANCE);
        assertTrue(InstanceDeduplicator.MAX_INSTANCES_PER_GROUP > 0);
    }
}
