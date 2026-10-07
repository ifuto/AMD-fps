package dev.ifuto.amdfaster.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import java.nio.LongBuffer;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Frame pacing with a single <b>timeline semaphore</b> (Vulkan 1.2 core, and
 * the synchronization primitive RADV/AMDVLK implement with the least overhead —
 * one kernel object instead of a fence pool) plus per-slot binary semaphores
 * for acquire/present handoff.
 *
 * <pre>
 *   frame N        : wait timeline >= N - framesInFlight
 *   submit of N    : signal timeline = N + 1
 * </pre>
 */
public final class FrameSync implements AutoCloseable {
	private final VkDeviceHolder holder;
	public final int slots;

	public final long timeline;
	public final long[] imageAvailable;
	public final long[] renderFinished;

	/** Number of frames submitted so far; also the next timeline signal value minus one. */
	public long value;
	/** Slot index for the frame currently being recorded (valid between begin/endFrame). */
	public int slot;

	/** Avoids dragging the whole Device into sync code. */
	interface VkDeviceHolder {
		org.lwjgl.vulkan.VkDevice device();
	}

	public FrameSync(Device device, int slots) {
		this.holder = device::getHandleCompat;
		this.slots = slots;
		try (MemoryStack stack = stackPush()) {
			VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
					.sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
					.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
					.initialValue(0);
			VkSemaphoreCreateInfo ci = VkSemaphoreCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
					.pNext(typeInfo.address());
			LongBuffer pSem = stack.mallocLong(1);
			check(VK10.vkCreateSemaphore(device.handle, ci, null, pSem), "vkCreateSemaphore(timeline)");
			timeline = pSem.get(0);

			VkSemaphoreCreateInfo bin = VkSemaphoreCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
			imageAvailable = new long[slots];
			renderFinished = new long[slots];
			for (int i = 0; i < slots; i++) {
				check(VK10.vkCreateSemaphore(device.handle, bin, null, pSem), "vkCreateSemaphore(imageAvailable)");
				imageAvailable[i] = pSem.get(0);
				check(VK10.vkCreateSemaphore(device.handle, bin, null, pSem), "vkCreateSemaphore(renderFinished)");
				renderFinished[i] = pSem.get(0);
			}
		}
	}

	/** Blocks until the slot for the next frame is free. Call at frame start. */
	public void beginFrame() {
		if (value >= slots) {
			try (MemoryStack stack = stackPush()) {
				VkSemaphoreWaitInfo wi = VkSemaphoreWaitInfo.calloc(stack)
						.sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO)
						.pSemaphores(stack.longs(timeline))
						.pValues(stack.longs(value - slots + 1));
				check(VK12.vkWaitSemaphores(holder.device(), wi, 0xFFFFFFFFFFFFFFFFL), "vkWaitSemaphores");
			}
		}
		slot = (int) (value % slots);
	}

	/** Called after the submit that signals {@code value + 1}. */
	public void frameSubmitted() {
		value++;
	}

	public long nextSignalValue() {
		return value + 1;
	}

	@Override
	public void close() {
		org.lwjgl.vulkan.VkDevice device = holder.device();
		VK10.vkDestroySemaphore(device, timeline, null);
		for (int i = 0; i < slots; i++) {
			VK10.vkDestroySemaphore(device, imageAvailable[i], null);
			VK10.vkDestroySemaphore(device, renderFinished[i], null);
		}
	}
}
