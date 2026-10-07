package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.AmdFaster;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFence;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Allocation policy layer. Deliberately not a full VMA clone — v0.1 allocates
 * one VkDeviceMemory per buffer (buffer count is low: one pair per region +
 * a handful of globals), but the <b>type selection</b> is where the AMD logic
 * lives:
 *
 * <pre>
 *   DEVICE_FAST    -> DEVICE_LOCAL               (uploaded via staging copy)
 *                     ...unless a big DEVICE_LOCAL|HOST_VISIBLE heap exists
 *                        (ReBAR / APU): then that heap, persistently mapped,
 *                        zero-copy, until {@link AmdTuning#directUploadBudget} runs out
 *   DIRECT_UPLOAD  -> DEVICE_LOCAL|HOST_VISIBLE when available, else HOST_VISIBLE|COHERENT
 *   STAGING        -> HOST_VISIBLE|COHERENT (short-lived transfer src)
 * </pre>
 *
 * Also owns the synchronous "immediate uploader" used while meshes are being
 * built (dedicated command pool + fence, mirroring the pattern VulkanMod uses
 * for texture uploads).
 */
public final class MemoryManager implements AutoCloseable {
	public enum Preference {
		/** Fastest GPU access; CPU writes go through staging unless ReBAR/unified memory is present. */
		DEVICE_FAST,
		/** CPU-written every frame / at build time; GPU reads it too (UBOs, indirect command buffers). */
		DIRECT_UPLOAD,
		/** CPU-only lifetime (transfer sources). */
		STAGING
	}

	private final Device device;
	private final int typeDeviceLocal;
	private final int typeHostVisible;
	private final int typeDirectUpload;   // may equal typeDeviceLocal-ish (ReBAR) or hostVisible
	private final boolean directUploadIsDeviceLocal;

	private long directUploadUsed;

	// immediate uploader
	private long uploadPool;
	private VkCommandBuffer uploadCmd;
	private long uploadFence;
	private final List<GpuBuffer> ownedBuffers = new ArrayList<>();
	private GpuBuffer stagingBuffer;
	private long stagingSize;
	private long stagingOffset;

	public MemoryManager(Device device) {
		this.device = device;

		this.typeDeviceLocal = require(device.findMemoryType(0xFFFFFFFF, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT), "DEVICE_LOCAL");
		this.typeHostVisible = require(device.findMemoryType(0xFFFFFFFF,
				VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT), "HOST_VISIBLE|COHERENT");

		int rebar = device.findMemoryType(0xFFFFFFFF,
				VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
		if (device.tuning.directUploadMemory && rebar != -1) {
			this.typeDirectUpload = rebar;
			this.directUploadIsDeviceLocal = true;
		} else {
			this.typeDirectUpload = typeHostVisible;
			this.directUploadIsDeviceLocal = false;
		}

		try (MemoryStack stack = stackPush()) {
			org.lwjgl.vulkan.VkCommandPoolCreateInfo poolCi = org.lwjgl.vulkan.VkCommandPoolCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
					.flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
					.queueFamilyIndex(device.graphicsFamily);
			LongBuffer pPool = stack.mallocLong(1);
			check(VK10.vkCreateCommandPool(device.handle, poolCi, null, pPool), "vkCreateCommandPool(upload)");
			this.uploadPool = pPool.get(0);

			org.lwjgl.vulkan.VkCommandBufferAllocateInfo ai = org.lwjgl.vulkan.VkCommandBufferAllocateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
					.commandPool(uploadPool)
					.level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
					.commandBufferCount(1);
			PointerBuffer pCmd = stack.mallocPointer(1);
			check(VK10.vkAllocateCommandBuffers(device.handle, ai, pCmd), "vkAllocateCommandBuffers(upload)");
			this.uploadCmd = new VkCommandBuffer(pCmd.get(0), device.handle);

			org.lwjgl.vulkan.VkFenceCreateInfo fci = org.lwjgl.vulkan.VkFenceCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
			LongBuffer pFence = stack.mallocLong(1);
			check(VK10.vkCreateFence(device.handle, fci, null, pFence), "vkCreateFence(upload)");
			this.uploadFence = pFence.get(0);
		}

		this.stagingSize = Math.max(1L << 20, device.tuning.stagingBytes);
		this.stagingBuffer = alloc(stagingSize,
				VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, Preference.STAGING, false);
		this.stagingOffset = 0;

		AmdFaster.LOGGER.info("[AMD-Faster] memory: deviceLocal={} hostVisible={} directUpload={} (deviceLocal={}, budget {} MiB)",
				typeDeviceLocal, typeHostVisible, typeDirectUpload, directUploadIsDeviceLocal,
				device.tuning.directUploadBudget >> 20);
	}

	private static int require(int type, String what) {
		if (type < 0) {
			throw new VkUtil.VkException("no memory type for " + what, VK10.VK_ERROR_FEATURE_NOT_PRESENT);
		}
		return type;
	}

	public int memoryTypeFor(Preference pref) {
		switch (pref) {
			case DEVICE_FAST:
				return (directUploadIsDeviceLocal && directUploadUsed < device.tuning.directUploadBudget)
						? typeDirectUpload : typeDeviceLocal;
			case DIRECT_UPLOAD:
			case STAGING:
			default:
				return pref == Preference.STAGING ? typeHostVisible : typeDirectUpload;
		}
	}

	public boolean shouldMap(Preference pref) {
		if (pref == Preference.STAGING || pref == Preference.DIRECT_UPLOAD) {
			return true;
		}
		// DEVICE_FAST lands in the ReBAR heap whenever the budget allows -> map it.
		return directUploadIsDeviceLocal && directUploadUsed < device.tuning.directUploadBudget;
	}

	/** Allocates a buffer; tracked for bulk release in {@link #close()}. */
	public GpuBuffer alloc(long size, int usage, Preference pref, boolean track) {
		int type = memoryTypeFor(pref);
		boolean map = shouldMap(pref) && type != typeDeviceLocal;
		GpuBuffer buf = new GpuBuffer(device, size, usage, type, map);
		if (pref == Preference.DEVICE_FAST && type == typeDirectUpload && directUploadIsDeviceLocal) {
			directUploadUsed += size;
		}
		if (track) {
			ownedBuffers.add(buf);
		}
		return buf;
	}

	public void free(GpuBuffer buffer) {
		if (buffer == null) {
			return;
		}
		ownedBuffers.remove(buffer);
		buffer.close();
	}

	/**
	 * Synchronously copies {@code data} into {@code dst}.
	 * If the destination is persistently mapped (ReBAR/APU path) this is a plain
	 * memcpy; otherwise it bounces through the staging ring with an immediate
	 * transfer submit.
	 */
	public void uploadNow(GpuBuffer dst, long dstOffset, ByteBuffer data) {
		int len = data.remaining();
		if (dst.persistentlyMapped) {
			ByteBuffer mapped = dst.mapped().duplicate();
			mapped.position((int) dstOffset).limit((int) dstOffset + len);
			mapped.put(data.duplicate());
			return;
		}
		if (len > stagingSize) {
			throw new VkUtil.VkException("upload larger than staging ring (" + len + ")", VK10.VK_ERROR_OUT_OF_HOST_MEMORY);
		}
		if (stagingOffset + len > stagingSize) {
			stagingOffset = 0; // ring wrap; caller serializes uploads on the render thread
		}
		ByteBuffer mapped = stagingBuffer.mapped().duplicate();
		mapped.position((int) stagingOffset).limit((int) stagingOffset + len);
		mapped.put(data.duplicate());
		long srcOffset = stagingOffset;
		stagingOffset += (len + 255) & ~255L;

		try (MemoryStack stack = stackPush()) {
			VK10.vkResetFences(device.handle, stack.longs(uploadFence));

			org.lwjgl.vulkan.VkCommandBufferBeginInfo begin = org.lwjgl.vulkan.VkCommandBufferBeginInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
					.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			check(VK10.vkBeginCommandBuffer(uploadCmd, begin), "vkBeginCommandBuffer(upload)");
			VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
					.srcOffset(srcOffset)
					.dstOffset(dstOffset)
					.size(len);
			VK10.vkCmdCopyBuffer(uploadCmd, stagingBuffer.handle, dst.handle, copy);
			check(VK10.vkEndCommandBuffer(uploadCmd), "vkEndCommandBuffer(upload)");

			VkSubmitInfo si = VkSubmitInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
					.pCommandBuffers(stack.pointers(uploadCmd));
			check(VK10.vkQueueSubmit(device.graphicsQueue, si, uploadFence), "vkQueueSubmit(upload)");
			check(VK10.vkWaitForFences(device.handle, stack.longs(uploadFence), true, Long.MAX_VALUE), "vkWaitForFences(upload)");
			VK10.vkResetCommandBuffer(uploadCmd, 0);
		}
	}

	public long directUploadUsed() {
		return directUploadUsed;
	}

	public long directUploadBudget() {
		return device.tuning.directUploadBudget;
	}

	public boolean directUploadIsDeviceLocal() {
		return directUploadIsDeviceLocal;
	}

	@Override
	public void close() {
		try {
			VK10.vkDeviceWaitIdle(device.handle);
		} catch (Throwable ignored) {
			// device may already be lost
		}
		for (int i = ownedBuffers.size() - 1; i >= 0; i--) {
			ownedBuffers.get(i).close();
		}
		ownedBuffers.clear();
		if (stagingBuffer != null) {
			stagingBuffer.close();
			stagingBuffer = null;
		}
		VK10.vkDestroyFence(device.handle, uploadFence, null);
		VK10.vkDestroyCommandPool(device.handle, uploadPool, null);
	}
}
