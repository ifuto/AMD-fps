package dev.ifuto.amdfaster.vk;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;

/**
 * The 192-byte std140 {@code FrameData} UBO shared by the terrain and line
 * pipelines. Written once per frame through the persistently mapped
 * {@link #buffer} (no vkCmdUpdateBuffer, no staging copy — the AMD path).
 *
 * <pre>
 *   offset   0  mat4  uProj
 *   offset  64  mat4  uView
 *   offset 128  vec4  uCamFog   xyz = camera world pos, w = fog start
 *   offset 144  vec4  uFogSky   x = fog far, y = sky brightness (0..1), z = emissive boost, w = unused
 *   offset 160  vec4  uFogColor rgb = fog color, a = fog strength (0..1)
 * </pre>
 */
public final class FrameConstants {
	public static final int SIZE = 192;

	private final ByteBuffer buffer;
	private final Matrix4f proj = new Matrix4f();
	private final Matrix4f view = new Matrix4f();
	private final Vector3f camPos = new Vector3f();
	private float fogStart, fogFar, skyBrightness, emissiveBoost, fogStrength;
	private final float[] fogColor = new float[3];

	public FrameConstants(ByteBuffer buffer) {
		this.buffer = buffer;
	}

	public FrameConstants setProj(Matrix4f m) {
		proj.set(m);
		return this;
	}

	public FrameConstants setView(Matrix4f m) {
		view.set(m);
		return this;
	}

	public FrameConstants setCamera(double x, double y, double z) {
		camPos.set((float) x, (float) y, (float) z);
		return this;
	}

	public FrameConstants setFog(float start, float far, float strength, float r, float g, float b) {
		this.fogStart = start;
		this.fogFar = far;
		this.fogStrength = strength;
		this.fogColor[0] = r;
		this.fogColor[1] = g;
		this.fogColor[2] = b;
		return this;
	}

	public FrameConstants setSkyBrightness(float v) {
		this.skyBrightness = v;
		return this;
	}

	public FrameConstants setEmissiveBoost(float v) {
		this.emissiveBoost = v;
		return this;
	}

	/** Serializes the UBO into the mapped buffer. Call once per frame before recording. */
	public void upload() {
		proj.get(0, buffer);
		view.get(64, buffer);
		buffer.putFloat(128, camPos.x);
		buffer.putFloat(132, camPos.y);
		buffer.putFloat(136, camPos.z);
		buffer.putFloat(140, fogStart);
		buffer.putFloat(144, fogFar);
		buffer.putFloat(148, skyBrightness);
		buffer.putFloat(152, emissiveBoost);
		buffer.putFloat(156, 0f);
		buffer.putFloat(160, fogColor[0]);
		buffer.putFloat(164, fogColor[1]);
		buffer.putFloat(168, fogColor[2]);
		buffer.putFloat(172, fogStrength);
	}
}
