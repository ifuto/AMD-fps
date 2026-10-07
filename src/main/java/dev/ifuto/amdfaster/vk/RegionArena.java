package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.mesh.VertexRecord;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A region of world (one or more 16&sup3; sections) uploaded into a single
 * persistently-mapped vertex+index buffer pair.
 *
 * <p>Region layout: {@code regionSections}&sup3; sections per region, one region
 * = one VkBuffer pair, one push-constant origin, one draw call per section.
 * This is the AMD-Faster equivalent of Sodium's "render region" but with the
 * draw batching pushed further: on RDNA the cost of a draw call is dominated by
 * command-buffer space and wave occupancy, so fewer, larger buffers win.
 *
 * <p>When the device exposes a large DEVICE_LOCAL|HOST_VISIBLE heap (Resizable
 * BAR / APU), the buffers are allocated there and mapped once at creation —
 * the mesher writes vertices straight into VRAM. Otherwise the arena is
 * HOST_VISIBLE|COHERENT and copied at submit time.
 */
public final class RegionArena {
	/** Bytes per section: worst case 16&sup3; blocks * 6 faces * 4 verts * 28 bytes. */
	public static final long MAX_VERTEX_BYTES_PER_SECTION = 16L * 16 * 16 * 6 * 4 * VertexRecord.SIZE;
	/** Indices per section: 16&sup3; * 6 faces * 6 indices * 4 bytes (u32). */
	public static final long MAX_INDEX_BYTES_PER_SECTION = 16L * 16 * 16 * 6 * 6 * 4;

	public final int regionOriginX, regionOriginY, regionOriginZ;
	public final int sectionsPerEdge;

	public GpuBuffer vertexBuffer;
	public GpuBuffer indexBuffer;
	public long vertexBytes;
	public long indexBytes;

	/** Per-section index counts (drawn with vkCmdDrawIndexedIndirect). */
	public final List<SectionDraw> sections = new ArrayList<>();

	public RegionArena(int regionOriginX, int regionOriginY, int regionOriginZ, int sectionsPerEdge) {
		this.regionOriginX = regionOriginX;
		this.regionOriginY = regionOriginY;
		this.regionOriginZ = regionOriginZ;
		this.sectionsPerEdge = sectionsPerEdge;
	}

	/** One indirect draw command per section. Matches VkDrawIndexedIndirectCommand. */
	public static final class SectionDraw {
		public int indexCount;
		public int instanceCount = 1;
		public int firstIndex;
		public int vertexOffset;
		public int firstInstance;
	}

	/** Allocates the GPU buffers for {@code sectionCount} sections. */
	public void allocate(MemoryManager memory, int sectionCount) {
		long vBytes = Math.max(1, MAX_VERTEX_BYTES_PER_SECTION * sectionCount);
		long iBytes = Math.max(1, MAX_INDEX_BYTES_PER_SECTION * sectionCount);
		// Cap the per-region allocation so a pathological fully-solid region cannot
		// blow the direct-upload budget in one go.
		vBytes = Math.min(vBytes, 256L * 1024 * 1024);
		iBytes = Math.min(iBytes, 64L * 1024 * 1024);

		int usage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
				| org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
		vertexBuffer = memory.alloc(vBytes, usage, MemoryManager.Preference.DEVICE_FAST, true);
		indexBuffer = memory.alloc(iBytes,
				org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT
						| org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
				MemoryManager.Preference.DEVICE_FAST, true);
		this.vertexBytes = vBytes;
		this.indexBytes = iBytes;
	}

	/** Maps the (possibly persistently mapped) buffers for CPU writing. */
	public ByteBuffer mappedVertices() {
		return vertexBuffer.mapped();
	}

	public ByteBuffer mappedIndices() {
		return indexBuffer.mapped();
	}

	public void close() {
		vertexBuffer = null;
		indexBuffer = null;
		sections.clear();
	}
}
