package net.amdfaster.gpu;

import net.amdfaster.platform.AmdArchitecture;

import java.util.Arrays;

/**
 * Assigns SPIR-V specialization constant IDs to the shader properties that are worth folding, and caps
 * how many pipelines that produces.
 *
 * <p>A specialization constant is a placeholder inside a SPIR-V module that takes its value when the
 * pipeline is created. The driver then compiles against that value, so a branch on it is evaluated once
 * at compile time rather than once per fragment, the untaken side is deleted rather than skipped, a loop
 * bounded by it is unrolled, and the registers the dead path wanted are freed -- which on a wave-limited
 * shader can raise occupancy as well as shorten the program. Leaving the same decision in a uniform keeps
 * the branch in the binary: uniform branching is cheap because every lane in the wave takes the same way,
 * but cheap is not free, and the code for both sides still occupies instruction cache and registers.
 *
 * <p>The measured effect is not marginal. The Vulkan samples report 4.4 per cent on a shader simple enough
 * that there is almost nothing to fold, and a recompiler that moved its texture decode from a uniform to a
 * specialization constant per fetch slot cut frame GPU time by 21 to 28 per cent on RDNA. That is the
 * reason to do this rather than leave every mode in a push constant.
 *
 * <p>What it costs is a pipeline per distinct value, and that is the part worth planning. Each one is
 * compiled on first use, which is a hitch, and each one occupies pipeline cache and GPU memory. The
 * bound that works in practice is small -- the recompiler above keeps four variants per key and queues
 * any further one at the lowest priority on a single worker, because a fifth is more likely to be a
 * one-off than a pattern. This planner applies the same reasoning: it multiplies the value counts of the
 * properties chosen for folding, refuses the set if the product exceeds the budget, and says so, rather
 * than quietly generating a permutation explosion that shows up as stutter.
 *
 * <p>The alternative is not free either. A ubershader driven by uniforms compiles once and never hitches,
 * but keeps every branch, and one measured comparison found generated variants roughly 0 to 10 per cent
 * faster than an ubershader with selected specialization constants. So the choice per property is a real
 * trade, and it is decided here on the numbers rather than by preferring one style everywhere.
 *
 * @see <a href="https://docs.vulkan.org/samples/latest/samples/performance/specialization_constants/README.html">
 *      Vulkan samples -- specialization constants over uniform buffer objects, 4.4 per cent on a simple
 *      sample, with the guidance to specialize all control flow</a>
 * @see <a href="https://docs.vulkan.org/guide/latest/mapping_data_to_shaders.html">Vulkan guide -- the
 *      three uses: toggling features, and improving backend optimisation via constant folding and dead
 *      code elimination</a>
 */
public final class SpecializationPlanner {

    /**
     * How many pipelines one shader may have before the compile cost outweighs the folded branches.
     *
     * <p>Four, matching what shipped variants-per-key in practice. Eight would be a 2 to the 3 set of
     * toggles and is reachable, but every one past the first four is more likely to be a rare
     * combination than a common one, so the tail costs compile time for pipelines that are bound once.
     */
    public static final int MAX_PIPELINES_PER_SHADER = 4;

    /** Specialization constant IDs are assigned from zero; this is the first one taken. */
    public static final int FIRST_ID = 0;

    /**
     * Bytes one specialization constant occupies in the {@code VkSpecializationInfo} data block.
     *
     * <p>The widest type allowed is a 64 bit double, and every entry is stored at that width so the map
     * entries can share one block with a fixed stride regardless of what the shader declared.
     */
    public static final int MAX_VALUE_BYTES = 8;

    /** Bytes per entry of the {@code VkSpecializationMapEntry} array: id, offset and size, all 32 bit. */
    public static final int MAP_ENTRY_BYTES = 12;

    private SpecializationPlanner() {
    }

    /**
     * One shader property that could be folded.
     *
     * @param distinctValues how many values this property takes across the draws that use the shader.
     *                       One means it never varies and folding it is free.
     * @param guardedWork    a relative measure of the shader work the property's branch guards: how many
     *                       instructions the untaken side would have run. Zero means the branch guards
     *                       nothing worth folding, so a pipeline for it buys no instructions back.
     * @param divergent      whether the value differs between lanes of one wave. A divergent property
     *                       cannot be a specialization constant at all, since the constant is one value
     *                       for the whole pipeline -- folding it would bake in one lane's answer for all
     *                       of them, which is a wrong result and not a slow one.
     */
    public record Property(String name, int distinctValues, int guardedWork, boolean divergent) {
    }

