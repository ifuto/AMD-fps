package net.amdfaster.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleFieldTest {

    private static final ParticleField.VisibilityTest ALL = (x, y, z, r) -> true;
    private static final ParticleField.VisibilityTest NONE = (x, y, z, r) -> false;

    @Test
    void spawningFillsTheStructureOfArrays() {
        ParticleField field = new ParticleField(64);
        assertTrue(field.spawn(1.0f, 64.0f, 2.0f, 0.5f, 1.0f, -0.5f, 2.0f, 0.25f, 7));
        assertEquals(1, field.count(), "one particle live");
        assertEquals(1, field.spawned(), "and one recorded as spawned");
        assertEquals(1.0f, field.xOf(0), 1e-6f, "x");
        assertEquals(64.0f, field.yOf(0), 1e-6f, "y");
        assertEquals(7, field.spriteOf(0), "sprite id survives");
        assertEquals(0.25f, field.sizeOf(0), 1e-6f, "quad size survives");
        assertEquals(0.0f, field.progressOf(0), 1e-6f, "freshly spawned, no life elapsed");
    }

    @Test
    void aFullFieldDropsTheExcessRatherThanGrowing() {
        // Dropping is the correct answer for a particle. The alternative is an unbounded allocation or
        // evicting a live one, and an explosion that spawns past the budget should lose the excess
        // rather than cost frame time -- which is the difference between a big explosion being a little
        // less pretty and it being a hitch.
        ParticleField field = new ParticleField(3);
        for (int i = 0; i < 3; i++) {
            assertTrue(field.spawn(i, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.1f, 0), "slot " + i);
        }
        assertTrue(field.isFull(), "capacity reached");
        assertFalse(field.spawn(9.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.1f, 0), "the fourth is dropped");
        assertEquals(3, field.count(), "and the count did not move");
        assertEquals(3, field.spawned(), "nor did the spawn total, since nothing was added");
    }

    @Test
    void aNonPositiveLifetimeIsRefused() {
        ParticleField field = new ParticleField(8);
        assertFalse(field.spawn(0.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0),
                "a particle with no life would expire on its first update");
        assertFalse(field.spawn(0.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.1f, 0));
        assertEquals(0, field.count(), "and neither was stored");
    }

    @Test
    void integrationIsSemiImplicitAndMatchesAModel() {
        // Velocity first, then position from the new velocity. The explicit form loses energy and makes
        // particles fall visibly slower at low frame rates, which is exactly when it shows. Values from
        // a model of the same three statements.
        ParticleField field = new ParticleField(4);
        field.spawn(0.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 10.0f, 0.1f, 0);

        field.advance(0.05f, 9.81f);
        assertEquals(63.975500f, field.yOf(0), 1e-3f, "after one frame");

        field.advance(0.05f, 9.81f);
        assertEquals(63.926524f, field.yOf(0), 1e-3f, "after two");

        field.advance(0.05f, 9.81f);
        assertEquals(63.853097f, field.yOf(0), 1e-3f, "after three");
    }

    @Test
    void dragIsFrameRateIndependent() {
        // Drag is quoted per second, so it has to be raised to the frame's fraction of a second.
        // Applying the per-second factor once per frame instead makes particles slow down faster at
        // high frame rates -- a visible difference between a 60 Hz and a 240 Hz machine, and the kind
        // of bug that only shows up on someone else's hardware.
        float atSixty = velocityAfter(60, 1.0f / 60.0f);
        float atTwoForty = velocityAfter(240, 1.0f / 240.0f);

        assertEquals(-9.709935f, atSixty, 1e-3f, "60 Hz over one second");
        assertEquals(-9.711161f, atTwoForty, 1e-3f, "240 Hz over one second");
        assertTrue(Math.abs(atSixty - atTwoForty) < 0.01f,
                "the two frame rates agree to within a hundredth of a block per second");
    }

    private static float velocityAfter(int frames, float dt) {
        ParticleField field = new ParticleField(2);
        field.spawn(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1e6f, 0.1f, 0);
        for (int i = 0; i < frames; i++) {
            field.advance(dt, ParticleField.DEFAULT_GRAVITY);
        }
        // Read directly rather than recovering it from a position delta. The delta over one more frame
        // includes that frame's gravity, which is 0.1635 of a block per second at 60 Hz -- enough to
        // make the two frame rates look like they disagree when they do not.
        return field.velocityYOf(0);
    }

    @Test
    void expiredParticlesAreRemovedAndTheRestKeepTheirSlot() {
        ParticleField field = new ParticleField(8);
        field.spawn(0.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0.5f, 1);
        field.spawn(0.0f, 65.0f, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 0.5f, 2);
        assertEquals(2, field.count(), "two live");

        int expired = field.advance(0.05f, 9.81f);
        assertEquals(0, expired, "the short-lived one has not reached 0.1 s yet");

        expired = field.advance(0.05f, 9.81f);
        assertEquals(1, expired, "age hits 0.1 on the second frame");
        assertEquals(1, field.count(), "and it is gone");
        assertEquals(1, field.expired(), "recorded in the run total");
        assertEquals(2, field.spriteOf(0), "the survivor was moved into slot 0 by the swap-remove");
    }

    @Test
    void swapRemoveKeepsTheArrayDense() {
        // Removing from the middle has to leave no hole. A hole would be iterated every frame forever
        // and drawn as a particle sitting at whatever was last in that slot.
        ParticleField field = new ParticleField(8);
        for (int i = 0; i < 4; i++) {
            field.spawn(i, 64.0f + i, 0.0f, 0.0f, 0.0f, 0.0f, i == 1 ? 0.05f : 5.0f, 0.5f, i);
        }
        field.advance(0.05f, 9.81f);

        assertEquals(3, field.count(), "the middle one expired");
        assertEquals(0, field.spriteOf(0), "slot 0 untouched");
        assertEquals(3, field.spriteOf(1), "the last particle moved into the hole");
        assertEquals(2, field.spriteOf(2), "and slot 2 kept its own");
        assertEquals(67.0f, field.yOf(1), 0.05f, "carrying its position with it");
    }

    @Test
    void cullingIsWhatActuallySavesTheFrame() {
        // Particles are alpha blended, so early depth test is off and every particle pixel is shaded
        // whether or not something is already there. A thousand particles behind a wall covering a few
        // pixels cost almost nothing; the saving is in not drawing them at all.
        ParticleField field = new ParticleField(1000);
        for (int i = 0; i < 1000; i++) {
            field.spawn(i, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 0.2f, 0);
        }

        assertEquals(1000, field.cull(ALL, 10.0f), "everything visible");
        assertEquals(0, field.culled(), "nothing rejected");

        // Half the explosion is off screen.
        ParticleField.VisibilityTest halfVisible = (x, y, z, r) -> x < 500.0f;
        assertEquals(500, field.cull(halfVisible, 10.0f), "half passes");
        assertEquals(500, field.culled(), "half rejected, and those pixels are never shaded");

        assertEquals(0, field.cull(NONE, 10.0f), "an explosion entirely behind the player");
        assertEquals(1000, field.culled());
        assertEquals(0.0, field.overdrawPixels(), 1e-9, "costs nothing");
    }

    @Test
    void theOverdrawEstimateScalesWithQuadAreaNotCount() {
        // The number to look at when a frame is slow. Halving the quad size quarters the cost, which is
        // why shrinking particles is a bigger lever than dropping some of them.
        ParticleField big = new ParticleField(512);
        ParticleField small = new ParticleField(512);
        for (int i = 0; i < 500; i++) {
            big.spawn(i, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 1.0f, 0);
            small.spawn(i, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 0.5f, 0);
        }
        big.cull(ALL, 10.0f);
        small.cull(ALL, 10.0f);

        assertEquals(50000.0, big.overdrawPixels(), 1e-6, "500 quads of 10x10 pixels");
        assertEquals(12500.0, small.overdrawPixels(), 1e-6, "a quarter, from half the size");
        assertEquals(big.overdrawPixels() / 4.0, small.overdrawPixels(), 1e-6, "area, not count");
    }

    @Test
    void theDrawListGrowsToFitAndHoldsLiveIndices() {
        // The list starts small and grows on demand. If it did not, culling a full field would write
        // past its end, and a field sized for a quiet scene would break the first time an explosion
        // filled it.
        ParticleField field = new ParticleField(600);
        for (int i = 0; i < 600; i++) {
            field.spawn(i, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 0.1f, i);
        }
        int visible = field.cull(ALL, 1.0f);
        assertEquals(600, visible, "more than the initial draw list capacity");
        assertTrue(field.drawList().length >= 600, "the list grew");
        for (int i = 0; i < visible; i++) {
            assertEquals(i, field.drawList()[i], "all visible, so the list is the identity here");
        }
    }

    @Test
    void progressDrivesFadeAndShrink() {
        ParticleField field = new ParticleField(4);
        field.spawn(0.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.1f, 0);

        assertEquals(0.0f, field.progressOf(0), 1e-6f, "at birth");
        field.advance(0.5f, 0.0f);
        assertEquals(0.5f, field.progressOf(0), 1e-6f, "halfway");
        field.advance(0.6f, 0.0f);
        assertEquals(0, field.count(), "and it expired before reaching the end");
    }

    @Test
    void clearDropsParticlesButKeepsTheRunTotals() {
        ParticleField field = new ParticleField(16);
        field.spawn(0.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 0.1f, 0);
        field.cull(ALL, 1.0f);

        field.clear();
        assertEquals(0, field.count(), "no particles left");
        assertEquals(0, field.visibleCount(), "and no draw list");
        assertEquals(0.0, field.overdrawPixels(), 1e-9, "no cost");
        assertEquals(1, field.spawned(), "but the run total survives, since it is about the session");
        assertEquals(16, field.capacity(), "and the arrays are still allocated");
    }

    @Test
    void storageIsStatedSoTheLayoutCanBeJudged() {
        ParticleField field = ParticleField.forClient();
        assertEquals(16384, field.capacity(), "sized for several explosions at once");
        // Nine float arrays plus one int array, all capacity long.
        assertEquals(16384 * (9 * 4 + 4), field.bytesHeld());
        assertEquals(655360, field.bytesHeld(), "640 KB for sixteen thousand particles");
    }

    @Test
    void aNonPositiveTimeStepChangesNothing() {
        // A paused client or a zero-length frame should not age particles, and a negative one -- which
        // a clock adjustment can produce -- must not run the integration backwards.
        ParticleField field = new ParticleField(4);
        field.spawn(0.0f, 64.0f, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 0.1f, 0);
        float before = field.yOf(0);

        assertEquals(0, field.advance(0.0f, 9.81f));
        assertEquals(0, field.advance(-1.0f, 9.81f));
        assertEquals(before, field.yOf(0), 0.0f, "position untouched");
        assertEquals(0.0f, field.progressOf(0), 0.0f, "and no life elapsed");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ParticleField(0));
        assertThrows(IllegalArgumentException.class, () -> new ParticleField(-1));
    }
}
