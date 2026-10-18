package net.amdfaster.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.render.DepthPrepassSelector.Decision;
import net.amdfaster.render.DepthPrepassSelector.Inputs;
import org.junit.jupiter.api.Test;

/**
 * Every threshold here is the break-even the implementation derives, not a number chosen to make a test
 * pass. The check that matters is {@link #theThresholdAgreesWithTheTwoCosts}: if the threshold and the
 * costs ever disagree, the selector would pick the more expensive option and nothing else would notice.
 */
class DepthPrepassSelectorTest {

    private static final long PIXELS = 1920L * 1080L;
    private static final double VERTEX = 60_000.0;
    private static final double FRAGMENT = 0.010;

    private static Inputs in(double overdraw, double cutout) {
        return new Inputs(overdraw, VERTEX, FRAGMENT, PIXELS, cutout, false, false, false);
    }

    @Test
    void theBreakEvenComesOutOfTheCostEquation() {
        // V < (O - 1) * N * F * (1 - cutout), rearranged. At 1080p with a mid-cost terrain shader the
        // threshold is about 3.9x, which is in the range the published guidance gives.
        assertEquals(3.893519, DepthPrepassSelector.breakEvenOverdraw(VERTEX, FRAGMENT, PIXELS, 0.0), 1e-6);
        // Cutout geometry cannot go in the pre-pass, so a quarter of the frame being cutout raises the
        // bar by exactly the part of the saving that was never available.
        assertEquals(4.858025, DepthPrepassSelector.breakEvenOverdraw(VERTEX, FRAGMENT, PIXELS, 0.25), 1e-6);
        // Geometry that costs nothing makes any overdraw worth two passes.
        assertEquals(1.0, DepthPrepassSelector.breakEvenOverdraw(0.0, FRAGMENT, PIXELS, 0.0), 1e-12);
    }

    @Test
    void aCheapShaderNeedsEnormousOverdrawBeforeAPrepassPays() {
        // The reason this is a decision rather than a setting. Shading a pixel six times with a trivial
        // shader is still cheaper than transforming the world twice.
        double trivial = 0.0005;
        assertEquals(58.870370,
                DepthPrepassSelector.breakEvenOverdraw(VERTEX, trivial, PIXELS, 0.0), 1e-6);
    }

    @Test
    void theThresholdAgreesWithTheTwoCosts() {
        // The property that would otherwise go unnoticed: the threshold and the cost model have to pick
        // the same winner, or the selector chooses the more expensive path and reports that it optimised.
        DepthPrepassSelector selector = new DepthPrepassSelector();
        for (double overdraw = 1.0; overdraw <= 8.0; overdraw += 0.25) {
            Inputs inputs = in(overdraw, 0.0);
            double threshold = DepthPrepassSelector.breakEvenOverdraw(VERTEX, FRAGMENT, PIXELS, 0.0);
            double one = DepthPrepassSelector.frameCost(false, inputs);
            double two = DepthPrepassSelector.frameCost(true, inputs);
            Decision decision = selector.decide(inputs);

            if (overdraw > threshold) {
                assertEquals(Decision.PREPASS, decision, "overdraw " + overdraw);
                assertTrue(two < one, "and two passes really is cheaper at " + overdraw);
            } else {
                assertEquals(Decision.SINGLE_PASS, decision, "overdraw " + overdraw);
                assertTrue(one <= two, "and one pass really is cheaper at " + overdraw);
            }
        }
    }

    @Test
    void lowOverdrawStaysOnePass() {
        DepthPrepassSelector selector = new DepthPrepassSelector();

        assertEquals(Decision.SINGLE_PASS, selector.decide(in(1.0, 0.0)), "nothing is overdrawn");
        assertEquals(Decision.SINGLE_PASS, selector.decide(in(2.0, 0.0)));
        assertEquals(Decision.SINGLE_PASS, selector.decide(in(3.89, 0.0)));

        // Exactly at the threshold the two cost the same, and one pass is chosen: the pre-pass also
        // submits every draw twice, which the arithmetic here does not price. Computed rather than
        // written out, because the threshold is 3.8935185185... and a rounded literal lands on the
        // wrong side of it -- rounding 3.8935185 up to 3.893519 puts it above the bar and the selector
        // correctly answers PREPASS, which is not what this test is asking about.
        double threshold = DepthPrepassSelector.breakEvenOverdraw(VERTEX, FRAGMENT, PIXELS, 0.0);
        assertEquals(Decision.SINGLE_PASS, selector.decide(in(threshold, 0.0)),
                "at the threshold itself, one pass");
        assertEquals(Decision.PREPASS, selector.decide(in(Math.nextUp(threshold), 0.0)),
                "one ulp above it, two");
        assertEquals(4L, selector.singlePasses());
        assertEquals(1L, selector.prepasses());
        assertEquals(0L, selector.prepasses());
    }

    @Test
    void highOverdrawTakesTwoPasses() {
        DepthPrepassSelector selector = new DepthPrepassSelector();

        assertEquals(Decision.PREPASS, selector.decide(in(4.0, 0.0)));
        assertEquals(Decision.PREPASS, selector.decide(in(6.0, 0.0)));
        assertEquals(2L, selector.prepasses());
    }

