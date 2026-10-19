package net.amdfaster.cpu;

/**
 * Checks a method's bytecode size against HotSpot's inlining thresholds, and corrects which threshold
 * applies to which method.
 *
 * <p>The usual shorthand is that a hot method has to be under 35 bytes of bytecode or it will not be
 * inlined. That is the wrong way round, and getting it wrong sends effort to the wrong methods. 35 is
 * {@code MaxInlineSize}, which is the limit for methods that are <em>not</em> hot -- below it they are
 * inlined regardless of call frequency. A method the compiler has already decided is hot gets
 * {@code FreqInlineSize}, which is 325 bytes on Linux x86-64 and platform dependent elsewhere. So a hot
 * method has nearly ten times the budget the shorthand claims, and a cold one has exactly the budget it
 * claims.
 *
 * <p>Why inlining matters enough to check is that it is not just dispatch overhead. Escape analysis runs
 * <em>after</em> inlining, so a method that fails to inline is a method whose allocations the compiler can
 * no longer prove do not escape -- and an allocation it cannot prove stays on the heap. That is the
 * mechanism behind a lambda passed to {@code Stream.map} allocating when the same code written as a loop
 * does not: the library method's body is large enough to blow the inlining budget, so the lambda and
 * anything it captures escape. Inlining alone is worth 7 to 10 times on the paths where it applies, and
 * the allocation it unlocks is on top of that.
 *
 * <p>The third threshold is the one that bites in nested code. C1 shrinks the limit by
 * {@code NestedInliningSizeRatio}, 90 per cent, at each level of nesting, so a 30 byte method inlines at
 * the top of a call chain and stops inlining two levels down. A method sized to be inlinable on its own
 * is not necessarily inlinable where it is called from, which is why the depth is an input here rather
 * than an afterthought.
 *
 * <p>There is also a floor below all of this. A method over 8,000 bytes of bytecode is never compiled at
 * all under {@code DontCompileHugeMethods}, which is on by default, so it stays interpreted however hot it
 * gets. That is a different failure from a missed inline and worth naming separately.
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html">The java Command,
 *      JDK 21 -- {@code MaxInlineSize} is the maximum bytecode size of a <em>cold</em> method to be
 *      inlined and defaults to 35; {@code FreqInlineSize} is the maximum for a <em>hot</em> method,
 *      applies only to C2, and is platform dependent</a>
 * @see <a href="https://stackoverflow.com/questions/43002528/when-can-hotspot-allocate-objects-on-the-stack">
 *      When can HotSpot allocate objects on the stack -- escape analysis is performed after inlining, so
 *      a method too big to inline is a method whose objects cannot be proven not to escape</a>
 * @see <a href="https://stackoverflow.com/questions/46508393/meaning-of-callee-is-too-large-in-jvm-logcompilation-output">
 *      "callee is too large" -- C1 applies {@code MaxInlineSize} multiplied by
 *      {@code NestedInliningSizeRatio}, 90 per cent, at each further level of inlining</a>
 * @see <a href="https://www.javacodegeeks.com/2026-04/the-object-allocation-tax-why-your-java-service-is-40-gc-and-how-the-jits-escape-analysis-both-helps-and-misleads-you.html">
 *      The Object Allocation Tax -- a lambda passed to a library method whose body exceeds the inlining
 *      budget escapes to the heap along with what it captures</a>
 */
public final class InliningBudget {

    /** Bytecode size below which a method is inlined regardless of how often it is called. */
    public static final int MAX_INLINE_SIZE = 35;

    /**
     * Bytecode size below which a hot method is inlined.
     *
     * <p>325 on Linux x86-64 and platform dependent elsewhere, so this is the value to check against
     * rather than a guarantee -- but it is an order of magnitude more room than the 35 byte shorthand
     * allows, which changes what is worth splitting.
     */
    public static final int FREQ_INLINE_SIZE = 325;

    /** How much the limit shrinks per level of nesting, as a ratio. */
    public static final double NESTED_INLINING_SIZE_RATIO = 0.9;

