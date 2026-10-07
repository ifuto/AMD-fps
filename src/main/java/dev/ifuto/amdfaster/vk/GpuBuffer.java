package dev.ifuto.amdfaster.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * A VkBuffer + its VkDeviceMemory, optionally persistently mapped.
 *
 * <p>Persistent mapping is the core AMD upload strategy: on Resizable-BAR
 * systems and APUs the DEVICE_LOCAL|HOST_VISIBLE heap lets the CPU write
 * vertices <i>directly into VRAM</i>, removing the staging copy from the hot
 * path entirely (docs/research/04-vma-memory.md and 06-opengl-amd-quirks.md —
 * the same reason Sodium's persistent-mapped path beats naive glBufferSubData
 * on AMD's OpenGL driver).
 */
public final class GpuBuffer implements AutoCloseable {
	public final long handle;
	public final long size;
	public final boolean persistentlyMapped;

	private final VkDevice device;
	private final long memory;
	private final ByteBuffer mapped;

	GpuBuffer(Device owner, long size, int usage, int memoryTypeIndex, boolean mapNow) {
		this.device = owner.handle;
		this.size = size;
		ByteBuffer mappedTmp = null;
		try (MemoryStack stack = stackPush()) {
			VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
					.size(size)
					.usage(usage)
					.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer pBuffer = stack.mallocLong(1);
			check(VK10.vkCreateBuffer(device, ci, null, pBuffer), "vkCreateBuffer");
			this.handle = pBuffer.get(0);

			VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
			VK10.vkGetBufferMemoryRequirements(device, handle, reqs);

			VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
					.allocationSize(reqs.size())
					.memoryTypeIndex(memoryTypeIndex);
			LongBuffer pMemory = stack.mallocLong(1);
			check(VK10.vkAllocateMemory(device, ai, null, pMemory), "vkAllocateMemory");
			this.memory = pMemory.get(0);
			check(VK10.vkBindBufferMemory(device, handle, memory, 0), "vkBindBufferMemory");

			if (mapNow) {
				PointerBuffer pData = stack.mallocPointer(1);
				check(VK10.vkMapMemory(device, memory, 0, size, 0, pData), "vkMapMemory");
				mappedTmp = pData.getByteBuffer(0, (int) Math.min(size, Integer.MAX_VALUE));
			}
		}
		this.mapped = mappedTmp;
		this.persistentlyMapped = mappedTmp != null;
	}

	/** The persistently mapped range, or {@code null} for device-only buffers. */
	public ByteBuffer mapped() {
		return mapped;
	}

	/** CPU-side write into the mapped range (no flush needed: HOST_COHERENT types only). */
	public void upload(ByteBuffer src, int dstOffset) {
		if (mapped == null) {
			throw new IllegalStateException("buffer is not persistently mapped");
		}
		ByteBuffer dst = mapped.duplicate();
		dst.position(dstOffset).limit(dstOffset + src.remaining());
		dst.put(src.duplicate());
	}

	@Override
	public void close() {
		if (mapped != null) {
			VK10.vkUnmapMemory(device, memory);
		}
		VK10.vkDestroyBuffer(device, handle, null);
		VK10.vkFreeMemory(device, memory, null);
	}
}
