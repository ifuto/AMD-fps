package net.amdfaster.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import net.amdfaster.cpu.InliningBudget.Verdict;
import org.junit.jupiter.api.Test;

class BytecodeSizesTest {

    @Test
    void itMeasuresTheClassItIsAskedAbout() {
        assertTrue(BytecodeSizes.isMeasurable(InliningBudget.class),
                "the mod's own classes are on the classpath, so they are measurable");

        // limitAtDepth is two statements and a call. Whatever javac makes of it, it is a real method with a
        // real body, and the measurement is the number C2 will compare against the inlining limits.
        int limitAtDepth = BytecodeSizes.methodBytes(InliningBudget.class, "limitAtDepth", "(I)D");
        assertTrue(limitAtDepth > 0, "measured " + limitAtDepth);
    }

    @Test
    void aTrivialGuardMethodIsSmallEnoughToAlwaysInline() {
        // require(boolean, String) is a compare and a throw. This is the assertion that ties the
        // measurement back to the decision it exists to inform: a method this small is under MaxInlineSize
        // at depth zero, so it inlines whether or not the call site is hot -- which is exactly the claim
        // InliningBudget makes and the reason the size has to be measured rather than assumed.
        int bytes = BytecodeSizes.methodBytes(InliningBudget.class, "require", "(ZLjava/lang/String;)V");

        assertTrue(bytes > 0, "the guard method exists and has a body");
        assertTrue(bytes <= InliningBudget.MAX_INLINE_SIZE,
                "a compare and a throw measured " + bytes + " bytes, over the 35 byte cold limit");
        assertEquals(Verdict.ALWAYS, InliningBudget.check("require", bytes, 0, false).verdict());
    }

    @Test
    void everyDeclaredMethodIsFound() {
        // Walks the reflection view of the class and asks the reader for each one. This is the check that
        // the constant pool was walked correctly: a single wrong slot size puts every following name index
        // out of step, and the reader would answer UNMEASURED for names that are plainly there.
        for (Method method : InliningBudget.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            int bytes = BytecodeSizes.methodBytes(InliningBudget.class, method.getName(), null);
            assertTrue(bytes > 0, "no measurement for " + method.getName()
                    + " -- the class file parse lost track of the method table");
        }
    }

    @Test
    void constructorsAndStaticInitialisersAreMethodsToo() {
        // getDeclaredMethods leaves these out, so nothing above would notice if the reader skipped them --
        // and a constructor is often the method that decides whether an object can be scalar replaced.
        List<BytecodeSizes.Method> methods = BytecodeSizes.methodsOf(InliningBudget.class);

        assertTrue(methods.stream().anyMatch(m -> m.name().equals("<init>")),
                "the private constructor is a method in the class file");
        assertTrue(methods.stream().allMatch(m -> m.bytes() > 0), "every measured method has a body");
    }

    @Test
    void theLargestMethodIsTheLargestOfWhatWasMeasured() {
        int largest = BytecodeSizes.largestMethodBytes(InliningBudget.class);

        assertTrue(largest > 0);
        for (BytecodeSizes.Method method : BytecodeSizes.methodsOf(InliningBudget.class)) {
            assertTrue(method.bytes() <= largest, method.name() + " is larger than the reported largest");
        }
    }

    @Test
    void anAbsentMethodIsUnmeasuredRatherThanZero() {
        // Zero would read as "tiny, so it always inlines", which is the opposite of the truth about a
        // method that could not be found. The distinction is the whole reason UNMEASURED is negative.
        assertEquals(BytecodeSizes.UNMEASURED,
                BytecodeSizes.methodBytes(InliningBudget.class, "noSuchMethod", "()V"));
    }

    @Test
    void anInterfaceWithNoBodiesHasNothingToMeasure() {
        // An abstract method has no Code attribute at all, so there is no size to report for it. Reporting
        // one as zero would say "this method is as small as a method gets", and zero inlines at every depth
        // -- a confident claim about a method that has no body.
        assertTrue(BytecodeSizes.isMeasurable(NoBodies.class), "the class file itself is there");
        assertTrue(BytecodeSizes.methodsOf(NoBodies.class).isEmpty(), "none of its methods has a body");
        assertEquals(BytecodeSizes.UNMEASURED, BytecodeSizes.largestMethodBytes(NoBodies.class));
    }

    @Test
    void aClassWithNoClassFileBehindItIsReportedAsUnmeasurable() {
        // A dynamic proxy is generated in memory, so there is no .class resource to read. The measurement
        // has to say so instead of inventing a size, and the caller has to be able to tell that apart from
        // a method that really is empty.
        Runnable proxy = (Runnable) java.lang.reflect.Proxy.newProxyInstance(
                BytecodeSizesTest.class.getClassLoader(), new Class<?>[] {Runnable.class},
                (p, m, a) -> null);

        assertFalse(BytecodeSizes.isMeasurable(proxy.getClass()), "a proxy has no class file");
        assertTrue(BytecodeSizes.methodsOf(proxy.getClass()).isEmpty());
        assertEquals(BytecodeSizes.UNMEASURED, BytecodeSizes.largestMethodBytes(proxy.getClass()));
    }

    @Test
    void aNullClassIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BytecodeSizes.methodsOf(null));
        assertThrows(IllegalArgumentException.class, () -> BytecodeSizes.isMeasurable(null));
        assertThrows(IllegalArgumentException.class, () -> BytecodeSizes.largestMethodBytes(null));
    }

    @Test
    void theMeasurementDrivesTheSplittingDecision() {
        // What the measurement is for. A class whose largest method is under FreqInlineSize is a class
        // where no method can fail the hot limit, so splitting it up would buy nothing -- and a class with
        // a method over it has a named target. Both answers come from measured bytes, not from a guess
        // about which method looked long in the source.
        int largest = BytecodeSizes.largestMethodBytes(BytecodeSizes.class);

        assertTrue(largest > 0, "the parser itself is measurable");
        InliningBudget.Check check = InliningBudget.check("largest", largest, 0, true);
        if (largest > InliningBudget.FREQ_INLINE_SIZE) {
            assertEquals(Verdict.NEVER, check.verdict(),
                    "over the hot limit at " + largest + " bytes, so it never inlines and escape analysis "
                            + "cannot run through it");
            assertFalse(check.escapeAnalysisPossible());
        } else {
            assertTrue(check.escapeAnalysisPossible());
        }
    }

    /**
     * An interface whose methods have no bodies.
     *
     * <p>Used to pin down what the reader does with a method that has no {@code Code} attribute, which is
     * every abstract method it will ever be asked about -- including Minecraft's, since a mod reading a
     * Minecraft class through its interface hits exactly this.
     */
    private interface NoBodies {
        void first();

        void second();
    }
}
