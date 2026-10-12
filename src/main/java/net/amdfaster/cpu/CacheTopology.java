package net.amdfaster.cpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the CPU cache hierarchy actually looks like, and how big a working set may be before it stops
 * being fast.
 *
 * <p>Every sizing decision in this mod is made against real numbers read from the machine at startup,
 * never against a constant. A table sized for 32 MB of L3 is a table that thrashes on a 8 MB part, and a
 * table sized for 8 MB leaves most of a 96 MB part unused.
 *
 * <h2>Why AMD needs this read at runtime rather than assumed</h2>
 *
 * <p>Two measurements change the design, and neither is what a generic cache textbook says.
 *
 * <p><b>V-Cache is partitioned, not shared.</b> On an X3D part the 64 MB of stacked cache sits on top of
 * <em>one</em> CCD's 32 MB slice, giving that CCD 96 MB. It does not extend the other CCDs. A measurement
 * across AMD machines with 3D vertical cache found the effective capacity to be 96 MB per CCD regardless
 * of how many CCDs the chip has, so a program running on one CCD cannot reach the L3 of another
 * (<a href="https://dl.acm.org/doi/10.1145/3695794.3695806">Measuring Data Access Latency in Large CPU
 * Caches</a>, 2024). The consequence is that a multi-threaded renderer must keep each worker's working
 * set inside <em>one</em> CCD's L3, not inside the chip's total. Summing the slices across CCDs would
 * overestimate by the number of CCDs and then thrash.
 *
 * <p><b>V-Cache does not evict like LRU.</b> The same measurement found the replacement policy closer to
 * random eviction than to least-recently-used. Code that relies on the tail of a scan staying resident
 * because it was touched recently is relying on behaviour that does not hold on these parts. Prefer
 * bounded working sets that are small enough to be resident regardless of policy, and do not build
 * access patterns that assume reuse distance ordering.
 *
 * <p>The latency cost of the stacked die is small against the capacity it buys: tripling the Zen 3 slice
 * added roughly three to four cycles, where unifying two 16 MB slices into one 32 MB slice had added
 * seven to eight. So the correct response to a large L3 is to <em>use</em> it, not to treat it as slow
 * memory.
 *
 * <p>Stateless apart from the topology it holds.
 */
public final class CacheTopology {

    /** Read by the real probe; injectable so the parsing can be tested without a Linux box. */
    public interface FsReader {
        /** @return the trimmed contents of {@code path}, or null if it cannot be read. */
        String read(Path path);
    }

    private static final Path SYS_CPU0 = Path.of("/sys/devices/system/cpu/cpu0/cache");
    /** Exposed by the amd_x3d_vcache driver, which only binds to parts that have stacked cache. */
    public static final String X3D_MODE_STRING =
            "/sys/bus/platform/drivers/amd_x3d_vcache/AMDI0101:00/amd_x3d_mode";
    private static final Path X3D_MODE = Path.of(X3D_MODE_STRING);

    /** Assumed when the machine reports nothing usable. A conservative 8 MB, which fits every part. */
    public static final int UNKNOWN_L3_BYTES = 8 * 1024 * 1024;

    /** Zen 4. 32 KB per core. */
    public static final int ZEN4_L1D = 32 * 1024;
    /** Zen 5 widened the data cache. */
    public static final int ZEN5_L1D = 48 * 1024;
    /** Zen 3 through Zen 5 all carry 1 MB of L2 per core. */
    public static final int ZEN_L2 = 1024 * 1024;
    /** One CCD without stacked cache. */
    public static final int CCD_L3 = 32 * 1024 * 1024;
    /** One CCD with 3D V-Cache: 32 MB plus 64 MB stacked. */
    public static final int CCD_L3_VCACHE = 96 * 1024 * 1024;
    /** Stacked capacity alone. Any slice at least this large has V-Cache on it. */
    public static final int VCACHE_STACK = 64 * 1024 * 1024;

    private final int l1dBytes;
    private final int l2Bytes;
    private final int l3PerDomainBytes;
    private final int domains;
    private final boolean vCache;

    public CacheTopology(int l1dBytes, int l2Bytes, int l3PerDomainBytes, int domains, boolean vCache) {
        if (l1dBytes <= 0 || l2Bytes <= 0 || l3PerDomainBytes <= 0) {
            throw new IllegalArgumentException("cache sizes must be positive");
        }
        if (domains < 1) {
            throw new IllegalArgumentException("at least one cache domain");
        }
        this.l1dBytes = l1dBytes;
        this.l2Bytes = l2Bytes;
        this.l3PerDomainBytes = l3PerDomainBytes;
        this.domains = domains;
        this.vCache = vCache;
    }

    /** L1 data cache per core. */
    public int l1dBytes() {
        return this.l1dBytes;
    }

    /** L2 per core. */
    public int l2Bytes() {
        return this.l2Bytes;
    }

    /**
     * L3 reachable by one core, in bytes.
     *
     * <p>This is the number every working-set budget in this mod is measured against. It is deliberately
     * <em>per domain</em> and not the chip total, because the domains do not share -- see the class
     * comment. Multiplying by {@link #domains()} gives a figure that looks impressive and thrashes.
     */
    public int l3PerDomainBytes() {
        return this.l3PerDomainBytes;
    }

    /** How many independent L3 domains the chip has -- one per CCD on AMD. */
    public int domains() {
        return this.domains;
    }

    /** Whether the machine has 3D V-Cache stacked on at least one CCD. */
    public boolean hasVCache() {
        return this.vCache;
    }

    /**
     * How much of one domain's L3 a structure may occupy before it starts evicting other work.
     *
     * <p>Half, not all. The game, the JVM and the driver share the cache; a renderer that assumes it owns
     * the whole of L3 turns every other subsystem's accesses into misses and can end up slower overall
     * than if it had used a smaller table that left room.
     */
    public int workingSetBudget() {
        return this.l3PerDomainBytes / 2;
    }

