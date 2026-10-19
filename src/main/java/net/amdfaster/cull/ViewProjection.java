package net.amdfaster.cull;

/**
 * Builds the view-projection matrix the frustum and depth-pyramid passes need, from the camera's own
 * basis rather than from its angles.
 *
 * <p>Why not from yaw and pitch. Minecraft's yaw is zero facing south and grows toward the west, its
 * pitch is positive looking down, and the modelview the game builds is a conjugated quaternion followed
 * by two Euler rotations. Reproducing that from memory is how a wrong sign gets into the basis, and a
 * negated screen-x axis is invisible in every test that uses an identity matrix while mirroring every
 * occlusion lookup in the game. So this takes the three axes as inputs and the caller reads them off
 * {@code Camera.NearPlane}, whose corners are the screen's corners in world space. Nothing here decides
 * which way is right; the game already decided.
 *
 * <p>Layout and depth convention are fixed by the classes that read the result, not by preference:
 * {@link Frustum} takes element (row, column) at {@code column * 4 + row} -- which is what its own
 * {@link Frustum#translation(float, float, float)} puts x, y and z at indices 12, 13 and 14 for -- and
 * it treats the near plane as {@code z >= 0}, so view space has +Z forward. That is reversed-Z with a
 * [0, 1] range, and {@link HiZ#projectToPixels} expects the same matrix.
 *
 * <p>Source for the conventions: the Gribb-Hartmann frustum extraction the existing {@link Frustum}
 * implements, and the reversed-Z mapping in the Vulkan guide's depth range section. The projection here
 * is derived from the near plane's half extents rather than from a field of view, so a non-square pixel
 * aspect needs no separate correction -- the near plane already encodes it.
 */
public final class ViewProjection {

    private ViewProjection() {
    }

    /**
     * Writes {@code projection * view} into {@code out} and returns it.
     *
     * @param camX          camera position, world space
     * @param camY          camera position, world space
     * @param camZ          camera position, world space
     * @param rightX        the world direction of screen +x, unit length
     * @param rightY        the world direction of screen +x, unit length
     * @param rightZ        the world direction of screen +x, unit length
     * @param upX           the world direction of screen +y, unit length
     * @param upY           the world direction of screen +y, unit length
     * @param upZ           the world direction of screen +y, unit length
     * @param forwardX      the world direction the camera looks, unit length
     * @param forwardY      the world direction the camera looks, unit length
     * @param forwardZ      the world direction the camera looks, unit length
     * @param nearHalfWidth half the near plane's width, in world units
     * @param nearHalfHeight half the near plane's height, in world units
     * @param near          distance to the near plane, positive
     * @param far           distance to the far plane, greater than {@code near}
     * @param out           16 floats, column-major, overwritten
     * @return {@code out}
     */
    public static float[] build(float camX, float camY, float camZ,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ,
            float forwardX, float forwardY, float forwardZ,
            float nearHalfWidth, float nearHalfHeight, float near, float far, float[] out) {
        if (out == null || out.length != 16) {
            throw new IllegalArgumentException("out must be 16 floats");
        }
        if (!(near > 0f)) {
            throw new IllegalArgumentException("near must be positive: " + near);
        }
        if (!(far > near)) {
            throw new IllegalArgumentException("far must exceed near: " + far + " <= " + near);
        }
        if (!(nearHalfWidth > 0f) || !(nearHalfHeight > 0f)) {
            throw new IllegalArgumentException("near plane extents must be positive: "
                    + nearHalfWidth + " x " + nearHalfHeight);
        }

        // View rows. Row 2 is the forward direction itself, because view space has +Z forward; the
        // translation column is the negated dot product, which is what puts the camera at the origin.
        float v00 = rightX;
        float v01 = rightY;
        float v02 = rightZ;
        float v03 = -(rightX * camX + rightY * camY + rightZ * camZ);
        float v10 = upX;
        float v11 = upY;
        float v12 = upZ;
        float v13 = -(upX * camX + upY * camY + upZ * camZ);
        float v20 = forwardX;
        float v21 = forwardY;
        float v22 = forwardZ;
        float v23 = -(forwardX * camX + forwardY * camY + forwardZ * camZ);

        // Projection. A point at (halfWidth, 0, near) has to land on ndc x = 1, so the x scale is
        // near / halfWidth; the same argument gives the y scale, and a non-square pixel aspect needs no
        // extra term because the near plane's extents already carry it.
        float sx = near / nearHalfWidth;
        float sy = near / nearHalfHeight;

        // Reversed-Z on [0, 1] with view +Z forward: ndc z is 1 at the near plane and 0 at the far one,
        // so z_clip = a * z_view + b with a * near + b = near and a * far + b = 0.
        float a = -near / (far - near);
        float b = near * far / (far - near);

        // out = projection * view, stored column-major. The projection's fourth row is (0, 0, 1, 0), so
        // clip w is exactly view z -- which is what makes clipW <= 0 mean "behind the camera".
        out[0] = sx * v00;
        out[4] = sx * v01;
        out[8] = sx * v02;
        out[12] = sx * v03;

        out[1] = sy * v10;
        out[5] = sy * v11;
        out[9] = sy * v12;
        out[13] = sy * v13;

        out[2] = a * v20;
        out[6] = a * v21;
        out[10] = a * v22;
        out[14] = a * v23 + b;

        out[3] = v20;
        out[7] = v21;
        out[11] = v22;
        out[15] = v23;

        return out;
    }
}
