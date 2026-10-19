package net.amdfaster.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.cpu.InliningBudget.Check;
import net.amdfaster.cpu.InliningBudget.Verdict;
import org.junit.jupiter.api.Test;

class InliningBudgetTest {

    @Test
    void theThirtyFiveByteLimitIsForColdMethodsNotHotOnes() {
        // The correction this class exists to make. 35 is MaxInlineSize, the limit for methods that are
        // not hot; a method the compiler has already decided is hot gets FreqInlineSize, which is 325 on
        // Linux x86-64. The shorthand gets it the wrong way round and sends the splitting effort at the
        // wrong methods.
        assertEquals(35, InliningBudget.MAX_INLINE_SIZE);
        assertEquals(325, InliningBudget.FREQ_INLINE_SIZE);
        assertEquals(9, InliningBudget.MAX_INLINE_LEVEL);
        assertEquals(8000, InliningBudget.HUGE_METHOD_BYTES);
        assertEquals(0.9, InliningBudget.NESTED_INLINING_SIZE_RATIO, 1e-12);

        assertEquals(Verdict.NEVER, InliningBudget.check("cold", 36, 0, false).verdict(),
                "one byte over the cold limit");
        assertEquals(Verdict.ONLY_WHEN_HOT, InliningBudget.check("hot", 36, 0, true).verdict(),
                "the same method is fine when the call site is hot");
    }

    @Test
    void aHotMethodHasNearlyTenTimesTheBudget() {
        assertEquals(Verdict.ONLY_WHEN_HOT, InliningBudget.check("m", 100, 0, true).verdict());
        assertEquals(Verdict.ONLY_WHEN_HOT, InliningBudget.check("m", 325, 0, true).verdict());
        assertEquals(Verdict.NEVER, InliningBudget.check("m", 326, 0, true).verdict());
    }

    @Test
    void theLimitShrinksAtEveryLevelOfNesting() {
        // C1 multiplies by 90 per cent per level, so a method sized to be inlinable on its own is not
        // necessarily inlinable where it is called from.
        assertEquals(35.0, InliningBudget.limitAtDepth(0), 1e-12);
        assertEquals(31.5, InliningBudget.limitAtDepth(1), 1e-12);
        assertEquals(28.35, InliningBudget.limitAtDepth(2), 1e-12);
        assertEquals(25.515, InliningBudget.limitAtDepth(3), 1e-12);
        assertEquals(13.559717115, InliningBudget.limitAtDepth(9), 1e-9,
                "smaller than most methods that would inline comfortably at the top");
    }

    @Test
    void aMethodCanInlineAtTheTopAndNotTwoLevelsDown() {
        // 30 bytes fits the 31.5 byte limit at level one and not the 28.35 at level two, so the same
        // method is inlinable from one call site and not from another.
        assertEquals(Verdict.ALWAYS, InliningBudget.check("m", 30, 1, false).verdict());
        assertEquals(Verdict.NEVER, InliningBudget.check("m", 30, 2, false).verdict());
        assertEquals(Verdict.ONLY_WHEN_HOT, InliningBudget.check("m", 30, 2, true).verdict(),
                "hotness still rescues it, because FreqInlineSize does not shrink with depth");
    }

    @Test
    void theDeepestColdLevelAnswersWhereAMethodCanBeCalledFrom() {
        assertEquals(5, InliningBudget.deepestColdLevel(20));
        assertEquals(1, InliningBudget.deepestColdLevel(30));
        assertEquals(0, InliningBudget.deepestColdLevel(35), "exactly the limit, so only the top");
        assertEquals(-1, InliningBudget.deepestColdLevel(36), "never cold, at any depth");
        assertEquals(-1, InliningBudget.deepestColdLevel(100));
        assertEquals(9, InliningBudget.deepestColdLevel(13), "small enough for the deepest level");
    }

    @Test
    void aMethodTooBigToCompileIsADifferentFailure() {
        // DontCompileHugeMethods is on by default, so a method over 8,000 bytecode bytes stays
        // interpreted however hot it gets. That is not a missed inline and should not be reported as one.
        Check huge = InliningBudget.check("huge", 8001, 0, true);

        assertEquals(Verdict.NOT_COMPILED, huge.verdict());
        assertEquals(8000 - 8001, huge.headroom(), "negative, because it is over the compile limit");
        assertFalse(huge.escapeAnalysisPossible());

        // Exactly at the compile limit: it does compile, so it is not NOT_COMPILED -- but 8,000 bytes is
        // far past FreqInlineSize, so it never inlines either. Two different thresholds, and conflating
        // them is exactly the mistake the cold/hot distinction above is guarding against.
        Check atCompileLimit = InliningBudget.check("big", 8000, 0, true);
        assertEquals(Verdict.NEVER, atCompileLimit.verdict());
        assertFalse(atCompileLimit.escapeAnalysisPossible());
        // The two verdicts measure headroom against different limits, which is the only way to tell them
        // apart once both are negative. NOT_COMPILED is measured against the compile limit, so 8,001
        // bytes is one byte over; NEVER is measured against the cold inlining limit, so 8,000 bytes is
        // 7,965 over. Same sign, different meaning.
        assertEquals(35.0 - 8000, atCompileLimit.headroom(), 1e-12);
    }

    @Test
    void aMissedInlineIsWhatDefeatsEscapeAnalysis() {
        // Escape analysis runs after inlining, so a method that fails to inline is a method whose
        // allocations the compiler can no longer prove do not escape. That is the mechanism behind a
        // lambda in Stream.map allocating where the same code as a loop does not.
        assertTrue(InliningBudget.check("m", 20, 0, false).escapeAnalysisPossible());
        assertTrue(InliningBudget.check("m", 36, 0, true).escapeAnalysisPossible(),
                "hot, so inlined, so escape analysis still runs");
        assertFalse(InliningBudget.check("m", 36, 0, false).escapeAnalysisPossible());
        assertFalse(InliningBudget.check("m", 326, 0, true).escapeAnalysisPossible());
    }

    @Test
    void headroomSaysHowCloseToTheEdgeAMethodIs() {
        Check comfortable = InliningBudget.check("m", 20, 0, false);
        assertEquals(15.0, comfortable.headroom(), 1e-12);

        Check atTheEdge = InliningBudget.check("m", 35, 0, false);
        assertEquals(0.0, atTheEdge.headroom(), 1e-12, "one more bytecode and it stops inlining cold");

        Check hot = InliningBudget.check("m", 100, 0, true);
        assertEquals(35.0 - 100, hot.headroom(), 1e-12,
                "measured against the cold limit, which is the one that would apply if the site cooled");
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> InliningBudget.check(null, 10, 0, false));
        assertThrows(IllegalArgumentException.class, () -> InliningBudget.check("m", -1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> InliningBudget.check("m", 10, -1, false));
        assertThrows(IllegalArgumentException.class,
                () -> InliningBudget.check("m", 10, 10, false), "past the deepest level");
        assertThrows(IllegalArgumentException.class, () -> InliningBudget.limitAtDepth(-1));
        assertThrows(IllegalArgumentException.class, () -> InliningBudget.limitAtDepth(10));
        assertThrows(IllegalArgumentException.class, () -> InliningBudget.deepestColdLevel(-1));
    }
}