    @Test
    void tileBasedHardwareNeverTakesAManualPrepass() {
        // TBDR removes hidden surfaces before shading anything, so a manual pre-pass repeats work the
        // tile already did. This is not about overdraw -- it is the wrong answer at any overdraw.
        DepthPrepassSelector selector = new DepthPrepassSelector();
        Inputs inputs = new Inputs(12.0, VERTEX, FRAGMENT, PIXELS, 0.0, true, false, false);

        assertEquals(Decision.HARDWARE_HSR, selector.decide(inputs));
        assertEquals(1L, selector.hardwareHsr());
    }

    @Test
    void theEarlyAndLateExtensionRemovesTheNeedForASecondPass() {
        // It lets one shader test depth early for most fragments and late for the ones that discard,
        // which solves cutout without a pre-pass at all.
        DepthPrepassSelector selector = new DepthPrepassSelector();
        Inputs inputs = new Inputs(12.0, VERTEX, FRAGMENT, PIXELS, 0.4, false, true, false);

        assertEquals(Decision.EARLY_AND_LATE, selector.decide(inputs));
        assertEquals(1L, selector.earlyAndLate());
    }

    @Test
    void suppressedEarlyZLowersTheBarButDoesNotForceAPrepass() {
        // The RDNA4 case: the driver's default HiZ workaround defeats early-Z, so the forward pass shades
        // more than the measured overdraw says. Scaled rather than forced -- geometry that would make the
        // pre-pass a loss still makes it a loss.
        DepthPrepassSelector selector = new DepthPrepassSelector();
        Inputs low = new Inputs(2.0, VERTEX, FRAGMENT, PIXELS, 0.0, false, false, true);
        Inputs high = new Inputs(3.0, VERTEX, FRAGMENT, PIXELS, 0.0, false, false, true);

        // effective overdraw is 1 + (O - 1) / (2/3): 2.0 -> 2.5, still under 3.89
        assertEquals(Decision.SINGLE_PASS, selector.decide(low));
        // 3.0 -> 4.0, over it
        assertEquals(Decision.PREPASS, selector.decide(high));
        assertEquals(2.0 / 3.0, DepthPrepassSelector.EARLY_Z_SUPPRESSION_FACTOR, 1e-12);
    }

    @Test
    void cutoutRaisesTheBarBecauseThePrepassCannotHelpThosePixels() {
        // A depth-only shader cannot know which leaf pixels the real shader would discard, and guessing
        // wrong writes depth where there is no surface -- which the colour pass then accepts with EQUAL
        // and draws nothing, leaving a hole. So cutout stays out, and the saving shrinks with it.
        DepthPrepassSelector selector = new DepthPrepassSelector();

        assertEquals(Decision.PREPASS, selector.decide(in(4.5, 0.0)));
        assertEquals(Decision.SINGLE_PASS, selector.decide(in(4.5, 0.5)),
                "the same overdraw, but half the frame is cutout and the bar moved past it");
    }

    @Test
    void nonsenseInputsAreRejectedRatherThanAnswered() {
        DepthPrepassSelector selector = new DepthPrepassSelector();

        assertThrows(IllegalArgumentException.class, () -> selector.decide(null));
        // Overdraw below one is not a scene, it is a mistake, and answering it would hide the mistake.
        assertThrows(IllegalArgumentException.class, () -> selector.decide(in(0.5, 0.0)));
        assertThrows(IllegalArgumentException.class, () -> selector.decide(in(Double.NaN, 0.0)));
        assertThrows(IllegalArgumentException.class, () -> selector.decide(in(2.0, -0.1)));
        assertThrows(IllegalArgumentException.class, () -> selector.decide(in(2.0, 1.0)),
                "a frame that is entirely cutout has no pre-pass to speak of");
        assertThrows(IllegalArgumentException.class,
                () -> selector.decide(new Inputs(2.0, VERTEX, 0.0, PIXELS, 0.0, false, false, false)));
        assertThrows(IllegalArgumentException.class,
                () -> selector.decide(new Inputs(2.0, VERTEX, FRAGMENT, 0, 0.0, false, false, false)));
        assertThrows(IllegalArgumentException.class,
                () -> selector.decide(new Inputs(2.0, -1.0, FRAGMENT, PIXELS, 0.0, false, false, false)));
        assertThrows(IllegalArgumentException.class,
                () -> DepthPrepassSelector.breakEvenOverdraw(VERTEX, FRAGMENT, PIXELS, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> DepthPrepassSelector.frameCost(true, null));
    }

    @Test
    void aFreshSelectorReportsZeroNotNaN() {
        DepthPrepassSelector selector = new DepthPrepassSelector();

        assertEquals(0L, selector.decisions());
        assertEquals(0L, selector.prepasses());
        assertEquals(0L, selector.singlePasses());
        assertEquals(0L, selector.earlyAndLate());
        assertEquals(0L, selector.hardwareHsr());
    }

    @Test
    void everyDecisionIsCounted() {
        DepthPrepassSelector selector = new DepthPrepassSelector();

        selector.decide(in(1.0, 0.0));
        selector.decide(in(9.0, 0.0));
        selector.decide(new Inputs(9.0, VERTEX, FRAGMENT, PIXELS, 0.0, true, false, false));
        selector.decide(new Inputs(9.0, VERTEX, FRAGMENT, PIXELS, 0.0, false, true, false));

        assertEquals(4L, selector.decisions());
        assertEquals(1L, selector.singlePasses());
        assertEquals(1L, selector.prepasses());
        assertEquals(1L, selector.hardwareHsr());
        assertEquals(1L, selector.earlyAndLate());
        assertEquals(4L, selector.singlePasses() + selector.prepasses()
                + selector.hardwareHsr() + selector.earlyAndLate(), "nothing falls through uncounted");
    }
}
