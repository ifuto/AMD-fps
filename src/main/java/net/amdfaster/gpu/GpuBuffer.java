package net.amdfaster.gpu;

import net.amdfaster.vk.MemoryTypeSelector;
import net.amdfaster.vk.VkContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;

/**
 * A Vulkan buffer and the single memory allocation behind it.
 *
 * <p>One allocation per buffer rather than sub-allocation. A sub-allocating allocator is the right
 * answer eventually, but it has to track free lists across frames and get fence reuse right, and
 * neither of those is worth getting wrong while the renderer is still being brought up. Sections
 * are large and few enough that the allocation count is not the bottleneck.
 *
 * <p>The memory type comes from {@link MemoryTypeSelector} restricted by the mask the driver
 * returns for this buffer, so a buffer that cannot use the preferred heap says so instead of
 * failing a validation check later.
 */
public final class GpuBuffer implements AutoCloseable {

    private final VkContext context;
    private final long handle;
    private final long memory;
    private final long size;
    private final int usageFlags;
    private final MemoryTypeSelector.Usage memoryUsage;
    private final int memoryTypeIndex;
    private final boolean hostVisible;
    private ByteBuffer mapped;

    private GpuBuffer(VkContext context, long handle, long memory, long size, int usageFlags,
                      MemoryTypeSelector.Usage memoryUsage, int memoryTypeIndex, boolean hostVisible) {
        this.context = context;
        this.handle = handle;
        this.memory = memory;
        this.size = size;
        this.usageFlags = usageFlags;
        this.memoryUsage = memoryUsage;
        this.memoryTypeIndex = memoryTypeIndex;
        this.hostVisible = hostVisible;
    }

    /**
     * @param size        bytes; must be positive
     * @param usageFlags  {@code VkBufferUsageFlagBits} combination
     * @param memoryUsage which topology the buffer belongs to
     */
    public static GpuBuffer create(VkContext context, long size, int usageFlags,
                                   MemoryTypeSelector.Usage memoryUsage) {
        if (size <= 0) {
            throw new IllegalArgumentException("buffer size must be positive, got " + size);
        }
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usageFlags)
                    // Exclusive: one queue family owns it. Sharing across families costs a
                    // driver-side ownership transfer on every access for no benefit here.
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int result = vkCreateBuffer(context.device(), bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateBuffer failed with VkResult " + result
                        + " for " + size + " bytes");
            }
            long handle = pBuffer.get(0);

            long memory = 0L;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(context.device(), handle, requirements);

                MemoryTypeSelector.Selection selection = MemoryTypeSelector.selectForBits(
                        context.memoryTypes(), memoryUsage, requirements.memoryTypeBits());

                VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(requirements.size())
                        .memoryTypeIndex(selection.typeIndex());

                LongBuffer pMemory = stack.mallocLong(1);
                result = vkAllocateMemory(context.device(), allocateInfo, null, pMemory);
                if (result != VK_SUCCESS) {
                    throw new IllegalStateException("vkAllocateMemory failed with VkResult " + result
                            + " for " + requirements.size() + " bytes in type "
                            + selection.typeIndex() + " (" + selection.reason() + ")");
                }
                memory = pMemory.get(0);

                result = vkBindBufferMemory(context.device(), handle, memory, 0);
                if (result != VK_SUCCESS) {
                    throw new IllegalStateException("vkBindBufferMemory failed with VkResult " + result);
                }

                boolean hostVisible = (context.memoryTypes().get(selection.typeIndex()).propertyFlags()
                        & MemoryTypeSelector.HOST_VISIBLE) != 0;
                return new GpuBuffer(context, handle, memory, size, usageFlags, memoryUsage,
                        selection.typeIndex(), hostVisible);
            } catch (RuntimeException e) {
                if (memory != 0L) {
                    vkFreeMemory(context.device(), memory, null);
                }
                vkDestroyBuffer(context.device(), handle, null);
                throw e;
            }
        }
    }

    public long handle() {
        return this.handle;
    }

    public long size() {
        return this.size;
    }

    public int usageFlags() {
        return this.usageFlags;
    }

    public MemoryTypeSelector.Usage memoryUsage() {
        return this.memoryUsage;
    }

    public int memoryTypeIndex() {
        return this.memoryTypeIndex;
    }

    public boolean isHostVisible() {
        return this.hostVisible;
    }

    /**
     * Maps the buffer for writing and keeps it mapped.
     *
     * <p>The mapping is created once and never remapped: on AMD, remapping a host-visible
     * allocation makes the driver flush and invalidate, which is exactly the cost this avoids.
     *
     * <p><b>Write sequentially and never read back.</b> A {@code HOST_VISIBLE} allocation without
     * {@code HOST_CACHED} is write-combined, which is as fast as {@code memcpy} can be in one
     * direction and catastrophically slow in the other, because every read is a partial burst that
     * has to be reassembled over PCIe.
     *
     * @throws IllegalStateException if the buffer is not host-visible, or already mapped
     */
    public ByteBuffer mapForWrite() {
        if (!this.hostVisible) {
            throw new IllegalStateException("buffer is not host-visible (type " + this.memoryTypeIndex + ")");
        }
        if (this.mapped != null) {
            return this.mapped;
        }
        try (MemoryStack stack = stackPush()) {
            PointerBuffer ppData = stack.mallocPointer(1);
            int result = vkMapMemory(this.context.device(), this.memory, 0L, this.size, 0, ppData);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkMapMemory failed with VkResult " + result);
            }
            this.mapped = ppData.getByteBuffer(0, (int) this.size);
            return this.mapped;
        }
    }

    /**
     * Unmaps. Only needed before destroying the buffer, or on a driver without coherent
     * host memory; a {@code HOST_COHERENT} type needs no explicit flush.
     */
    public void unmap() {
        if (this.mapped == null) {
            return;
        }
        this.mapped = null;
        vkUnmapMemory(this.context.device(), this.memory);
    }

    public boolean isMapped() {
        return this.mapped != null;
    }

    @Override
    public void close() {
        unmap();
        if (this.handle != 0L) {
            vkDestroyBuffer(this.context.device(), this.handle, null);
        }
        if (this.memory != 0L) {
            vkFreeMemory(this.context.device(), this.memory, null);
        }
    }
}