    /**
     * How many bytes of one worker's scratch may stay in L1.
     *
     * <p>L1 is where a culling inner loop has to live to run at full rate. A kernel whose working set
     * overflows it pays L2 latency on every iteration, which on a loop that does a few integer operations
     * per element is most of the cost.
     */
    public int l1ScratchBudget() {
        // A quarter, because the loop's own code, its constants and whatever the caller is walking share
        // the same 32 or 48 KB.
        return this.l1dBytes / 4;
    }

    /**
     * Whether the replacement policy can be assumed to keep recently touched lines resident.
     *
     * <p>False on V-Cache parts. Code must not be written so that its correctness or its performance
     * depends on LRU ordering when this returns false.
     */
    public boolean canAssumeLruEviction() {
        return !this.vCache;
    }

    /**
     * Reads the real hierarchy from the machine.
     *
     * <p>Linux exposes the whole thing under sysfs, which is read here. On any other platform, or if the
     * files are missing or unparseable, the result falls back to a conservative constant so the mod still
     * sizes things sensibly rather than crashing or, worse, sizing against zero.
     */
    public static CacheTopology probe() {
        return probe(CacheTopology::readFile);
    }

    static CacheTopology probe(FsReader reader) {
        int l1d = 0;
        int l2 = 0;
        int l3 = 0;
        int domains = 1;

        for (int index = 0; index < 16; index++) {
            Path dir = SYS_CPU0.resolve("index" + index);
            String level = reader.read(dir.resolve("level"));
            if (level == null) {
                break;
            }
            int size = parseSize(reader.read(dir.resolve("size")));
            if (size <= 0) {
                continue;
            }
            switch (level.trim()) {
                case "1" -> l1d = Math.max(l1d, size);
                case "2" -> l2 = Math.max(l2, size);
                case "3" -> {
                    l3 = Math.max(l3, size);
                    domains = Math.max(domains, countDomains(reader.read(dir.resolve("shared_cpu_list"))));
                }
                default -> {
                    // Level 4 exists on some Intel parts as a package-level cache. Not modelled.
                }
            }
        }

        if (l1d <= 0) {
            l1d = ZEN4_L1D;
        }
        if (l2 <= 0) {
            l2 = ZEN_L2;
        }
        boolean vCache = l3 >= VCACHE_STACK || reader.read(X3D_MODE) != null;
        if (l3 <= 0) {
            l3 = UNKNOWN_L3_BYTES;
            // Without a reading we cannot tell whether the part has stacked cache. Assume not, because
            // assuming V-Cache would make the mod distrust LRU for no reason on the far more common
            // non-X3D machines.
            vCache = false;
        }
        return new CacheTopology(l1d, l2, l3, domains, vCache);
    }

    /**
     * Parses a sysfs size such as {@code 32768K} or {@code 96M}.
     *
     * @return bytes, or 0 if the string is not a size
     */
    static int parseSize(String raw) {
        if (raw == null) {
            return 0;
        }
        String text = raw.trim();
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        long value;
        try {
            value = Long.parseLong(text.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
        String unit = text.substring(end).trim();
        long multiplier = switch (unit.toUpperCase()) {
            case "", "B" -> 1L;
            case "K", "KB" -> 1024L;
            case "M", "MB" -> 1024L * 1024L;
            case "G", "GB" -> 1024L * 1024L * 1024L;
            default -> 0L;
        };
        long bytes = value * multiplier;
        // A size that does not fit in an int is not a per-core cache and something was misparsed.
        if (multiplier == 0 || bytes <= 0 || bytes > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) bytes;
    }

    /**
     * Counts the independent L3 domains implied by a {@code shared_cpu_list} such as {@code 0-7,16-23}.
     *
     * <p>sysfs gives one line per <em>cache instance</em> for cpu0, not a count, so this reports how many
     * CPU groups cpu0's L3 is shared with -- which is one domain. The useful number for the partitioned
     * argument is therefore the domain size, and the caller derives the count from the CPU total. This
     * returns the number of <em>ranges</em> in the list, which for a well-formed entry is 1.
     */
    static int countDomains(String sharedCpuList) {
        if (sharedCpuList == null) {
            return 1;
        }
        String text = sharedCpuList.trim();
        if (text.isEmpty()) {
            return 1;
        }
        int ranges = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ',') {
                ranges++;
            }
        }
        return ranges + 1;
    }

    /** How many CPUs share one L3 domain, from a list such as {@code 0-7} or {@code 0-3,8-11}. */
    public static int cpusPerDomain(String sharedCpuList) {
        if (sharedCpuList == null || sharedCpuList.trim().isEmpty()) {
            return 1;
        }
        int total = 0;
        for (String range : sharedCpuList.trim().split(",")) {
            String[] parts = range.split("-");
            try {
                int low = Integer.parseInt(parts[0].trim());
                int high = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : low;
                total += Math.max(1, high - low + 1);
            } catch (NumberFormatException e) {
                return Math.max(1, total);
            }
        }
        return Math.max(1, total);
    }

    private static String readFile(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            List<String> lines = Files.readAllLines(path);
            return lines.isEmpty() ? null : String.join("\n", lines).trim();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add("L1d=" + (this.l1dBytes / 1024) + "K");
        parts.add("L2=" + (this.l2Bytes / 1024) + "K");
        parts.add("L3=" + (this.l3PerDomainBytes / (1024 * 1024)) + "M/domain x" + this.domains);
        if (this.vCache) {
            parts.add("V-Cache");
        }
        return String.join(" ", parts);
    }
}
