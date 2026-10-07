package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.AmdFaster;
import dev.ifuto.amdfaster.config.AmdFasterConfig;
import dev.ifuto.amdfaster.platform.GpuProbe;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.PointerBuffer;

import java.nio.LongBuffer;

/**
 * The Vulkan backend: owns instance, device, swapchain, sync objects, command
 * pools and the per-frame command buffers. Lifecycle is driven by the mixins:
 *
 * <pre>
 *   Minecraft.runTick(HEAD)   -> {@link #beginFrame()}
 *   ...world/gui rendering...  -> (AMD-Faster renders in the overlay mode)
 *   RenderSystem.flipFrame     -> {@link #endFrame()}
 *   Minecraft.close            -> {@link #shutdown()}
 * </pre>
 *
 * <p>In v0.1 the backend renders the <b>diagnostics/benchmark overlay</b>:
 * when active it takes over the frame (the vanilla world renderer is skipped by
 * {@code GameRendererMixin}), draws the meshed sections with the terrain
 * pipeline plus the overlay lines, and presents. When inactive it is fully
 * transparent — one acquire/present cycle is still needed because the vanilla
 * GL framebuffer was never drawn to, so we present the clear color.
 */
public final class Backend implements AutoCloseable {
	private static Backend instance;

	public static Backend get() {
		return instance;
	}

	public static boolean initIfNeeded(long windowHandle) {
		if (instance != null) {
			return true;
		}
		AmdFasterConfig cfg = AmdFaster.config();
		if (!cfg.enabled || cfg.backend.equals("off")) {
			AmdFaster.setStatus(AmdFaster.BackendState.DISABLED, "config: backend=" + cfg.backend);
			return false;
		}
		try {
			AmdFaster.setStatus(AmdFaster.BackendState.INITIALIZING, "creating Vulkan instance");
			GpuProbe.probeGl();
			instance = new Backend(cfg, windowHandle);
			AmdFaster.setStatus(AmdFaster.BackendState.ACTIVE, GpuProbe.describe());
			return true;
		} catch (Throwable t) {
			AmdFaster.setStatus(AmdFaster.BackendState.FAILED, t.toString());
			try {
				if (instance != null) {
					instance.close();
				}
			} catch (Throwable ignored) {
			}
			instance = null;
			return false;
		}
	}

	// --- owned Vulkan objects ---
	public final Instance vkInstance;
	public final Device device;
	public final MemoryManager memory;
	public final PipelineCache pipelineCache;
	public final Swapchain swapchain;
	public final FrameSync sync;
	public final TerrainPipeline terrainPipeline;
	public final LinePipeline linePipeline;
	public final DescriptorAllocator descriptors;

	private long commandPool;
	private VkCommandBuffer[] commandBuffers;
	private boolean frameActive;
	private boolean swapchainDirty = true;

	/** Set by the overlay when it wants the backend to draw this frame. */
	private Runnable frameHook;

