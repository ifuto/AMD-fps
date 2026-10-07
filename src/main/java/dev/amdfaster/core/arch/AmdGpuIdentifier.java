package dev.amdfaster.core.arch;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies AMD GPUs from the strings and ids the graphics APIs expose.
 *
 * <p>Two independent sources are combined, because neither is sufficient on its own:
 * <ol>
 *   <li><b>Device name matching</b> - {@code GL_RENDERER} ("AMD Radeon RX 7900 XTX") or
 *       {@code VkPhysicalDeviceProperties.deviceName}. This is what users actually see, and it is
 *       the only thing that works for APUs where the device id changes every generation.</li>
 *   <li><b>PCI device id table</b> - a small, curated table of ids that are stable and well known.
 *       Used to disambiguate names like "AMD Radeon Graphics" (an APU string that AMD reuses for
 *       Vega, RDNA2 and RDNA3 integrated parts alike).</li>
 * </ol>
 *
 * <p>When both sources disagree the name wins for retail board naming patterns (an RX 7900 is an
 * RX 7900 no matter what the id says) while the id wins for the generic APU strings.
 *
 * <p>Anything that cannot be classified with reasonable confidence resolves to
 * {@link AmdArch#UNKNOWN} with a low confidence value. The tuning core then only applies
 * architecture independent tuning, and the mod logs the raw strings so users can report the GPU to
 * get it added to the tables below.
 */
public final class AmdGpuIdentifier {

    private AmdGpuIdentifier() {
    }

    private record Rule(Pattern pattern, AmdArch arch, double confidence, String label) {
        static Rule of(String regex, AmdArch arch, double confidence, String label) {
            return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), arch, confidence, label);
        }
    }

    /**
     * Ordered rules; the first match wins, so more specific patterns must come first.
     *
     * <p>Some notes on the naming traps that these patterns exist to avoid:
     * <ul>
     *   <li>"Radeon 680M" (Rembrandt, RDNA 2) vs "Radeon RX 6800" / "RX 6800M" (Navi 22, RDNA 2).</li>
     *   <li>"Radeon 780M" (Phoenix, RDNA 3) vs "Radeon RX 7800 XT" (Navi 32, RDNA 3).</li>
     *   <li>"Radeon 890M"/"8060S" (Strix, RDNA 3.5) - shares the GFX11 family but is a different
     *       SKU with 4 more CUs and LPDDR5X.</li>
     *   <li>"RX 5500 XT" (Navi 14, RDNA 1) vs "RX 550" (Polaris 11, GCN 4) - three digits vs four.</li>
     * </ul>
     */
    private static final List<Rule> RULES = List.of(
            // ---- RDNA 4 (GFX12) -------------------------------------------------------------
            Rule.of("\\bRX\\s?9\\d{3}\\b", AmdArch.RDNA4, 0.95, "RX 9000 retail name"),
            Rule.of("\\bR9\\d{3}\\b|RADEON\\s?AI\\s?PRO", AmdArch.RDNA4, 0.6, "RDNA4 era product name"),

            // ---- RDNA 3 (GFX11) -------------------------------------------------------------
            Rule.of("\\bRX\\s?79\\d{2}\\b", AmdArch.RDNA3, 0.95, "RX 7900 retail name"),
            Rule.of("\\bRX\\s?78\\d{2}\\b", AmdArch.RDNA3, 0.95, "RX 7800 retail name"),
            Rule.of("\\bRX\\s?77\\d{2}\\b", AmdArch.RDNA3, 0.95, "RX 7700 retail name"),
            Rule.of("\\bRX\\s?76\\d{2}\\b", AmdArch.RDNA3, 0.95, "RX 7600 retail name"),
            Rule.of("\\bW7[579]00\\b", AmdArch.RDNA3, 0.9, "Radeon Pro W7000 workstation"),
            Rule.of("\\b780M\\b|\\b760M\\b|\\b740M\\b|\\bZ1\\b|\\bZ1\\s?EXTREME\\b",
                    AmdArch.RDNA3_APU, 0.9, "Phoenix / Hawk Point / Z1 APU"),
            Rule.of("\\b890M\\b|\\b880M\\b|\\b860M\\b|\\b840M\\b|\\b8060S\\b|\\b8050S\\b|\\b8040S\\b",
                    AmdArch.RDNA3_APU, 0.85, "Strix Point / Strix Halo APU"),

            // ---- RDNA 2 (GFX10.3) -----------------------------------------------------------
            Rule.of("\\bRX\\s?6\\d{3}\\b", AmdArch.RDNA2, 0.95, "RX 6000 retail name"),
            Rule.of("\\bW6[89]00\\b", AmdArch.RDNA2, 0.9, "Radeon Pro W6000 workstation"),
            Rule.of("\\b680M\\b|\\b660M\\b|\\b610M\\b", AmdArch.RDNA2_APU, 0.9, "Rembrandt APU"),
            Rule.of("STEAM\\s?DECK|VAN\\s?GOGH|AERITH|CUSTOM\\s?GPU\\s?04",
                    AmdArch.RDNA2_APU, 0.9, "Steam Deck (Van Gogh)"),
            Rule.of("\\b66\\d{2}M\\b|\\b67\\d{2}M\\b|\\b68\\d{2}M\\b|\\b69\\d{2}M\\b",
                    AmdArch.RDNA2, 0.75, "RDNA2 mobile part"),

            // ---- RDNA 1 (GFX10) -------------------------------------------------------------
            Rule.of("\\bRX\\s?5\\d{3}\\b", AmdArch.RDNA1, 0.95, "RX 5000 retail name"),
            Rule.of("\\bW5700\\b|\\b5700M\\b|\\b5600M\\b|\\b5500M\\b", AmdArch.RDNA1, 0.9, "Navi 1x part"),

            // ---- Vega / Polaris / GCN -------------------------------------------------------
            Rule.of("\\bVEGA\\s?(8|11|3|6)\\b|\\bVEGA\\s?GRAPHICS\\b", AmdArch.VEGA_APU, 0.9,
                    "Vega integrated graphics (Vega 3/6/8/11)"),
            Rule.of("\\bVEGA\\s?(56|64)\\b|RADEON\\s?VII\\b|RADEON\\s?PRO\\s?VII\\b", AmdArch.VEGA, 0.95,
                    "Vega 56/64 / Radeon VII"),
            Rule.of("\\bWX\\s?(3100|4100|5100|7100|8100|9100)\\b|\\bPRO\\s?WX\\b", AmdArch.VEGA, 0.6,
                    "Radeon Pro WX (Vega/Polaris)"),
            Rule.of("\\bRX\\s?(4|5)\\d{2}(?!\\d)\\b", AmdArch.POLARIS, 0.9, "RX 400/500 retail name"),
            Rule.of("\\bR9\\s?FURY\\b|\\bR9\\s?NANO\\b|\\bR9\\s?3\\d{2}\\b|\\bR7\\s?3\\d{2}\\b|\\bR9\\s?380\\b",
                    AmdArch.GCN3, 0.85, "GCN 3.0 (Tonga/Fiji) retail name"),
            Rule.of("\\bR9\\s?2\\d{2}\\b|\\bR9\\s?390\\b|\\bR9\\s?390X\\b|\\bR7\\s?2\\d{2}\\b", AmdArch.GCN2, 0.8,
                    "GCN 2.0 (Hawaii) retail name"),
            Rule.of("\\bHD\\s?7\\d{3}\\b|\\bHD\\s?8\\d{3}\\b|\\bR9\\s?280X\\b", AmdArch.GCN1, 0.8,
                    "GCN 1.0 retail name"),

            // ---- Instinct / CDNA ------------------------------------------------------------
            Rule.of("\\bMI\\s?(50|60|100)\\b", AmdArch.CDNA, 0.85, "CDNA 1 Instinct"),
            Rule.of("\\bMI\\s?(2\\d{2})\\b", AmdArch.CDNA, 0.85, "CDNA 2 Instinct"),
            Rule.of("\\bMI\\s?(3\\d{2})\\b|\\bMI300\\b", AmdArch.CDNA, 0.85, "CDNA 3 Instinct"),

            // ---- Very common ambiguous APU string ------------------------------------------
            // AMD reports "AMD Radeon(TM) Graphics" for Vega (Ryzen 4000/5000) and for some
            // Rembrandt/Phoenix parts. The device id table below is consulted first for these.
            Rule.of("^AMD\\s?RADEON(\\(TM\\))?\\s?GRAPHICS$|\\bRADEON\\s?VEGA\\b", AmdArch.VEGA_APU, 0.45,
                    "generic AMD integrated graphics string (ambiguous)")
    );

    /**
     * Curated PCI device ids. Only ids that are stable across drivers and that meaningfully
     * disambiguate a name are listed. Values marked {@code LOW} are best-effort and therefore
     * classified with reduced confidence; they never drive aggressive tuning on their own.
     */
    public static AmdArch archForDeviceId(int deviceId) {
        return switch (deviceId & 0xFFFF) {
            // Polaris / GCN 4
            case 0x67DF, 0x67C4, 0x67C7, 0x67E3, 0x67E8, 0x6FDF -> AmdArch.POLARIS;
            // Vega / GCN 5
            case 0x687F, 0x6863, 0x6861 -> AmdArch.VEGA;
            // Vega APUs (Raven Ridge, Picasso)
            case 0x15DD, 0x15D8 -> AmdArch.VEGA_APU;
            // RDNA 1 (Navi 10/12/14)
            case 0x731F, 0x7310, 0x7312, 0x7360, 0x7340, 0x7341 -> AmdArch.RDNA1;
            // RDNA 2 (Navi 21/22/23/24)
            case 0x73BF, 0x73A5, 0x73A3, 0x73DF, 0x73E3, 0x73FF, 0x73EF, 0x7422, 0x7423 -> AmdArch.RDNA2;
            // RDNA 3 (Navi 31/32/33)
            case 0x744C, 0x7448, 0x747E, 0x7470, 0x7480, 0x7483 -> AmdArch.RDNA3;
            default -> AmdArch.UNKNOWN;
        };
    }

    /** Result of a classification attempt. */
    public record Match(AmdArch arch, double confidence, String reason) {
        static final Match UNKNOWN = new Match(AmdArch.UNKNOWN, 0.0, "no pattern matched");
    }

    /**
     * Classifies an AMD device.
     *
     * @param renderer  {@code GL_RENDERER} or Vulkan {@code deviceName}
     * @param deviceId  PCI device id or {@code -1}
     */
    public static Match classifyAmd(String renderer, int deviceId) {
        Match byName = matchName(renderer);
        AmdArch byId = archForDeviceId(deviceId);
        boolean idReliable = byId != AmdArch.UNKNOWN;

        // The ambiguous "AMD Radeon(TM) Graphics" string is exactly the case where the id is the
        // better oracle, so let it win there.
        if (byName.arch == AmdArch.VEGA_APU && byName.confidence <= 0.5 && idReliable) {
            return new Match(byId, 0.8, "device id 0x" + hex(deviceId) + " over ambiguous APU name");
        }
        if (byName.arch != AmdArch.UNKNOWN && byName.confidence >= GpuIdentity.CONFIDENT) {
            return byName;
        }
        if (idReliable) {
            return new Match(byId, byName.arch == AmdArch.UNKNOWN ? 0.8 : 0.7,
                    "device id 0x" + hex(deviceId) + (byName.arch == AmdArch.UNKNOWN ? "" : " (name was ambiguous)"));
        }
        return byName;
    }

    /** Runs the ordered name rules. Package private for tests. */
    static Match matchName(String renderer) {
        if (renderer == null || renderer.isBlank()) {
            return Match.UNKNOWN;
        }
        String name = normalise(renderer);
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(name);
            if (matcher.find()) {
                return new Match(rule.arch(), rule.confidence(), rule.label() + " <- \"" + matcher.group() + "\"");
            }
        }
        return Match.UNKNOWN;
    }

    /**
     * Upper-cases the string and strips the vendor noise AMD puts in its GL renderer strings, e.g.
     * {@code "AMD Radeon RX 7900 XTX (0x744C)"} or {@code "AMD Radeon(TM) Graphics"}.
     */
    static String normalise(String renderer) {
        String s = renderer.toUpperCase(Locale.ROOT);
        s = s.replace("(R)", "").replace("(TM)", "").replace("(C)", "");
        s = s.replaceAll("\\(0X[0-9A-F]{4,8}\\)", " ");
        s = s.replace("ADVANCED MICRO DEVICES", " ");
        s = s.replace("GRAPHICS PROCESSOR", " ");
        s = s.replace("COMPUTE ENGINE", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static String hex(int value) {
        return String.format("%04X", value & 0xFFFF);
    }
}
