package dev.ifuto.amdfaster.platform;

import java.util.Locale;

/**
 * Best-effort classification of AMD GPU families, used to pick memory/queue
 * tuning defaults. Classification uses three signals, in decreasing order of
 * reliability:
 *
 * <ol>
 *   <li>PCI device id (from {@code VkPhysicalDeviceProperties}) against the
 *       table below — kept deliberately coarse; it only needs to answer
 *       "which ISA generation / dGPU vs APU".</li>
 *   <li>the Linux renderer string, where radeonsi embeds the LLVM chip name
 *       ({@code navi31}, {@code gfx1030}, {@code renoir}, …),</li>
 *   <li>marketing names ({@code RX 6800}, {@code 780M}, {@code Steam Deck}).</li>
 * </ol>
 *
 * The ISA generations matter because they change the numbers the backend cares
 * about: wave32 support (GCN5+/RDNA, see docs/research/01-rdna-isa.md), LDS
 * size per workgroup (64 KiB), VGPR pressure/occupancy targets, and whether
 * mesh shaders / sampler feedback style tricks are available at all
 * (VK_EXT_mesh_shader: RDNA2+ on RADV, RDNA3+ on Windows drivers — we feature
 * probe instead of trusting this enum).
 */
public enum AmdArchitecture {
	GCN_POLARIS("GCN4 (Polaris)", false, 64),
	GCN_VEGA("GCN5 (Vega)", false, 64),
	APU_VEGA("Vega APU (Raven/Picasso/Cezanne/Green Sardine)", true, 64),
	RDNA1("RDNA1 (Navi 1x)", false, 32),
	RDNA2("RDNA2 (Navi 2x)", false, 32),
	RDNA3("RDNA3 (Navi 3x)", false, 32),
	RDNA4("RDNA4 (Navi 4x)", false, 32),
	APU_RDNA2("RDNA2 APU (VanGogh/Rembrandt/Mendocino)", true, 32),
	APU_RDNA3("RDNA3 APU (Phoenix/Hawk/Strix)", true, 32),
	STEAM_DECK("VanGogh APU (Steam Deck)", true, 32),
	UNKNOWN("Unknown AMD", false, 64),
	NON_AMD("Non-AMD GPU", false, 32);

	public final String label;
	public final boolean apu;
	/** Preferred/native wavefront width for compute work (informational in v0.1). */
	public final int nativeWave;

	AmdArchitecture(String label, boolean apu, int nativeWave) {
		this.label = label;
		this.apu = apu;
		this.nativeWave = nativeWave;
	}

	/**
	 * Classify. Any parameter may be 0/empty when its probe source did not run.
	 */
	public static AmdArchitecture classify(int pciVendorId, int pciDeviceId, String glRenderer, String vkDeviceName) {
		if (pciVendorId != 0 && pciVendorId != GpuProbe.VENDOR_ID_AMD) {
			String hay = (glRenderer + " " + vkDeviceName).toLowerCase(Locale.ROOT);
			if (!hay.contains("radeon") && !hay.contains("amd")) {
				return NON_AMD;
			}
		}

		// 1) PCI device id table (ranges are inclusive; AMD ids are messy, this is
		//    intentionally "good enough" for tuning defaults, not for correctness).
		AmdArchitecture byId = fromDeviceId(pciDeviceId);
		if (byId != null) {
			return byId;
		}

		// 2) chip codenames from the radeonsi renderer string / Vk device name.
		String s = (glRenderer + " " + vkDeviceName).toLowerCase(Locale.ROOT);
		if (s.contains("navi48") || s.contains("navi44") || s.contains("gfx120")) {
			return RDNA4;
		}
		if (s.contains("navi31") || s.contains("navi32") || s.contains("navi33") || s.contains("gfx110") || s.contains("gfx115")) {
			// APUs of this generation (Phoenix/Hawk/Strix) are matched below first.
			if (s.contains("gfx115") || s.contains("phoenix") || s.contains("hawk") || s.contains("strix")) {
				return APU_RDNA3;
			}
			return RDNA3;
		}
		if (s.contains("navi21") || s.contains("navi22") || s.contains("navi23") || s.contains("navi24") || s.contains("gfx103")) {
			if (s.contains("rembrandt") || s.contains("gfx1035") || s.contains("gfx1036") || s.contains("mendocino")) {
				return APU_RDNA2;
			}
			return RDNA2;
		}
		if (s.contains("navi10") || s.contains("navi12") || s.contains("navi14") || s.contains("gfx101")) {
			return RDNA1;
		}
		if (s.contains("steam deck") || s.contains("van gogh") || s.contains("vangogh") || s.contains("gfx1033") || s.contains("aerith")) {
			return STEAM_DECK;
		}
		if (s.contains("renoir") || s.contains("lucienne") || s.contains("cezanne") || s.contains("barcelo") || s.contains("green sardine")
				|| s.contains("gfx90c") || s.contains("picasso") || s.contains("raven")) {
			return APU_VEGA;
		}
		if (s.contains("vega 10") || s.contains("vega10") || s.contains("vega 20") || s.contains("vega20") || s.contains("gfx900") || s.contains("gfx906")) {
			return GCN_VEGA;
		}
		if (s.contains("polaris") || s.contains("gfx803") || s.contains("gfx8") || s.contains("ellesmere") || s.contains("baffin")) {
			return GCN_POLARIS;
		}

		// 3) marketing names.
		if (s.contains("rx 9")) {
			return RDNA4;
		}
		if (s.contains("rx 7") || s.contains("rx 79") || s.contains("rx 78") || s.contains("rx 77") || s.contains("rx 76")) {
			return RDNA3;
		}
		if (s.contains("rx 6") || s.contains("rx 69") || s.contains("rx 68") || s.contains("rx 67") || s.contains("rx 66") || s.contains("rx 65") || s.contains("rx 64")) {
			return RDNA2;
		}
		if (s.contains("rx 57") || s.contains("rx 56") || s.contains("rx 55") || s.contains("rx 53")) {
			return RDNA1;
		}
		if (s.contains("radeon 8") && (s.contains("m") || s.contains("graphics"))) {
			return APU_RDNA3;
		}
		if (s.contains("780m") || s.contains("760m") || s.contains("740m") || s.contains("890m") || s.contains("880m") || s.contains("860m") || s.contains("radeon graphics")) {
			return APU_RDNA3;
		}
		if (s.contains("680m") || s.contains("660m") || s.contains("650m") || s.contains("610m")) {
			return APU_RDNA2;
		}
		if (s.contains("rx vega") || s.contains("vega 11") || s.contains("vega 10") || s.contains("vega 8") || s.contains("vega 6") || s.contains("vega 3")) {
			return APU_VEGA;
		}
		if (s.contains("rx 5") || s.contains("rx 4") || s.contains("rx 580") || s.contains("rx 570")) {
			return GCN_POLARIS;
		}
		if (s.contains("vega")) {
			return GCN_VEGA;
		}

		boolean amd = pciVendorId == GpuProbe.VENDOR_ID_AMD || s.contains("amd") || s.contains("radeon") || s.contains("ati");
		return amd ? UNKNOWN : NON_AMD;
	}

