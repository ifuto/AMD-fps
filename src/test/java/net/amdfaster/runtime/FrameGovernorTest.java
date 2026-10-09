package net.amdfaster.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameGovernorTest {

    @Test
    void anActivePlayerIsNeverCapped() {
        // The one hard rule. A player who is looking at the game and moving the mouse gets exactly what
        // they asked for, whatever that is. Every other behaviour in this class is a consequence of
        // that one holding.
        FrameGovernor governor = new FrameGovernor();
        governor.noteInput(0);
        assertEquals(240, governor.tick(0, true, 240), "a 240 Hz setting is honoured");
        assertEquals(FrameGovernor.State.ACTIVE, governor.state());
        assertEquals(0, governor.cappedFrames());
        assertEquals(0, governor.savedFrames());
    }

    @Test
    void anUnfocusedWindowIsCapped() {
        // The cheapest frame is the one not drawn, and nobody is looking at this one. Minecraft still
        // ticks -- pausing a singleplayer world in the background would be a behaviour change -- it just
        // does not draw sixty times a second for an empty chair.
        FrameGovernor governor = new FrameGovernor();
        governor.noteFocusChange(0, false);
        assertEquals(FrameGovernor.State.UNFOCUSED, governor.state());
        assertEquals(10, governor.tick(0, false, 60));
        assertEquals(1, governor.cappedFrames());
        assertEquals(50, governor.savedFrames(), "fifty frames a second not rendered");
    }

    @Test
    void theCapNeverRaisesThePlayersSetting() {
        // A player who set 5 FPS on purpose does not want it lifted to the idle cap.
        FrameGovernor governor = new FrameGovernor();
        governor.noteFocusChange(0, false);
        assertEquals(5, governor.tick(0, false, 5), "the lower of the two wins");
        assertEquals(0, governor.savedFrames(), "and nothing was saved, because nothing was cut");
    }

    @Test
    void goingIdleNeedsTheFullDelay() {
        FrameGovernor governor = new FrameGovernor();
        governor.noteInput(1000);

        assertEquals(60, governor.tick(1000 + 59_999, true, 60), "one millisecond short is still active");
        assertEquals(FrameGovernor.State.ACTIVE, governor.state());

        assertEquals(30, governor.tick(1000 + 60_000, true, 60), "at the delay it goes idle");
        assertEquals(FrameGovernor.State.IDLE, governor.state());
        assertEquals(30, governor.savedFrames());
    }

    @Test
    void inputResetsTheIdleClock() {
        FrameGovernor governor = new FrameGovernor();
        governor.noteInput(0);
        governor.tick(30_000, true, 60);
        assertEquals(FrameGovernor.State.ACTIVE, governor.state());

        governor.noteInput(30_000);
        assertEquals(60, governor.tick(80_000, true, 60), "fifty seconds since the last input, not sixty");
        assertEquals(FrameGovernor.State.ACTIVE, governor.state());

        assertEquals(30, governor.tick(90_000, true, 60), "and idle again after the full delay");
    }

    @Test
    void leavingIdleCostsSomethingSoTheStateDoesNotFlicker() {
        // A governor that bounces out of idle on every key tap is worse than no governor, because each
        // transition resets the frame limiter and the result is a stutter with no visible cause.
        FrameGovernor governor = new FrameGovernor();
        governor.noteInput(0);
        governor.tick(60_000, true, 60);
        assertEquals(FrameGovernor.State.IDLE, governor.state());
        long transitionsBefore = governor.transitions();

        // A tap, immediately followed by another tick.
        governor.noteInput(60_100);
        governor.tick(60_100, true, 60);
        assertEquals(FrameGovernor.State.ACTIVE, governor.state(), "input does wake it");

        // ...and then no further input. The grace period means the return to idle is not instant, so a
        // player tapping a key every few hundred milliseconds stays in one state rather than two.
        long after = governor.transitions();
        assertTrue(after > transitionsBefore, "the wake itself is one transition");
        governor.tick(60_200, true, 60);
        assertEquals(after, governor.transitions(), "no further transition in the grace window");
    }

    @Test
    void regainingFocusDoesNotLiftTheCapForOneFrame() {
        // Lifting it instantly would let a window being alt-tabbed through run at full rate a frame at
        // a time, which costs the work without ever producing a smooth frame.
        FrameGovernor governor = new FrameGovernor();
        governor.noteFocusChange(0, false);
        assertEquals(10, governor.tick(0, false, 60));

        governor.noteFocusChange(1000, true);
        assertEquals(10, governor.tick(1000, true, 60), "still capped on the frame focus returns");
        assertEquals(60, governor.tick(1000 + FrameGovernor.DEFAULT_FOCUS_GRACE_MS, true, 60),
                "and full rate once the grace period has passed");
    }

    @Test
    void unfocusedBeatsIdle() {
        // Both conditions at once. The lower cap is the right answer: the window is not being watched,
        // and being idle as well does not make it more worth drawing.
        FrameGovernor governor = new FrameGovernor();
        governor.noteInput(0);
        governor.noteFocusChange(0, false);
        assertEquals(10, governor.tick(120_000, false, 60));
        assertEquals(FrameGovernor.State.UNFOCUSED, governor.state());
    }

    @Test
    void theFrameIntervalForATargetRate() {
        assertEquals(16L, FrameGovernor.intervalMs(60), "1000/60 truncated");
        assertEquals(33L, FrameGovernor.intervalMs(30));
        assertEquals(100L, FrameGovernor.intervalMs(10));
        assertEquals(0L, FrameGovernor.intervalMs(0), "zero and below mean uncapped");
        assertEquals(0L, FrameGovernor.intervalMs(-1));
    }

    @Test
    void theRunTotalsSurviveAcrossStatesAndClearTogether() {
        FrameGovernor governor = new FrameGovernor();
        governor.noteFocusChange(0, false);
        governor.tick(0, false, 60);
        governor.tick(16, false, 60);
        assertEquals(2, governor.cappedFrames());
        assertEquals(100, governor.savedFrames());

        governor.clear();
        assertEquals(FrameGovernor.State.ACTIVE, governor.state());
        assertEquals(0, governor.cappedFrames());
        assertEquals(0, governor.savedFrames());
        assertEquals(0, governor.transitions());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FrameGovernor(0, 30, 1000, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new FrameGovernor(10, 0, 1000, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new FrameGovernor(10, 30, -1, 100, 100));
    }
}
