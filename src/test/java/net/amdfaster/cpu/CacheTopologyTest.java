package net.amdfaster.cpu;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheTopologyTest {

    private static CacheTopology.FsReader fake(Map<String, String> files) {
        return path -> files.get(path.toString());
    }

    /** The sysfs layout of a Ryzen 7 7800X3D: 32K L1d, 1M L2, 96M L3 over 8 threads on one CCD. */
    private static Map<String, String> x3dFiles() {
        String base = "/sys/devices/system/cpu/cpu0/cache/";
        Map<String, String> f = new HashMap<>();
        f.put(base + "index0/level", "1");
        f.put(base + "index0/size", "32K");
        f.put(base + "index1/level", "2");
        f.put(base + "index1/size", "1024K");
        f.put(base + "index2/level", "3");
        f.put(base + "index2/size", "96M");
        f.put(base + "index2/shared_cpu_list", "0-7");
        return f;
    }

    @Test
    void anX3dPartIsDetectedAndItsWorkingSetBudgetTripled() {
        CacheTopology topology = CacheTopology.probe(fake(x3dFiles()));

        assertEquals(32 * 1024, topology.l1dBytes());
        assertEquals(1024 * 1024, topology.l2Bytes());
        assertEquals(96 * 1024 * 1024, topology.l3PerDomainBytes());
        assertTrue(topology.hasVCache(), "96M is 32M plus a 64M stack");
        assertEquals(48 * 1024 * 1024, topology.workingSetBudget());
    }

    @Test
    void aPlainCcdIsNotMistakenForVCache() {
        Map<String, String> files = x3dFiles();
        files.put("/sys/devices/system/cpu/cpu0/cache/index2/size", "32M");
        CacheTopology topology = CacheTopology.probe(fake(files));

        assertFalse(topology.hasVCache(), "32M is a normal CCD slice");
        assertEquals(16 * 1024 * 1024, topology.workingSetBudget());
    }

    @Test
    void theX3dModeSysfsNodeIsAlsoEvidenceOfStackedCache() {
        // Some kernels report the slice size without the stack folded in but still expose the V-Cache
        // scheduler driver, which only binds to parts that have it.
        Map<String, String> files = x3dFiles();
        files.put("/sys/devices/system/cpu/cpu0/cache/index2/size", "32M");
        files.put("/sys/bus/platform/drivers/amd_x3d_vcache/AMDI0101:00/amd_x3d_mode", "cache");

        assertTrue(CacheTopology.probe(fake(files)).hasVCache());
    }

    @Test
    void theWorkingSetBudgetIsHalfTheDomainNotTheWholeOfIt() {
        // The game, the JVM and the driver share this cache. A renderer that budgets against the whole
        // of L3 turns every other subsystem's access into a miss and can end up slower than one that
        // used a smaller table and left room.
        CacheTopology topology = CacheTopology.probe(fake(x3dFiles()));
        assertEquals(topology.l3PerDomainBytes() / 2, topology.workingSetBudget());
        assertTrue(topology.workingSetBudget() < topology.l3PerDomainBytes());
    }

    @Test
    void theBudgetIsPerDomainBecauseVCacheIsPartitioned() {
        // A measurement across AMD machines with 3D vertical cache found 96 MB effective per CCD no
        // matter how many CCDs the chip has: a program on one CCD cannot reach another's L3. Budgeting
        // against the chip total would overestimate by the CCD count and then thrash.
        CacheTopology twoDomain = new CacheTopology(32 * 1024, 1024 * 1024, 96 * 1024 * 1024, 2, true);
        assertEquals(2, twoDomain.domains());
        assertEquals(96 * 1024 * 1024, twoDomain.l3PerDomainBytes(),
                "not 192M -- the domains do not share");
        assertEquals(48 * 1024 * 1024, twoDomain.workingSetBudget());
    }

    @Test
    void vCacheCannotBeAssumedToEvictLeastRecentlyUsed() {
        // The same measurement found the stacked cache's replacement policy closer to random than to
        // LRU. Anything that depends on recently touched lines still being resident is depending on
        // behaviour these parts do not provide.
        assertFalse(CacheTopology.probe(fake(x3dFiles())).canAssumeLruEviction());
        Map<String, String> plain = x3dFiles();
        plain.put("/sys/devices/system/cpu/cpu0/cache/index2/size", "32M");
        assertTrue(CacheTopology.probe(fake(plain)).canAssumeLruEviction());
    }

    @Test
    void anUnreadableMachineFallsBackToSomethingConservative() {
        // No reading must not mean a zero-sized cache, which would make every budget zero and every
        // table degenerate. It also must not guess V-Cache, because assuming a random replacement policy
        // on the far more common non-X3D part would throw away reuse for nothing.
        CacheTopology topology = CacheTopology.probe(fake(new HashMap<>()));

        assertEquals(CacheTopology.UNKNOWN_L3_BYTES, topology.l3PerDomainBytes());
        assertEquals(CacheTopology.ZEN4_L1D, topology.l1dBytes());
        assertEquals(CacheTopology.ZEN_L2, topology.l2Bytes());
        assertFalse(topology.hasVCache());
        assertEquals(1, topology.domains());
    }

    @Test
    void sysfsSizesParseInEveryUnitTheyAppearIn() {
        assertEquals(32 * 1024, CacheTopology.parseSize("32K"));
        assertEquals(1024 * 1024, CacheTopology.parseSize("1024K"));
        assertEquals(96 * 1024 * 1024, CacheTopology.parseSize("96M"));
        assertEquals(8 * 1024 * 1024, CacheTopology.parseSize("8192K"));
        assertEquals(48 * 1024, CacheTopology.parseSize("  48K  "), "surrounding whitespace");
        assertEquals(1024, CacheTopology.parseSize("1024"), "a bare number is bytes");
    }

    @Test
    void unparseableSizesReportZeroRatherThanThrowing() {
        // These come from the kernel, not from us, and a malformed one must not stop the mod starting.
        assertEquals(0, CacheTopology.parseSize(null));
        assertEquals(0, CacheTopology.parseSize(""));
        assertEquals(0, CacheTopology.parseSize("abc"));
        assertEquals(0, CacheTopology.parseSize("1024X"), "an unknown unit is not a size");
        assertEquals(0, CacheTopology.parseSize("0K"));
        assertEquals(0, CacheTopology.parseSize("2G"), "too large to be a per-core cache");
    }

    @Test
    void aCpuListCountsEveryThreadSharingTheDomain() {
        assertEquals(8, CacheTopology.cpusPerDomain("0-7"));
        assertEquals(16, CacheTopology.cpusPerDomain("0-7,16-23"), "two ranges on an SMT part");
        assertEquals(8, CacheTopology.cpusPerDomain("0-3,8-11"), "discontiguous");
        assertEquals(1, CacheTopology.cpusPerDomain("0"));
        assertEquals(1, CacheTopology.cpusPerDomain(null));
    }

    @Test
    void theL1ScratchBudgetLeavesRoomForTheCaller() {
        // A quarter, because the loop's own code, its constants and whatever the caller is walking share
        // the same 32 or 48 KB. A kernel that assumed it owned all of L1 would spill on every iteration.
        assertEquals(8192, new CacheTopology(CacheTopology.ZEN4_L1D, CacheTopology.ZEN_L2,
                CacheTopology.CCD_L3, 1, false).l1ScratchBudget(), "Zen 4");
        assertEquals(12288, new CacheTopology(CacheTopology.ZEN5_L1D, CacheTopology.ZEN_L2,
                CacheTopology.CCD_L3, 1, false).l1ScratchBudget(), "Zen 5 widened L1d");
    }

    @Test
    void nonsenseSizesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheTopology(0, 1024, 1024, 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheTopology(1024, 1024, 1024, 0, false));
    }

    @Test
    void theX3dPathConstantMatchesTheKernelLayout() {
        // If this drifts the probe silently reads nothing and falls back, which looks like success.
        assertTrue(Path.of("/sys/devices/system/cpu/cpu0/cache").isAbsolute());
        assertTrue(CacheTopology.X3D_MODE_STRING.contains("amd_x3d_vcache"));
        assertTrue(CacheTopology.X3D_MODE_STRING.contains("AMDI0101:00"), "the ACPI device id");
    }
}