    /**
     * What folding the chosen set costs and saves.
     *
     * @param pipelines  the product of the chosen properties' value counts
     * @param assigned   the specialization IDs handed out, in the order the properties were given
     * @param specBytes  the {@code VkSpecializationInfo} payload: the value block plus the map entries
     * @param instructionsSaved relative instructions removed per shader invocation
     * @param withinBudget whether the pipeline count fits {@link #MAX_PIPELINES_PER_SHADER}
     */
    public record Plan(String shader, int[] assigned, int pipelines, int specBytes, int instructionsSaved,
                       boolean withinBudget) {

        /** How many properties were folded rather than left as uniforms. */
        public int folded() {
            return this.assigned.length;
        }

        /** Pipelines over the budget, which is the number that would have to be dropped. */
        public int overflow() {
            return withinBudget ? 0 : this.pipelines - MAX_PIPELINES_PER_SHADER;
        }
    }

    /**
     * Chooses which properties to fold and assigns their specialization IDs.
     *
     * <p>Properties are taken in the order given, most valuable first, and a property is skipped once
     * including it would push the pipeline product over {@link #MAX_PIPELINES_PER_SHADER}. Skipping
     * rather than failing matters: the caller asked for as much folding as fits, and refusing the whole
     * set because the last property did not fit would leave the earlier ones branching for no reason.
     * A divergent property is never folded, at any budget, for the reason in {@link Property}.
     *
     * <p>A property with one distinct value is folded whenever it guards work, since it costs no extra
     * pipeline -- the product does not move -- and removes the branch outright.
     */
    public static Plan plan(String shader, Property... properties) {
        require(shader != null, "a shader needs a name");
        require(properties != null, "properties must be given, even if empty");
        int[] assigned = new int[properties.length];
        int folded = 0;
        int pipelines = 1;
        int saved = 0;
        int nextId = FIRST_ID;
        for (Property property : properties) {
            require(property != null, "a null property");
            require(property.name() != null, "a property needs a name");
            require(property.distinctValues() >= 1, "a property takes at least one value");
            require(property.guardedWork() >= 0, "guarded work must not be negative");
            if (property.divergent()) {
                continue;
            }
            long product = (long) pipelines * property.distinctValues();
            if (product > MAX_PIPELINES_PER_SHADER) {
                continue;
            }
            pipelines = (int) product;
            // Folding a property that guards nothing removes no instructions, so it is not worth an ID
            // even when it is free -- unless it is the only value, in which case the compiler already
            // knows it and the branch was never really there.
            if (property.guardedWork() > 0 || property.distinctValues() == 1) {
                assigned[folded++] = nextId++;
                saved += property.guardedWork();
            }
        }
        int specBytes = folded * (MAX_VALUE_BYTES + MAP_ENTRY_BYTES);
        return new Plan(shader, Arrays.copyOf(assigned, folded), pipelines, specBytes, saved,
                pipelines <= MAX_PIPELINES_PER_SHADER);
    }

    /**
     * The specialization data size for a set of properties, whether or not they were chosen.
     *
     * <p>Worth knowing on its own: the map entry array and the value block are copied into every pipeline
     * creation, so a shader with many constants pays for all of them at each create even when only a few
     * change the compiled result.
     */
    public static int specBytes(int count) {
        require(count >= 0, "a count must not be negative");
        return count * (MAX_VALUE_BYTES + MAP_ENTRY_BYTES);
    }

    /**
     * Whether the workgroup size belongs in a specialization constant.
     *
     * <p>It does when the size is fixed for the device, which it is here: {@code local_size_x} bounded by
     * a constant lets the driver allocate shared memory and registers for exactly that many lanes rather
     * than for the maximum the shader could declare. The value is per architecture rather than per draw,
     * so it costs one pipeline either way and the folding is free.
     */
    public static boolean specializeWorkgroupSize(AmdArchitecture architecture) {
        require(architecture != null, "an architecture is needed");
        return architecture.recommendedWorkgroupSize() > 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
