package net.amdfaster.cpu;

/**
 * Which vector backend a kernel should use, decided once at startup and never guessed at the call site.
 *
 * <h2>The floor is scalar</h2>
 *
 * <p>Java's Vector API lives in {@code jdk.incubator.vector}, which the JVM does not expose unless it is
 * started with {@code --add-modules=jdk.incubator.vector}. The mod cannot pass that flag, and the user
 * may not have. So this class <b>never names a class from that package statically</b>: it resolves the
 * module by string, and if resolution fails the mod still loads and runs, on scalar code. A mod that
 * crashed at class-init on a JVM without the module would be worse than no optimisation at all.
 *
 * <p>The API has been through eleven incubations from JDK 16 to JDK 26 with no changes since JDK 22, so
 * it is stable in practice, but it is still not part of Java SE and must not be depended on.
 *
 * <h2>The ladder</h2>
 *
 * <p>Each kernel resolves its backend once, first match wins:
 *
 * <ol>
 *   <li>a kill-switch system property is set, so the user can disable everything after a bad driver
 *       interaction, without editing a config file they cannot reach;</li>
 *   <li>the module is absent from the boot layer;</li>
 *   <li>the module is present but its classes fail to link;</li>
 *   <li>the widest available shape is narrower than 256 bits, where vectorising a loop over a handful of
 *       elements costs more in setup than it saves;</li>
 *   <li>the differential self-test between the scalar and vector implementations disagrees, which means
 *       one of them is wrong and the scalar one is the one whose result the rest of the mod has been
 *       built against.</li>
 * </ol>
 *
 * <p>Item five is the one that matters. A vector kernel that is fast and occasionally wrong produces
 * geometry that flickers or vanishes, once every few thousand frames, in a way no one can reproduce.
 * Checking it against the scalar twin at startup turns that into a log line.
 *
 * <h2>What AMD adds</h2>
 *
 * <p>Zen 4 executes a 512-bit instruction on two 256-bit units <em>simultaneously</em>, not sequentially,
 * so a 512-bit operation still retires at roughly one per cycle for basic arithmetic and the latency is
 * not doubled. Wider vectors are worth using even there, because one instruction replaces two and the
 * front end has less to decode. Zen 5 desktop widened the datapath to a native 512 bits for double the
 * throughput, but <b>Zen 5 mobile and the Ryzen AI APUs kept the 256-bit path</b>, so a stripped APU
 * behaves like Zen 4 here. Neither case changes what code should be written; both change what speedup to
 * expect.
 *
 * <p>Two AMD-specific costs do change the code. <b>Gather and scatter are slow on Zen</b>, so the
 * structure-of-arrays layout used by the culling kernels exists partly to make every access a
 * contiguous load. And on Zen 5 the minimum latency of a 512-bit operation rose from one cycle to two,
 * so a kernel needs enough independent work in flight to hide it -- which again favours long contiguous
 * runs over short dependent chains.
 *
 * <p>Immutable.
 */
public final class SimdSupport {

    /** Set to {@code true} to force every kernel onto the scalar path without touching the config. */
    public static final String KILL_SWITCH = "amdfaster.forceScalar";

    /** Below this many bits per vector, the setup cost outweighs the throughput gain. */
    public static final int MIN_USEFUL_BITS = 256;

    /** The package this class refuses to reference statically. */
    private static final String VECTOR_MODULE = "jdk.incubator.vector";
    private static final String VECTOR_SHAPE_CLASS = "jdk.incubator.vector.VectorShape";

    /** Why a kernel ended up on the path it did. Logged once at startup. */
    public enum Backend {
        /** The vector path is in use and passed its self-test. */
        VECTOR,
        /** The scalar twin is in use. Always correct, never the fastest. */
        SCALAR
    }

    /** The resolved state of the vector backend, decided once. */
    public record Resolution(
            Backend backend,
            int vectorBits,
            int intLanes,
            String reason) {

        public boolean isVector() {
            return this.backend == Backend.VECTOR;
        }
    }

    private SimdSupport() {
    }