	private Backend(AmdFasterConfig cfg, long windowHandle) {
		vkInstance = new Instance(cfg, windowHandle);
		device = new Device(vkInstance, cfg);
		memory = new MemoryManager(device);
		pipelineCache = new PipelineCache(device, cfg.pipelineCache);
		swapchain = new Swapchain(device, memory, vkInstance.surface);

		int w = Minecraft.getInstance().getWindow().getFramebufferWidth();
		int h = Minecraft.getInstance().getWindow().getFramebufferHeight();
		swapchain.create(Math.max(1, w), Math.max(1, h));

		terrainPipeline = new TerrainPipeline(device, pipelineCache, swapchain.colorFormat, swapchain.depthFormat);
		linePipeline = new LinePipeline(device, pipelineCache, swapchain.colorFormat, swapchain.depthFormat);

		int frames = device.tuning.framesInFlight;
		sync = new FrameSync(device, frames);
		descriptors = new DescriptorAllocator(device, frames * 4 + 16);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandPoolCreateInfo poolCi = VkCommandPoolCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
					.flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
					.queueFamilyIndex(device.graphicsFamily);
			LongBuffer pPool = stack.mallocLong(1);
			VkUtil.check(VK10.vkCreateCommandPool(device.handle, poolCi, null, pPool), "vkCreateCommandPool");
			commandPool = pPool.get(0);

			VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
					.commandPool(commandPool)
					.level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
					.commandBufferCount(frames);
			PointerBuffer pCmds = stack.mallocPointer(frames);
			VkUtil.check(VK10.vkAllocateCommandBuffers(device.handle, ai, pCmds), "vkAllocateCommandBuffers");
			commandBuffers = new VkCommandBuffer[frames];
			for (int i = 0; i < frames; i++) {
				commandBuffers[i] = new VkCommandBuffer(pCmds.get(i), device.handle);
			}
		}
	}

	/** Installs the per-frame draw hook (the overlay). Pass {@code null} to clear. */
	public void setFrameHook(Runnable hook) {
		this.frameHook = hook;
	}

	public boolean isFrameActive() {
		return frameActive;
	}

	/** Called from Minecraft.runTick HEAD. Acquires the next swapchain image. */
	public boolean beginFrame() {
		if (swapchainDirty) {
			recreateSwapchain();
			swapchainDirty = false;
		}
		sync.beginFrame();
		int image = swapchain.acquire(sync.imageAvailable[sync.slot],
				Minecraft.getInstance().getWindow().getFramebufferWidth(),
				Minecraft.getInstance().getWindow().getFramebufferHeight());
		if (image < 0) {
			// swapchain was recreated during acquire; skip this frame
			frameActive = false;
			currentImage = -1;
			return false;
		}
		currentImage = image;
		currentCmd = commandBuffers[sync.slot];
		frameActive = true;
		return true;
	}

	/** Swapchain image index for the frame currently being recorded. */
	public int currentImageIndex() {
		return currentImage;
	}

	/** Command buffer for the frame currently being recorded (null outside a frame). */
	public VkCommandBuffer currentCommandBuffer() {
		return currentCmd;
	}

	/** Records + submits the frame. Called from RenderSystem.flipFrame redirect. */
	public void endFrame() {
		if (!frameActive) {
			return;
		}
		frameActive = false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBuffer cmd = commandBuffers[sync.slot];
			VkUtil.check(VK10.vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");

			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
					.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			VkUtil.check(VK10.vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");

			// The frame hook (overlay) records its draws here.
			if (frameHook != null) {
				frameHook.run();
			} else {
				// No overlay: clear the swapchain image so the user sees the sky color
				// rather than a stale GL framebuffer.
				TerrainPipeline.beginDynamicRendering(cmd, swapchain.width, swapchain.height,
						swapchain.views[acquiredImageIndex()], swapchain.depthView);
				TerrainPipeline.endDynamicRendering(cmd);
			}

			VkUtil.check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

			// Transition swapchain image to PRESENT_SRC before submit.
			long nextSignal = sync.nextSignalValue();
			VkSubmitInfo si = VkSubmitInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
					.pWaitSemaphores(stack.longs(sync.imageAvailable[sync.slot]))
					.pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
					.pCommandBuffers(stack.pointers(cmd))
					.pSignalSemaphores(stack.longs(sync.renderFinished[sync.slot], sync.timeline));
			if (device.has13) {
				// timeline signal value rides on VkTimelineSemaphoreSubmitInfo
				org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo ts = org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo.calloc(stack)
						.sType(VK12.VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO)
						.pSignalSemaphoreValues(stack.longs(nextSignal));
				si.pNext(ts.address());
			}
			VkUtil.check(VK10.vkQueueSubmit(device.graphicsQueue, si, VK10.VK_NULL_HANDLE), "vkQueueSubmit");
			sync.frameSubmitted();

			if (!swapchain.present(device.presentQueue, acquiredImageIndex(), sync.renderFinished[sync.slot])) {
				swapchainDirty = true;
			}
		}
	}

	private int lastAcquiredImage = -1;

	private int acquiredImageIndex() {
		return lastAcquiredImage < 0 ? 0 : lastAcquiredImage;
	}

	public void onResize(int width, int height) {
		swapchainDirty = true;
	}

	public void recreateSwapchain() {
		try {
			VK10.vkDeviceWaitIdle(device.handle);
		} catch (Throwable ignored) {
		}
		int w = Math.max(1, Minecraft.getInstance().getWindow().getFramebufferWidth());
		int h = Math.max(1, Minecraft.getInstance().getWindow().getFramebufferHeight());
		swapchain.create(w, h);
	}

	public void shutdown() {
		try {
			VK10.vkDeviceWaitIdle(device.handle);
		} catch (Throwable ignored) {
		}
		if (pipelineCache != null) {
			pipelineCache.save();
		}
	}

	@Override
	public void close() {
		shutdown();
		try {
			if (descriptors != null) descriptors.close();
			if (sync != null) sync.close();
			if (linePipeline != null) linePipeline.close();
			if (terrainPipeline != null) terrainPipeline.close();
			if (swapchain != null) swapchain.destroy();
			if (pipelineCache != null) pipelineCache.close();
			if (memory != null) memory.close();
			if (device != null) device.close();
			if (vkInstance != null) vkInstance.close();
			if (commandPool != 0L) {
				VK10.vkDestroyCommandPool(device.handle, commandPool, null);
			}
		} catch (Throwable t) {
			AmdFaster.LOGGER.warn("[AMD-Faster] teardown: {}", t.toString());
		}
		instance = null;
	}
}
