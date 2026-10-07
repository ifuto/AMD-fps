package dev.ifuto.amdfaster.overlay;

import dev.ifuto.amdfaster.AmdFaster;
import dev.ifuto.amdfaster.mesh.SectionMesher;
import dev.ifuto.amdfaster.vk.Backend;
import dev.ifuto.amdfaster.vk.FrameConstants;
import dev.ifuto.amdfaster.vk.RegionArena;
import dev.ifuto.amdfaster.vk.TerrainPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The overlay's scene: a cube of sections around the camera, meshed into a
 * {@link RegionArena} and drawn every frame with the terrain pipeline, plus a
 * line overlay (region wireframe + frame-time graph).
 *
 * <p>Camera: while active, the camera orbits the player slowly (configurable),
 * giving the benchmark a moving viewpoint; press ESC to return to the game.
 */
public final class OverlayScene implements AutoCloseable {
	private final Minecraft client;
	private final SectionMesher mesher = new SectionMesher();
	private final List<RegionArena> arenas = new ArrayList<>();
	private final Matrix4f proj = new Matrix4f();
	private final Matrix4f view = new Matrix4f();
	private final FrameConstants frameConstants;

	private int centerSectionX, centerSectionY, centerSectionZ;
	private int sectionRadius = 4;
	private float orbitAngle;
	private long lastFrameNanos;
	private float fps;

	OverlayScene(Minecraft client) {
		this.client = client;
		this.frameConstants = new FrameConstants(java.nio.ByteBuffer.allocateDirect(FrameConstants.SIZE)
				.order(java.nio.ByteOrder.nativeOrder()));
	}

	/** (Re)builds the section meshes around the player. Called on activation. */
	public void build() {
		closeArenas();
		ClientWorld level = client.world;
		if (level == null || client.player == null) {
			return;
		}
		AmdFasterConfigHolder cfg = new AmdFasterConfigHolder();
		sectionRadius = cfg.radius;

		BlockPos playerPos = client.player.blockPosition();
		centerSectionX = SectionPos.blockToSectionCoord(playerPos.getX());
		centerSectionY = SectionPos.blockToSectionCoord(playerPos.getY());
		centerSectionZ = SectionPos.blockToSectionCoord(playerPos.getZ());

		int totalSections = 0;
		int minSecY = level.getMinSectionY();
		int maxSecY = level.getMaxSectionY();

		for (int dx = -sectionRadius; dx <= sectionRadius; dx++) {
			for (int dz = -sectionRadius; dz <= sectionRadius; dz++) {
				for (int dy = -sectionRadius; dy <= sectionRadius; dy++) {
					int sx = centerSectionX + dx;
					int sy = centerSectionY + dy;
					int sz = centerSectionZ + dz;
					if (sy < minSecY || sy > maxSecY) {
						continue;
					}
					LevelChunk chunk = level.getChunk(sx, sz);
					if (chunk == null) {
						continue;
					}
					LevelChunkSection[] sections = chunk.getSections();
					int idx = level.getSectionIndexFromSectionY(sy);
					if (idx < 0 || idx >= sections.length) {
						continue;
					}
					LevelChunkSection section = sections[idx];
					if (section == null || section.isEmpty()) {
						continue;
					}
					RegionArena arena = new RegionArena(
							SectionPos.sectionToBlockCoord(sx),
							SectionPos.sectionToBlockCoord(sy),
							SectionPos.sectionToBlockCoord(sz), 1);
					Backend backend = Backend.get();
					arena.allocate(backend.memory, 1);
					ByteBuffer vb = arena.mappedVertices();
					ByteBuffer ib = arena.mappedIndices();
					if (vb == null || ib == null) {
						// device-only arena: upload via staging
						vb = java.nio.ByteBuffer.allocateDirect((int) arena.vertexBytes);
						ib = java.nio.ByteBuffer.allocateDirect((int) arena.indexBytes);
					}
					int indices = mesher.mesh(level, SectionPos.of(sx, sy, sz), section, vb, ib);
					if (indices > 0) {
						if (arena.mappedVertices() == null) {
							backend.memory.uploadNow(arena.vertexBuffer, 0, vb);
							backend.memory.uploadNow(arena.indexBuffer, 0, ib);
						}
						RegionArena.SectionDraw draw = new RegionArena.SectionDraw();
						draw.indexCount = indices;
						draw.firstIndex = 0;
						arena.sections.add(draw);
						arenas.add(arena);
						totalSections++;
					} else {
						arena.close();
					}
				}
			}
		}
		OverlayController.get().stats().sectionsMeshed = totalSections;
		AmdFaster.LOGGER.info("[AMD-Faster] overlay meshed {} sections around ({},{},{})",
				totalSections, centerSectionX, centerSectionY, centerSectionZ);
	}

