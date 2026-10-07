package net.amdfaster.vk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Chooses which of a physical device's memory types a buffer should be bound to.
 *
 * <p>Kept free of Vulkan types on purpose: the choice is the part worth testing, and it depends on
 * AMD's memory topology, which differs between discrete cards, cards with Resizable BAR enabled and
 * APUs. The property flag constants below mirror {@code VkMemoryPropertyFlagBits} so a caller can
 * pass the driver's values straight through.
 */
public final class MemoryTypeSelector {

    // Mirrors VkMemoryPropertyFlagBits. Kept here so this class stays testable without a device.
    public static final int DEVICE_LOCAL = 0x00000001;
    public static final int HOST_VISIBLE = 0x00000002;
    public static final int HOST_COHERENT = 0x00000004;
    public static final int HOST_CACHED = 0x00000008;
    public static final int LAZILY_ALLOCATED = 0x00000010;

    /** What the buffer is for. The four cases AMD's topology actually distinguishes. */
    public enum Usage {
        /**
         * Only the GPU touches it: meshes, textures, the meshlet index buffer.
         * Wants {@code DEVICE_LOCAL}, and on a discrete card wants the *largest* device-local heap,
         * which is the visible one once Resizable BAR is on.
         */
        GPU_ONLY,

        /**
         * Written by the CPU every frame and streamed to the GPU: the staging buffer for mesh
         * uploads. Wants {@code HOST_VISIBLE | HOST_COHERENT} and specifically <em>not</em>
         * {@code HOST_CACHED}: an uncached mapping is write-combined, which is the fastest possible
         * path for the sequential memcpy we do, and we never read it back.
         */
        CPU_TO_GPU,

        /** Read back by the CPU: query results, readback of compute output. Wants HOST_CACHED. */
        GPU_TO_CPU,

        /**
         * Mapped once and written in place by the CPU while the GPU reads it: the per-frame
         * uniform and index ring. On an APU or a card with a large BAR this lands on a single
         * {@code DEVICE_LOCAL | HOST_VISIBLE} heap and costs no copy at all -- which is the whole
         * reason the APU path exists.
         */
        CPU_AND_GPU
    }

    /** One entry of {@code VkPhysicalDeviceMemoryProperties}: a type, the heap it lives on, its size. */
    public record MemoryType(int index, int heapIndex, long heapSize, int propertyFlags) {

        public boolean has(int flags) {
            return (this.propertyFlags & flags) == flags;
        }
    }

    /** The chosen type index, plus why, so a report can show what topology was detected. */
    public record Selection(int typeIndex, Usage usage, String reason) {
    }

    private MemoryTypeSelector() {
    }

    /**
     * @return the best type index for the usage
     * @throws IllegalStateException if no type on this device can serve it, with the reason
     */
    public static Selection select(List<MemoryType> types, Usage usage) {
        return switch (usage) {
            // No preference against host visibility: with Resizable BAR or on an APU the entire
            // device-local heap is host-visible, and preferring otherwise would push meshes into
            // the small invisible heap that is really meant for transient attachments.
            case GPU_ONLY -> require(types, usage, DEVICE_LOCAL, t -> true, "device-local, GPU-only");
            case CPU_TO_GPU -> require(types, usage, HOST_VISIBLE | HOST_COHERENT,
                    t -> !t.has(HOST_CACHED), "host-visible, write-combined (not host-cached)");
            case GPU_TO_CPU -> require(types, usage, HOST_VISIBLE, t -> t.has(HOST_CACHED),
                    "host-visible, host-cached");
            case CPU_AND_GPU -> {
                // Without Resizable BAR -- or with only the classic 256 MiB window, which is too
                // small to hold a frame's working set -- the copy-free path is unavailable and the
                // ring lives in system memory and gets copied. Say so, do not fail.
                MemoryType unified = hasUnifiedHostDeviceMemory(types)
                        ? find(types, DEVICE_LOCAL | HOST_VISIBLE, t -> !t.has(HOST_CACHED))
                        : null;
                if (unified != null) {
                    yield new Selection(unified.index(), usage,
                            "device-local and host-visible (UMA or large BAR): no staging copy needed");
                }
                MemoryType system = find(types, HOST_VISIBLE | HOST_COHERENT, t -> !t.has(HOST_CACHED));
                if (system == null) {
                    throw new IllegalStateException("no memory type supports " + usage
                            + " among " + types.size() + " types");
                }
                yield new Selection(system.index(), usage,
                        "no unified device-local+host-visible heap; a staging copy is required");
            }
        };
    }

    /**
     * True when one heap is both device-local and host-visible and holds most of the device's
     * memory -- the signature of an APU, or of a discrete card with Resizable BAR / Smart Access
     * Memory enabled. Both get the copy-free path, which is the whole reason the APU target exists.
     */
    public static boolean hasUnifiedHostDeviceMemory(List<MemoryType> types) {
        long deviceLocal = types.stream()
                .filter(t -> t.has(DEVICE_LOCAL))
                .mapToLong(t -> t.heapSize())
                .max()
                .orElse(0L);
        long unified = types.stream()
                .filter(t -> t.has(DEVICE_LOCAL | HOST_VISIBLE))
                .mapToLong(t -> t.heapSize())
                .max()
                .orElse(0L);
        // Half of VRAM is the line between "the classic 256 MiB BAR window" and "Resizable BAR or
        // an APU, where everything the GPU can see the CPU can write".
        return deviceLocal > 0 && unified * 2 >= deviceLocal;
    }

    private static Selection require(List<MemoryType> types, Usage usage, int required,
                                     Predicate<MemoryType> preferred, String why) {
        MemoryType found = find(types, required, preferred);
        if (found == null) {
            throw new IllegalStateException("no memory type supports " + usage + " (" + why + ") among "
                    + types.size() + " types");
        }
        return new Selection(found.index(), usage, why);
    }

    /**
     * Largest heap among the types that carry {@code required} and satisfy {@code preferred}, or
     * the largest that merely carries {@code required} if none is preferred, or {@code null}.
     */
    private static MemoryType find(List<MemoryType> types, int required, Predicate<MemoryType> preferred) {
        List<MemoryType> usable = new ArrayList<>();
        List<MemoryType> wanted = new ArrayList<>();
        for (MemoryType t : types) {
            // Lazily allocated memory backs transient attachments; it is not somewhere to put a
            // buffer we intend to keep.
            if (!t.has(required) || t.has(LAZILY_ALLOCATED)) {
                continue;
            }
            usable.add(t);
            if (preferred.test(t)) {
                wanted.add(t);
            }
        }
        // Largest heap wins: on a discrete card that is the visible half of VRAM, which is where a
        // big BAR puts everything; on an APU it is the one and only heap.
        return largest(wanted.isEmpty() ? usable : wanted);
    }

    private static MemoryType largest(List<MemoryType> pool) {
        MemoryType best = null;
        for (MemoryType t : pool) {
            if (best == null || t.heapSize() > best.heapSize()) {
                best = t;
            }
        }
        return best;
    }
}
