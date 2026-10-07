package net.amdfaster.vk;

import net.amdfaster.vk.DeviceScorer.Candidate;
import net.amdfaster.vk.DeviceScorer.Ranked;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.amdfaster.vk.DeviceScorer.DEVICE_TYPE_CPU;
import static net.amdfaster.vk.DeviceScorer.DEVICE_TYPE_DISCRETE_GPU;
import static net.amdfaster.vk.DeviceScorer.DEVICE_TYPE_INTEGRATED_GPU;
import static net.amdfaster.vk.DeviceScorer.DEVICE_TYPE_OTHER;
import static net.amdfaster.vk.DeviceScorer.DEVICE_TYPE_VIRTUAL_GPU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceScorerTest {

    private static final long GIB = 1L << 30;

    private static Candidate amd(int type, long gib) {
        return new Candidate("AMD Radeon RX 7900 XTX", type, gib * GIB, 0x00403000, true);
    }

    @Test
    void discreteBeatsIntegratedWhoeverMadeIt() {
        List<Ranked> ranked = DeviceScorer.rank(List.of(
                new Candidate("AMD Radeon 780M", DEVICE_TYPE_INTEGRATED_GPU, 8 * GIB, 0x00403000, true),
                new Candidate("NVIDIA GeForce RTX 4060", DEVICE_TYPE_DISCRETE_GPU, 8 * GIB, 0x00403000, false)));
        assertEquals("NVIDIA GeForce RTX 4060", ranked.get(0).candidate().deviceName());
    }

    @Test
    void moreDeviceLocalMemoryBeatsTheVendorPreference() {
        // The tuning is calibrated for RDNA, but a 4 GiB card that cannot hold a frame's worth of
        // meshlets is the wrong adapter no matter who made it.
        List<Ranked> ranked = DeviceScorer.rank(List.of(
                new Candidate("AMD Radeon RX 550", DEVICE_TYPE_DISCRETE_GPU, 4 * GIB, 0x00402000, true),
                new Candidate("NVIDIA GeForce RTX 4090", DEVICE_TYPE_DISCRETE_GPU, 24 * GIB, 0x00403000, false)));
        assertEquals("NVIDIA GeForce RTX 4090", ranked.get(0).candidate().deviceName());
    }

    @Test
    void onATieTheAmdAdapterWins() {
        List<Ranked> ranked = DeviceScorer.rank(List.of(
                new Candidate("Intel Arc A770", DEVICE_TYPE_DISCRETE_GPU, 16 * GIB, 0x00403000, false),
                new Candidate("AMD Radeon RX 7800 XT", DEVICE_TYPE_DISCRETE_GPU, 16 * GIB, 0x00403000, true)));
        assertEquals("AMD Radeon RX 7800 XT", ranked.get(0).candidate().deviceName());
    }

    @Test
    void aSoftwareRasteriserIsLast() {
        List<Ranked> ranked = DeviceScorer.rank(List.of(
                new Candidate("llvmpipe", DEVICE_TYPE_CPU, 16 * GIB, 0x00403000, false),
                amd(DEVICE_TYPE_INTEGRATED_GPU, 8)));
        assertEquals("llvmpipe", ranked.get(1).candidate().deviceName());
    }

    @Test
    void deviceClassesAreOrdered() {
        assertTrue(DeviceScorer.typeRank(DEVICE_TYPE_DISCRETE_GPU)
                > DeviceScorer.typeRank(DEVICE_TYPE_INTEGRATED_GPU));
        assertTrue(DeviceScorer.typeRank(DEVICE_TYPE_INTEGRATED_GPU)
                > DeviceScorer.typeRank(DEVICE_TYPE_VIRTUAL_GPU));
        assertTrue(DeviceScorer.typeRank(DEVICE_TYPE_VIRTUAL_GPU)
                > DeviceScorer.typeRank(DEVICE_TYPE_OTHER));
        assertTrue(DeviceScorer.typeRank(DEVICE_TYPE_OTHER) > DeviceScorer.typeRank(DEVICE_TYPE_CPU));
    }

    @Test
    void aHigherApiVersionBreaksATie() {
        List<Ranked> ranked = DeviceScorer.rank(List.of(
                new Candidate("AMD Radeon RX 580", DEVICE_TYPE_DISCRETE_GPU, 8 * GIB, 0x00401000, true),
                new Candidate("AMD Radeon RX 6800", DEVICE_TYPE_DISCRETE_GPU, 8 * GIB, 0x00403000, true)));
        assertEquals("AMD Radeon RX 6800", ranked.get(0).candidate().deviceName());
    }

    @Test
    void rankingIsDeterministicWhenEverythingMatches() {
        Candidate a = new Candidate("AMD Radeon Pro W6800", DEVICE_TYPE_DISCRETE_GPU, 8 * GIB, 0x00403000, true);
        Candidate b = new Candidate("AMD Radeon RX 6800", DEVICE_TYPE_DISCRETE_GPU, 8 * GIB, 0x00403000, true);
        assertEquals(DeviceScorer.rank(List.of(a, b)).get(0).candidate().deviceName(),
                DeviceScorer.rank(List.of(b, a)).get(0).candidate().deviceName(),
                "the order must not depend on enumeration order");
    }

    @Test
    void theReasonNamesTheClassAndTheMemory() {
        String reason = DeviceScorer.rank(List.of(amd(DEVICE_TYPE_DISCRETE_GPU, 24))).get(0).reason();
        assertTrue(reason.contains("discrete GPU"), reason);
        assertTrue(reason.contains("24 GiB device-local"), reason);
        assertTrue(reason.contains("AMD"), reason);
    }

    @Test
    void smallMemoryIsReportedInMiB() {
        String reason = DeviceScorer.rank(List.of(
                new Candidate("AMD Radeon R5", DEVICE_TYPE_INTEGRATED_GPU, 512L << 20, 0x00402000, true)))
                .get(0).reason();
        assertTrue(reason.contains("512 MiB device-local"), reason);
    }

    @Test
    void anEmptyListRanksToAnEmptyList() {
        assertEquals(0, DeviceScorer.rank(List.of()).size());
    }
}