	private static AmdArchitecture fromDeviceId(int id) {
		if (id == 0) {
			return null;
		}
		// RDNA4 (gfx12xx): Navi 48 / 44
		if (id == 0x7540 || id == 0x7548 || id == 0x754C || id == 0x7550 || id == 0x7552
				|| id == 0x7580 || id == 0x7588 || id == 0x7590 || id == 0x75A0) {
			return RDNA4;
		}
		// RDNA3 (gfx11xx): Navi 31/32/33 families
		if ((id >= 0x7440 && id <= 0x745F) || (id >= 0x7470 && id <= 0x748F) || (id >= 0x7500 && id <= 0x753F)) {
			return RDNA3;
		}
		// RDNA3 APUs (Phoenix1/2 = 0x15BF, Hawk Point, Strix Point = 0x1114/0x150E …)
		if (id == 0x15BF || id == 0x15E7 || id == 0x1114 || id == 0x150E || id == 0x1645 && false) {
			return APU_RDNA3;
		}
		// RDNA2 (gfx103x): Navi 21/22/23/24
		if ((id >= 0x73A0 && id <= 0x73BF) || (id >= 0x73C0 && id <= 0x73DF) || (id >= 0x73E0 && id <= 0x73FF)
				|| (id >= 0x7400 && id <= 0x7422) || (id >= 0x7420 && id <= 0x743F)) {
			return RDNA2;
		}
		// RDNA2 APUs: VanGogh 0x163F, Rembrandt 0x1681, Mendocino 0x1645
		if (id == 0x163F || id == 0x1681 || id == 0x1645) {
			return id == 0x163F ? STEAM_DECK : APU_RDNA2;
		}
		// RDNA1 (gfx101x): Navi 10/12/14
		if ((id >= 0x7310 && id <= 0x731F) || (id >= 0x7340 && id <= 0x734F) || (id >= 0x7360 && id <= 0x736F)) {
			return RDNA1;
		}
		// GCN5 (Vega): Vega10 0x6860-0x687F, Vega20 0x66A0-0x66AF
		if ((id >= 0x6860 && id <= 0x687F) || (id >= 0x66A0 && id <= 0x66AF) || id == 0x6880 || id == 0x66AF) {
			return GCN_VEGA;
		}
		// Vega APUs: Raven 0x15DD, Picasso 0x15D8, Renoir 0x1636, Green Sardine 0x164C, Cezanne 0x1638
		if (id == 0x15DD || id == 0x15D8 || id == 0x1636 || id == 0x164C || id == 0x1638 || id == 0x15E7) {
			return APU_VEGA;
		}
		// GCN4 (Polaris): 0x67DF (RX 480/580), 0x67FF (RX 560), 0x67E0-0x67EF
		if (id == 0x67DF || id == 0x67FF || (id >= 0x67E0 && id <= 0x67EF) || (id >= 0x67C0 && id <= 0x67CF)) {
			return GCN_POLARIS;
		}
		return null;
	}
}
