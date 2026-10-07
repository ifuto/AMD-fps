package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.AmdFaster;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vulkan pipeline cache, persisted to {@code config/amdfaster-pipeline-cache.bin}.
 *
 * <p>AMD driver pipelines cache well (ACO benefits from it, AMDVLK/proprietary even
 * more so) — this is the single biggest "stutter on first world load" reducer we
 * can ship without touching the game's logic. The blob is opaque; the cache
 * header embeds the driver UUID so a driver upgrade simply invalidates it.
 */
public final class PipelineCache implements AutoCloseable {
	private final VkDevice device;
	public final long handle;

	public PipelineCache(Device device, boolean enabled) {
		this.device = device.handle;
		ByteBuffer initial = null;
		if (enabled) {
			Path path = FabricLoader.getInstance().getConfigDir().resolve("amdfaster-pipeline-cache.bin");
			try {
				if (Files.exists(path)) {
					byte[] bytes = Files.readAllBytes(path);
					if (bytes.length > 4) {
						initial = MemoryUtil.memAlloc(bytes.length);
						initial.put(bytes).flip();
					}
				}
			} catch (IOException e) {
				AmdFaster.LOGGER.warn("[AMD-Faster] pipeline cache read failed: {}", e.toString());
			}
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineCacheCreateInfo ci = VkPipelineCacheCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO)
					.pInitialData(initial);
			LongBuffer pCache = stack.mallocLong(1);
			VkUtil.check(VK10.vkCreatePipelineCache(device.handle, ci, null, pCache), "vkCreatePipelineCache");
			this.handle = pCache.get(0);
		}
		if (initial != null) {
			MemoryUtil.memFree(initial);
		}
	}

	public void save() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer sizeBuf = stack.mallocInt(1);
			VK10.vkGetPipelineCacheData(device, handle, sizeBuf, null);
			int size = sizeBuf.get(0);
			if (size <= 0) {
				return;
			}
			ByteBuffer data = stack.malloc(size);
			VK10.vkGetPipelineCacheData(device, handle, sizeBuf, data);
			Path path = FabricLoader.getInstance().getConfigDir().resolve("amdfaster-pipeline-cache.bin");
			Files.createDirectories(path.getParent());
			Files.write(path, toByteArray(data));
			AmdFaster.LOGGER.info("[AMD-Faster] pipeline cache saved ({} bytes)", size);
		} catch (IOException e) {
			AmdFaster.LOGGER.warn("[AMD-Faster] pipeline cache write failed: {}", e.toString());
		}
	}

	private static byte[] toByteArray(ByteBuffer buf) {
		ByteBuffer dup = buf.duplicate();
		dup.clear();
		byte[] out = new byte[dup.remaining()];
		dup.get(out);
		return out;
	}

	@Override
	public void close() {
		if (handle != 0L) {
			VK10.vkDestroyPipelineCache(device, handle, null);
		}
	}
}