	/** Records the overlay's draws into the backend's current command buffer. */
	public void renderFrame() {
		Backend backend = Backend.get();
		if (backend == null) {
			return;
		}
		long now = System.nanoTime();
		if (lastFrameNanos > 0) {
			float ms = (now - lastFrameNanos) / 1_000_000f;
			fps = fps * 0.9f + (1000f / Math.max(ms, 0.01f)) * 0.1f;
		}
		lastFrameNanos = now;
		OverlayController.get().stats().fps = fps;
		OverlayController.get().stats().frameMs = fps > 0 ? 1000f / fps : 0;

		updateCamera();
		uploadFrameConstants();

		VkCommandBuffer cmd = backend.currentCommandBuffer();
		if (cmd == null) {
			return;
		}

		TerrainPipeline.beginDynamicRendering(cmd, backend.swapchain.width, backend.swapchain.height,
				backend.swapchain.views[backend.currentImageIndex()], backend.swapchain.depthView);

		VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, backend.terrainPipeline.pipeline);

		// push constants: region origin (vec4)
		float[] origin = new float[4];
		for (RegionArena arena : arenas) {
			origin[0] = arena.regionOriginX;
			origin[1] = arena.regionOriginY;
			origin[2] = arena.regionOriginZ;
			origin[3] = 0;
			try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
				VK10.vkCmdPushConstants(cmd, backend.terrainPipeline.pipelineLayout,
						VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(origin));
			}
			VK10.vkCmdBindVertexBuffers(cmd, 0, new long[]{arena.vertexBuffer.handle}, new long[]{0});
			VK10.vkCmdBindIndexBuffer(cmd, arena.indexBuffer.handle, 0, VK10.VK_INDEX_TYPE_UINT32);
			for (RegionArena.SectionDraw draw : arena.sections) {
				VK10.vkCmdDrawIndexed(cmd, draw.indexCount, 1, draw.firstIndex, draw.vertexOffset, 0);
			}
		}

		TerrainPipeline.endDynamicRendering(cmd);
		OverlayController.get().stats().drawCalls = arenas.size();
	}

	private void updateCamera() {
		double cx = SectionPos.sectionToBlockCoord(centerSectionX) + 8;
		double cz = SectionPos.sectionToBlockCoord(centerSectionZ) + 8;
		double cy = SectionPos.sectionToBlockCoord(centerSectionY) + 8;
		orbitAngle += 0.002f;
		double radius = sectionRadius * 16.0 * 0.8;
		double ox = cx + Math.cos(orbitAngle) * radius;
		double oz = cz + Math.sin(orbitAngle) * radius;
		double oy = cy + 24;

		proj.setPerspective((float) Math.toRadians(70), (float) backend().swapchain.width / backend().swapchain.height, 0.05f, 4096f);
		view.setLookAt((float) ox, (float) oy, (float) oz, (float) cx, (float) cy, (float) cz,
				0, 1, 0);
	}

	private void uploadFrameConstants() {
		frameConstants.setProj(proj).setView(view)
				.setCamera(0, 0, 0)
				.setFog(128, 512, 0.6f, 0.53f, 0.81f, 1.0f)
				.setSkyBrightness(1.0f)
				.setEmissiveBoost(0.0f)
				.upload();
		// In v0.1 the UBO upload goes through a tiny host-visible staging buffer;
		// the descriptor binding is wired up in the follow-up that adds the
		// descriptor set allocation (see Backend.descriptors).
	}

	private Backend backend() {
		return Backend.get();
	}

	private void closeArenas() {
		for (RegionArena arena : arenas) {
			Backend backend = Backend.get();
			if (backend != null && arena.vertexBuffer != null) {
				backend.memory.free(arena.vertexBuffer);
				backend.memory.free(arena.indexBuffer);
			}
			arena.close();
		}
		arenas.clear();
	}

	@Override
	public void close() {
		closeArenas();
	}

	/** Tiny holder so the scene does not need a direct config dependency cycle. */
	private static final class AmdFasterConfigHolder {
		final int radius = dev.ifuto.amdfaster.AmdFaster.config().benchmarkRadiusSections;
	}
}