    /** The deepest nesting the compiler will inline through. */
    public static final int MAX_INLINE_LEVEL = 9;

    /** Bytecode size above which a method is never compiled at all and stays interpreted. */
    public static final int HUGE_METHOD_BYTES = 8000;

    private InliningBudget() {
    }

    /** The verdict for one method at one call depth. */
    public enum Verdict {
        /** Small enough to inline whether or not the call site is hot. */
        ALWAYS,
        /** Too big for the cold limit, small enough for the hot one. */
        ONLY_WHEN_HOT,
        /** Too big for either. */
        NEVER,
        /** Too big to be compiled at all, so inlining is not the question. */
        NOT_COMPILED
    }

    /**
     * One method's verdict and the headroom behind it.
     *
     * @param bytes         the method's bytecode size
     * @param depth         how deep in the call chain the call site is
     * @param limitAtDepth  the cold limit at that depth, after the per-level shrink
     * @param verdict       what the compiler will do
     */
    public record Check(String method, int bytes, int depth, double limitAtDepth, Verdict verdict) {

        /** Bytes of headroom against the limit that applies, which is what says how close to the edge. */
        public double headroom() {
            if (this.verdict == Verdict.NOT_COMPILED) {
                return HUGE_METHOD_BYTES - this.bytes;
            }
            return this.limitAtDepth - this.bytes;
        }

        /** Whether the method's allocations can still be proven not to escape. */
        public boolean escapeAnalysisPossible() {
            return this.verdict != Verdict.NEVER && this.verdict != Verdict.NOT_COMPILED;
        }
    }

    /**
     * The cold inlining limit at a given nesting depth.
     *
     * <p>C1 multiplies the limit by 90 per cent at each further level, so the budget a method has depends
     * on where it is called from and not only on its own size. At depth nine it is down to 13.56 bytes,
     * which is smaller than most methods that would be comfortably inlinable at the top.
     */
    public static double limitAtDepth(int depth) {
        require(depth >= 0, "a depth must not be negative");
        require(depth <= MAX_INLINE_LEVEL, "the compiler does not inline past level " + MAX_INLINE_LEVEL);
        return MAX_INLINE_SIZE * Math.pow(NESTED_INLINING_SIZE_RATIO, depth);
    }

    /**
     * Checks a method.
     *
     * @param method a name for the report
     * @param bytes  the method's bytecode size
     * @param depth  how deep the call site is; 0 for a call from the top of the chain
     * @param hot    whether the compiler has decided the call site is hot, which is what raises the limit
     *               from 35 to 325
     */
    public static Check check(String method, int bytes, int depth, boolean hot) {
        require(method != null, "a method needs a name");
        require(bytes >= 0, "a bytecode size must not be negative");
        double limit = limitAtDepth(depth);
        Verdict verdict;
        if (bytes > HUGE_METHOD_BYTES) {
            verdict = Verdict.NOT_COMPILED;
        } else if (bytes <= limit) {
            verdict = Verdict.ALWAYS;
        } else if (hot && bytes <= FREQ_INLINE_SIZE) {
            verdict = Verdict.ONLY_WHEN_HOT;
        } else {
            verdict = Verdict.NEVER;
        }
        return new Check(method, bytes, depth, limit, verdict);
    }

    /**
     * The deepest level at which a method of this size still inlines as a cold call.
     *
     * <p>The number that answers "can I call this from inside the mesh worker". A 30 byte method manages
     * one level; a 20 byte method manages five; a 36 byte method manages none and needs the call site to
     * be hot.
     *
     * @return the deepest level, or -1 when it never inlines cold at any depth
     */
    public static int deepestColdLevel(int bytes) {
        require(bytes >= 0, "a bytecode size must not be negative");
        int deepest = -1;
        for (int depth = 0; depth <= MAX_INLINE_LEVEL; depth++) {
            if (bytes <= limitAtDepth(depth)) {
                deepest = depth;
            }
        }
        return deepest;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
