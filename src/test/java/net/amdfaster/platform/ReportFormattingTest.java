package net.amdfaster.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report is read by hand from a JSON file, so the formatting has to be right. */
class ReportFormattingTest {

    @Test
    void formatsVulkanVersions() {
        assertEquals("1.2.0", GpuInfo.formatVersion((1 << 22) | (2 << 12)));
        assertEquals("1.3.250", GpuInfo.formatVersion((1 << 22) | (3 << 12) | 250));
        assertEquals("1.0.0", GpuInfo.formatVersion(1 << 22));
    }

    @Test
    void formatsByteSizes() {
        assertEquals("0 B", GpuInfo.formatBytes(0));
        assertEquals("512 B", GpuInfo.formatBytes(512));
        assertEquals("1.00 KiB", GpuInfo.formatBytes(1024));
        assertEquals("1.50 MiB", GpuInfo.formatBytes(1024L * 1024 + 512 * 1024));
        assertEquals("8.00 GiB", GpuInfo.formatBytes(8L * 1024 * 1024 * 1024));
        assertEquals("32.00 GiB", GpuInfo.formatBytes(32L * 1024 * 1024 * 1024));
    }

    @Test
    void escapesJsonStrings() {
        assertEquals("\"plain\"", GpuReport.jsonString("plain"));
        assertEquals("null", GpuReport.jsonString(null));
        assertEquals("\"quote \\\" and backslash \\\\\"", GpuReport.jsonString("quote \" and backslash \\"));
        assertEquals("\"line1\\nline2\"", GpuReport.jsonString("line1\nline2"));
        assertEquals("\"tab\\there\"", GpuReport.jsonString("tab\there"));
        assertEquals("\"\\u0001\"", GpuReport.jsonString("\u0001"));
        assertEquals("\"AMD Radeon RX 7900 XTX (RADV NAVI31)\"",
                GpuReport.jsonString("AMD Radeon RX 7900 XTX (RADV NAVI31)"));
    }

    @Test
    void trackedExtensionListIsUniqueAndNonEmpty() {
        assertTrue(GpuReport.EXTENSIONS_OF_INTEREST.size() >= 20);
        assertEquals(GpuReport.EXTENSIONS_OF_INTEREST.size(),
                GpuReport.EXTENSIONS_OF_INTEREST.stream().distinct().count(),
                "duplicate entry in EXTENSIONS_OF_INTEREST");
    }
}
