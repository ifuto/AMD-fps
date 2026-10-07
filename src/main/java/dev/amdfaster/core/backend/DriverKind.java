package dev.amdfaster.core.backend;

import java.util.Locale;

/**
 * Which driver stack is in use. AMD's tuning decisions depend on this as much as on the silicon:
 * the same RDNA 2 card behaves very differently under {@code amdvlk} / {@code RADV} / the Windows
 * proprietary drivers, and the OpenGL path on Windows is a different (and much weaker) submission
 * path than {@code radeonsi} on Linux.
 *
 * <p>Sources: AMDVLK and PAL sources in {@code GPUOpen-Drivers}, Mesa's {@code radeonsi}/RADV
 * sources and the {@code RADV_PERFTEST}, {@code AMD_DEBUG}, {@code ACO_DEBUG} environment variables
 * that those drivers read.
 */
public enum DriverKind {
    /** Windows AMD OpenGL driver (the ATI-era "ATICfx"/"atig6txx" stack). Known to be slow at many small writes. */
    AMD_WINDOWS_GL("AMD Windows OpenGL", true),
    /** Mesa {@code radeonsi} OpenGL driver on Linux. */
    MESA_RADEONSI("Mesa radeonsi (Linux OpenGL)", true),
    /** AMD's own Vulkan driver, source available as AMDVLK, built on PAL + LLPC. */
    AMDVLK("AMDVLK (PAL + LLPC)", true),
    /** Windows AMD Vulkan driver (same PAL based stack, shipped in the Adrenalin driver). */
    AMD_WINDOWS_VULKAN("AMD Windows Vulkan (PAL + LLPC)", true),
    /** Mesa RADV, the community Vulkan driver that uses ACO as the shader compiler. */
    RADV("Mesa RADV (ACO)", true),
    /** AMD's legacy AMDGPU-PRO hybrid stack. */
    AMDGPU_PRO("AMDGPU-PRO", true),
    NVIDIA_PROPRIETARY("NVIDIA proprietary", false),
    INTEL_MESA("Intel Mesa", false),
    SOFTWARE("software rasteriser", false),
    UNKNOWN("unknown driver", false);

    private final String label;
    private final boolean amdStack;

    DriverKind(String label, boolean amdStack) {
        this.label = label;
        this.amdStack = amdStack;
    }

    public String label() {
        return label;
    }

    public boolean isAmdStack() {
        return amdStack;
    }

    /** True when the driver ships AMD's own LLVM based shader compiler (LLPC), which AMD-Faster can drive offline. */
    public boolean usesLlpc() {
        return this == AMDVLK || this == AMD_WINDOWS_VULKAN;
    }

    /** True when the driver compiles shaders with ACO (Mesa RADV). */
    public boolean usesAco() {
        return this == RADV;
    }

    /** True when the GL submission path is the known-weak Windows AMD OpenGL driver. */
    public boolean isWeakGlSubmission() {
        return this == AMD_WINDOWS_GL;
    }

    /**
     * Best-effort classification from the API version/description strings AMD reports, e.g.
     * {@code "AMD proprietary driver, 23.9.1"} (Windows GL), {@code "Mesa 24.2.0 (LLVM 18.1.8)"}
     * (Linux GL), {@code "AMD open-source driver: Version 2024.Q3.1"} (AMDVLK), {@code "Mesa RADV"}.
     */
    public static DriverKind classify(String vendor, String versionString, boolean vulkan, boolean onWindows) {
        String s = ((vendor == null ? "" : vendor) + ' ' + (versionString == null ? "" : versionString))
                .toLowerCase(Locale.ROOT);
        boolean mesa = s.contains("mesa");
        if (s.contains("llvmpipe") || s.contains("softpipe") || s.contains("swiftshader") || s.contains("warp")) {
            return SOFTWARE;
        }
        if (s.contains("nvidia") || s.contains("proprietary driver") && s.contains("nvidia")) {
            return NVIDIA_PROPRIETARY;
        }
        if (vulkan) {
            if (s.contains("radv") || mesa) {
                return RADV;
            }
            if (s.contains("amdvlk") || s.contains("amd open-source driver") || s.contains("amd open source driver")) {
                return AMDVLK;
            }
            if (s.contains("amd") || s.contains("advanced micro devices")) {
                return AMD_WINDOWS_VULKAN;
            }
        } else {
            if (mesa) {
                return MESA_RADEONSI;
            }
            if (s.contains("amd") || s.contains("ati") || s.contains("radeon")) {
                return onWindows ? AMD_WINDOWS_GL : MESA_RADEONSI;
            }
        }
        if (s.contains("intel")) {
            return INTEL_MESA;
        }
        return UNKNOWN;
    }
}