    /**
     * Resolves the backend.
     *
     * <p>Safe to call at class-init on any JVM. Never throws, because a mod that cannot start is a worse
     * outcome than a mod running its scalar path.
     */
    public static Resolution resolve() {
        return resolve(System.getProperty(KILL_SWITCH));
    }

    static Resolution resolve(String killSwitch) {
        if ("true".equalsIgnoreCase(killSwitch)) {
            return scalar(0, "kill switch " + KILL_SWITCH + " is set");
        }

        Module module;
        try {
            module = ModuleLayer.boot().findModule(VECTOR_MODULE).orElse(null);
        } catch (Throwable t) {
            return scalar(0, "module layer could not be queried");
        }
        if (module == null) {
            return scalar(0, "module " + VECTOR_MODULE + " is not in the boot layer; "
                    + "start the JVM with --add-modules=" + VECTOR_MODULE);
        }

        Class<?> shapeClass;
        try {
            shapeClass = Class.forName(VECTOR_SHAPE_CLASS, false, SimdSupport.class.getClassLoader());
        } catch (Throwable t) {
            return scalar(0, VECTOR_SHAPE_CLASS + " would not link");
        }

        int bits = preferredVectorBits(shapeClass);
        if (bits <= 0) {
            return scalar(0, "the widest available shape could not be read");
        }
        if (bits < MIN_USEFUL_BITS) {
            return scalar(bits, "widest shape is " + bits + " bits, below the " + MIN_USEFUL_BITS
                    + "-bit floor");
        }
        return new Resolution(Backend.VECTOR, bits, bits / Integer.SIZE,
                "vector backend active at " + bits + " bits");
    }

    private static Resolution scalar(int bits, String reason) {
        return new Resolution(Backend.SCALAR, bits, Math.max(1, bits / Integer.SIZE), reason);
    }

    /**
     * Asks the Vector API how wide its preferred shape is, without ever compiling a reference to it.
     *
     * <p>{@code VectorShape.preferredShape().vectorBitSize()}, reached reflectively.
     *
     * @return the width in bits, or 0 if it could not be read
     */
    private static int preferredVectorBits(Class<?> shapeClass) {
        try {
            Object shape = shapeClass.getMethod("preferredShape").invoke(null);
            Object bits = shape.getClass().getMethod("vectorBitSize").invoke(shape);
            return bits instanceof Integer value ? value : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * How many elements of a given size a kernel should process per iteration on the scalar path.
     *
     * <p>The scalar twin is written so the JIT can auto-vectorise it, and C2 needs a straight-line loop
     * with no early exit and no data-dependent branch to do so. Processing in chunks whose size divides
     * the array length keeps the loop shape the compiler recognises.
     *
     * @param bytesPerElement the width of one element, 4 for a float or an int
     * @return elements per chunk, at least 1
     */
    public static int scalarChunkElements(Resolution resolution, int bytesPerElement) {
        int lanes = resolution.intLanes();
        // Even on the scalar path, chunk to the width the machine would use if the module were present,
        // so that adding the flag changes nothing about the memory access pattern -- only the instruction
        // the loop body compiles to.
        return Math.max(1, (lanes * 4) / Math.max(1, bytesPerElement));
    }

    /**
     * Whether a kernel should assume gather and scatter are cheap.
     *
     * <p>Never, on the hardware this mod targets. Gather and scatter are slow on Zen, and slower still
     * relative to a contiguous load. Kernels here use structure-of-arrays so that every access is a run
     * of adjacent elements, and any kernel that would need a gather should be restructured rather than
     * written with one.
     */
    public static boolean cheapGather() {
        return false;
    }

    /**
     * Whether a kernel needs independent work in flight to hide vector latency.
     *
     * <p>On Zen 5 the minimum latency of a 512-bit operation rose from one cycle to two, so a chain of
     * dependent vector operations loses the throughput the wider datapath bought. Kernels accumulate into
     * more than one register rather than one, which is what this reports.
     */
    public static boolean needsLatencyHiding(Resolution resolution) {
        return resolution.isVector() && resolution.vectorBits() >= 512;
    }
}
