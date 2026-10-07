package dev.ifuto.amdfaster.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayout;

import java.nio.LongBuffer;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * A tiny descriptor pool + set allocator. v0.1 needs exactly two kinds of sets
 * (frame UBO, atlas sampler), so a single pool with both sizes suffices; the
 * per-frame sets are re-allocated only when the swapchain is recreated (set
 * bindings reference the per-swapchain UBO, so they must be rebuilt together).
 */
public final class DescriptorAllocator implements AutoCloseable {
	private final org.lwjgl.vulkan.VkDevice device;
	private long pool;
	private final int maxSets;

	public DescriptorAllocator(Device device, int maxSets) {
		this.device = device.handle;
		this.maxSets = maxSets;
		try (MemoryStack stack = stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
			sizes.get(0)
					.type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
					.descriptorCount(maxSets);
			sizes.get(1)
					.type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.descriptorCount(maxSets);
			VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
					.maxSets(maxSets)
					.pPoolSizes(sizes);
			LongBuffer pPool = stack.mallocLong(1);
			check(VK10.vkCreateDescriptorPool(device.handle, ci, null, pPool), "vkCreateDescriptorPool");
			this.pool = pPool.get(0);
		}
	}

	/** Allocates one set per layout, in order. */
	public long[] allocate(VkDescriptorSetLayout... layouts) {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
					.descriptorPool(pool)
					.pSetLayouts(stack.longs(toLongArray(layouts)));
			LongBuffer pSets = stack.mallocLong(layouts.length);
			check(VK10.vkAllocateDescriptorSets(device, ai, pSets), "vkAllocateDescriptorSets");
			long[] out = new long[layouts.length];
			for (int i = 0; i < out.length; i++) {
				out[i] = pSets.get(i);
			}
			return out;
		}
	}

	private static long[] toLongArray(VkDescriptorSetLayout... layouts) {
		long[] out = new long[layouts.length];
		for (int i = 0; i < layouts.length; i++) {
			out[i] = layouts[i].address();
		}
		return out;
	}

	public void reset() {
		VK10.vkResetDescriptorPool(device, pool, 0);
	}

	@Override
	public void close() {
		VK10.vkDestroyDescriptorPool(device, pool, null);
	}
}
