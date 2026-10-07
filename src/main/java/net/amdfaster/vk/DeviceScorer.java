package net.amdfaster.vk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks the physical devices on the machine and says why.
 *
 * <p>AMD-Faster's tuning is calibrated for RDNA, so an AMD adapter is preferred -- but only as a
 * tie-break, and never to the exclusion of anything else. The mod has to load and render on a
 * machine that has nothing else. Device class and VRAM outrank vendor: a discrete GPU beats an
 * integrated one whoever made it, and a 24 GiB card beats a 4 GiB one, because that is what
 * actually decides whether a frame's worth of meshlets fits.
 */
public final class DeviceScorer {

    // Mirrors VkPhysicalDeviceType.
    public static final int DEVICE_TYPE_OTHER = 0;
    public static final int DEVICE_TYPE_INTEGRATED_GPU = 1;
    public static final int DEVICE_TYPE_DISCRETE_GPU = 2;
    public static final int DEVICE_TYPE_VIRTUAL_GPU = 3;
    public static final int DEVICE_TYPE_CPU = 4;

    public record Candidate(String deviceName, int deviceType, long deviceLocalBytes,
                            int apiVersion, boolean amd) {
    }

    public record Ranked(Candidate candidate, String reason) {
    }

    private DeviceScorer() {
    }

    /** Best first. Stable: equal candidates keep their enumeration order, then name. */
    public static List<Ranked> rank(List<Candidate> candidates) {
        List<Ranked> out = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            out.add(new Ranked(c, explain(c)));
        }
        out.sort(Comparator
                .comparingInt((Ranked r) -> typeRank(r.candidate().deviceType())).reversed()
                .thenComparing(Comparator.comparingLong((Ranked r) -> r.candidate().deviceLocalBytes()).reversed())
                .thenComparing(Comparator.comparing((Ranked r) -> r.candidate().amd()).reversed())
                .thenComparing(Comparator.comparingInt((Ranked r) -> r.candidate().apiVersion()).reversed())
                .thenComparing(r -> r.candidate().deviceName()));
        return out;
    }

    public static int typeRank(int deviceType) {
        // CPU and OTHER are last: a software rasteriser or a virtual adapter is a fallback, not a
        // target. VIRTUAL ranks below INTEGRATED because it is usually a hypervisor passthrough.
        return switch (deviceType) {
            case DEVICE_TYPE_DISCRETE_GPU -> 4;
            case DEVICE_TYPE_INTEGRATED_GPU -> 3;
            case DEVICE_TYPE_VIRTUAL_GPU -> 2;
            case DEVICE_TYPE_OTHER -> 1;
            default -> 0;
        };
    }

    private static String explain(Candidate c) {
        String type = switch (c.deviceType()) {
            case DEVICE_TYPE_DISCRETE_GPU -> "discrete GPU";
            case DEVICE_TYPE_INTEGRATED_GPU -> "integrated GPU";
            case DEVICE_TYPE_VIRTUAL_GPU -> "virtual GPU";
            case DEVICE_TYPE_CPU -> "software rasteriser";
            default -> "unknown device class";
        };
        String memory = c.deviceLocalBytes() >= 1L << 30
                ? (c.deviceLocalBytes() >> 30) + " GiB device-local"
                : (c.deviceLocalBytes() >> 20) + " MiB device-local";
        return type + ", " + memory + (c.amd() ? ", AMD (tuning target)" : "");
    }
}
