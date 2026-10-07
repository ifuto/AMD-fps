package net.amdfaster.platform;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps the strings a Vulkan driver reports about itself onto an {@link AmdArchitecture}.
 *
 * <p>There is no reliable numeric way to do this: {@code deviceID} tables are enormous and
 * change every release, and the two AMD drivers name devices differently
 * ({@code AMD Radeon RX 7900 XTX (RADV NAVI31)} vs {@code AMD Radeon(TM) RX 7900 XTX}).
 * So we match on tokens, ordered most-specific first, and record <em>which</em> token matched so
 * a wrong guess is always explainable in the report.
 *
 * <p>An unrecognised part is {@link AmdArchitecture#UNKNOWN}, which is a supported outcome: the
 * probe still reports the driver-measured subgroup size, LDS size and work-group limits, which is
 * what the renderer actually schedules against.
 */
public final class GpuIdentity {

    private GpuIdentity() {
    }

    /** Ordered most-specific first; the first match wins. */
    private record Rule(AmdArchitecture arch, String token, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            // --- RDNA 4 (GFX12) -----------------------------------------------------------
            rule(AmdArchitecture.RDNA4, "gfx120", "gfx120\\d"),
            rule(AmdArchitecture.RDNA4, "navi4x", "navi\\s?4[48]"),
            rule(AmdArchitecture.RDNA4, "RX 9000 series", "\\brx\\s*9\\d{3}"),

            // --- RDNA 3.5 (GFX11.5, mobile) ----------------------------------------------
            rule(AmdArchitecture.RDNA35, "gfx115", "gfx115\\d"),
            rule(AmdArchitecture.RDNA35, "Radeon 8x0M iGPU", "\\b8[4689]0m\\b"),
            rule(AmdArchitecture.RDNA35, "Strix/Krackan", "strix|krackan"),

            // --- RDNA 3 (GFX11) ----------------------------------------------------------
            rule(AmdArchitecture.RDNA3, "gfx110", "gfx110\\d"),
            rule(AmdArchitecture.RDNA3, "navi3x", "navi\\s?3[123]"),
            rule(AmdArchitecture.RDNA3, "RX 7000 series", "\\brx\\s*7\\d{3}"),
            rule(AmdArchitecture.RDNA3, "Radeon 7x0M iGPU", "\\b7[468]0m\\b"),
            rule(AmdArchitecture.RDNA3, "Radeon PRO W7000", "\\bw7[89]\\d0\\b"),
            rule(AmdArchitecture.RDNA3, "Phoenix/Hawk Point", "phoenix|hawk\\s?point"),

            // --- RDNA 2 (GFX10.3) --------------------------------------------------------
            rule(AmdArchitecture.RDNA2, "gfx103", "gfx103\\d"),
            rule(AmdArchitecture.RDNA2, "navi2x", "navi\\s?2[1234]"),
            rule(AmdArchitecture.RDNA2, "RX 6000 series", "\\brx\\s*6\\d{3}"),
            rule(AmdArchitecture.RDNA2, "Radeon PRO W6000", "\\bw6[4568]\\d0\\b"),
            rule(AmdArchitecture.RDNA2, "VanGogh/Rembrandt/Cezanne", "vangogh|rembrandt|cezanne"),

            // --- RDNA 1 (GFX10.1) --------------------------------------------------------
            rule(AmdArchitecture.RDNA1, "gfx101", "gfx101\\d"),
            rule(AmdArchitecture.RDNA1, "navi1x", "navi\\s?1[024]"),
            rule(AmdArchitecture.RDNA1, "RX 5000 series", "\\brx\\s*5\\d{3}"),
            rule(AmdArchitecture.RDNA1, "Radeon PRO W5000", "\\bw5[57]\\d0\\b"),

            // --- Vega / GCN 5 (GFX9) -----------------------------------------------------
            rule(AmdArchitecture.GCN5_VEGA, "gfx9", "gfx9\\d\\d"),
            rule(AmdArchitecture.GCN5_VEGA, "Radeon VII", "radeon\\s*vii\\b"),
            rule(AmdArchitecture.GCN5_VEGA, "Vega", "\\bvega\\b")
    );

    private static Rule rule(AmdArchitecture arch, String token, String regex) {
        return new Rule(arch, token, Pattern.compile(regex));
    }

    /**
     * @param architecture the matched generation, or {@link AmdArchitecture#UNKNOWN}
     * @param token        human-readable name of the rule that matched, or {@code null}
     * @param haystack     the lower-cased text that was matched against
     */
    public record Match(AmdArchitecture architecture, String token, String haystack) {
        public static Match none(String haystack) {
            return new Match(AmdArchitecture.UNKNOWN, null, haystack);
        }
    }

    /**
     * Classifies a device from everything the driver told us about it.
     *
     * @param deviceName {@code VkPhysicalDeviceProperties::deviceName}
     * @param driverInfo {@code VkPhysicalDeviceDriverProperties::driverInfo}, may be null
     * @param driverName {@code VkPhysicalDeviceDriverProperties::driverName}, may be null
     */
    public static Match classify(String deviceName, String driverInfo, String driverName) {
        StringBuilder haystack = new StringBuilder();
        if (deviceName != null) {
            haystack.append(deviceName).append(' ');
        }
        if (driverInfo != null) {
            haystack.append(driverInfo).append(' ');
        }
        if (driverName != null) {
            haystack.append(driverName).append(' ');
        }

        String probe = haystack.toString().toLowerCase(Locale.ROOT);

        for (Rule r : RULES) {
            if (r.pattern().matcher(probe).find()) {
                return new Match(r.arch(), r.token(), probe.trim());
            }
        }

        return Match.none(probe.trim());
    }

    /** AMD's PCI vendor id. */
    public static final int VENDOR_ID_AMD = 0x1002;
    public static final int VENDOR_ID_NVIDIA = 0x10DE;
    public static final int VENDOR_ID_INTEL = 0x8086;

    public static String vendorName(int vendorId) {
        return switch (vendorId) {
            case VENDOR_ID_AMD -> "AMD";
            case VENDOR_ID_NVIDIA -> "NVIDIA";
            case VENDOR_ID_INTEL -> "Intel";
            default -> "Unknown (0x" + Integer.toHexString(vendorId) + ")";
        };
    }
}
