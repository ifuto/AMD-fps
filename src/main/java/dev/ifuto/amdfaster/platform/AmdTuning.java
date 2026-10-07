package dev.ifuto.amdfaster.platform;

import dev.ifuto.amdfaster.config.AmdFasterConfig;

/**
 * Tuning knobs derived from the detected architecture + the actual Vulkan
 * memory topology of the machine. This is where "AMD-first" stops being a
 * slogan and becomes numbers:
 *
 * <ul>
 *   <li><b>Resizable BAR / unified memory.</b> AMD boards historically exposed
 *       only a 256 MiB HOST_VISIBLE|DEVICE_LOCAL heap; with ReBAR (SAM) enabled
 *       the whole VRAM appears as one such heap. When we detect a large
 *       device-local host-visible heap, section arenas are allocated directly
 *       in it and the CPU writes vertices straight into VRAM — the staging
 *       copy disappears entirely (see docs/research/04-vma-memory.md).</li>
 *   <li><b>APUs (Vega 8 / 680M / 780M / Steam Deck).</b> There is no VRAM; the
 *       single heap is already host-visible. Budgets must stay conservative so
 *       the display engine and the OS keep breathing room, and one extra frame
 *       in flight usually hides upload latency better than on dGPUs.</li>
 *   <li><b>GCN (Polaris/Vega).</b> Still fully supported by RADV/AMDVLK; we
 *       just avoid assuming wave32 and keep indirect batches smaller — GCN
 *       scheduling of very large MULTI_DRAW_INDIRECT lists is less forgiving.</li>
 * </ul>
 */
public final class AmdTuning {
	public final AmdArchitecture architecture;

	/** Frames in flight actually used. */
	public final int framesInFlight;
	/** Per-frame staging ring slice size in bytes. */
	public final long stagingBytes;
	/**
	 * True when arenas/buffers should be allocated DEVICE_LOCAL|HOST_VISIBLE and
	 * persistently mapped (ReBAR dGPU or unified-memory APU).
	 */
	public final boolean directUploadMemory;
	/** Safety budget (bytes) kept free inside the device-local host-visible heap. */
	public final long directUploadBudget;
	/** Sections per region cube edge (region = N^3 sections share one buffer pair). */
	public final int regionSections;
	/** Max draws per vkCmdDrawIndexedIndirect call. */
	public final int maxIndirectDrawsPerCall;
	/** Human readable summary for the log / overlay title. */
	public final String summary;

	private AmdTuning(AmdArchitecture architecture, int framesInFlight, long stagingBytes,
	                  boolean directUploadMemory, long directUploadBudget,
	                  int regionSections, int maxIndirectDrawsPerCall, String summary) {
		this.architecture = architecture;
		this.framesInFlight = framesInFlight;
		this.stagingBytes = stagingBytes;
		this.directUploadMemory = directUploadMemory;
		this.directUploadBudget = directUploadBudget;
		this.regionSections = regionSections;
		this.maxIndirectDrawsPerCall = maxIndirectDrawsPerCall;
		this.summary = summary;
	}

	/**
	 * @param deviceLocalHostVisibleHeapBytes size of the biggest DEVICE_LOCAL|HOST_VISIBLE
	 *                                        heap reported by the driver (0 when absent)
	 * @param totalDeviceLocalBytes           biggest DEVICE_LOCAL heap (VRAM / carve-out)
	 */
	public static AmdTuning derive(AmdArchitecture arch, AmdFasterConfig cfg,
	                               long deviceLocalHostVisibleHeapBytes, long totalDeviceLocalBytes) {
		int fif = cfg.framesInFlight;
		if (fif <= 0) {
			fif = arch.apu ? 3 : 2;
		}

		long staging = cfg.stagingBufferMiB * 1024L * 1024L;
		if (arch.apu) {
			staging = Math.min(staging, 32L * 1024 * 1024);
		}

		// ReBAR / unified memory detection with a config override.
		long minRebarHeap = 256L * 1024 * 1024;
		if (cfg.deviceLocalHostVisibleMiB > 0) {
			minRebarHeap = cfg.deviceLocalHostVisibleMiB * 1024L * 1024L;
		}
		boolean direct = deviceLocalHostVisibleHeapBytes >= Math.max(minRebarHeap, 512L * 1024 * 1024)
				|| (arch.apu && deviceLocalHostVisibleHeapBytes > 0);
		// Keep headroom: never claim more than ~60% of VRAM (or 1.5 GiB on APUs,
		// which share system RAM with the OS and display engine).
		long budget;
		if (arch.apu) {
			budget = Math.min(deviceLocalHostVisibleHeapBytes / 2, 1536L * 1024 * 1024);
		} else {
			budget = Math.min(totalDeviceLocalBytes * 6 / 10, deviceLocalHostVisibleHeapBytes * 6 / 10);
		}
		if (!direct) {
			budget = 0;
		}

		int regionSections = arch == AmdArchitecture.GCN_POLARIS ? 4 : 8;
		int maxIndirect = switch (arch) {
			case GCN_POLARIS, GCN_VEGA, APU_VEGA -> 512;
			case RDNA1 -> 1024;
			case RDNA2, APU_RDNA2, STEAM_DECK -> 2048;
			case RDNA3, RDNA4, APU_RDNA3 -> 4096;
			default -> 1024;
		};

		String summary = String.format("%s, wave%d, %d frames in flight, staging %d MiB/frame, %s",
				arch.label, arch.nativeWave, fif, staging >> 20,
				direct ? "direct-to-VRAM uploads (ReBAR/unified, budget " + (budget >> 20) + " MiB)"
						: "staged uploads (no large DEVICE_LOCAL|HOST_VISIBLE heap)");
		return new AmdTuning(arch, fif, staging, direct, budget, regionSections, maxIndirect, summary);
	}

	/** Conservative default when nothing could be probed (non-AMD or headless). */
	public static AmdTuning fallback(AmdFasterConfig cfg) {
		return derive(AmdArchitecture.NON_AMD, cfg, 0, 1L << 30);
	}
}
