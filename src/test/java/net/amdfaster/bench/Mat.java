package net.amdfaster.bench;

/**
 * Three-by-four matrix composition, which is all a software renderer needs.
 *
 * <p>A model matrix and a view matrix are multiplied once per object rather than applied to every
 * vertex twice. That is not a micro-optimisation to make a number look better; it is what the fixed
 * function pipeline always did and what a vertex shader does implicitly, and a benchmark that
 * transformed vertices twice would be measuring a renderer nobody ships.
 */
final class Mat {

    private Mat() {
    }

    /** A translation plus a turn about the vertical axis, which is how every mob in the game is placed. */
    static void translateRotateY(float x, float y, float z, float yawDegrees, float[] out) {
        float radians = (float) Math.toRadians(yawDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        // Columns of the rotation: where the model's own axes land in the world.
        out[0] = cos;
        out[1] = 0.0f;
        out[2] = -sin;
        out[3] = x;
        out[4] = 0.0f;
        out[5] = 1.0f;
        out[6] = 0.0f;
        out[7] = y;
        out[8] = sin;
        out[9] = 0.0f;
        out[10] = cos;
        out[11] = z;
    }

    /** A camera-facing quad of a given size at a point, which is what a particle is. */
    static void billboard(float px, float py, float pz, float size,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ, float[] out) {
        out[0] = rightX * size;
        out[1] = rightY * size;
        out[2] = rightZ * size;
        out[3] = px;
        out[4] = upX * size;
        out[5] = upY * size;
        out[6] = upZ * size;
        out[7] = py;
        // No third column: a billboard has no depth of its own, and giving it one would only let it
        // intersect the thing it is supposed to be floating in front of.
        out[8] = 0.0f;
        out[9] = 0.0f;
        out[10] = 0.0f;
        out[11] = pz;
    }

    /** A plain translation, which is what the rain and cloud meshes are moved by. */
    static void translate(float x, float y, float z, float[] out) {
        out[0] = 1.0f;
        out[1] = 0.0f;
        out[2] = 0.0f;
        out[3] = x;
        out[4] = 0.0f;
        out[5] = 1.0f;
        out[6] = 0.0f;
        out[7] = y;
        out[8] = 0.0f;
        out[9] = 0.0f;
        out[10] = 1.0f;
        out[11] = z;
    }

    /** Multiplies a view matrix by a model matrix. Both are twelve floats; the result overwrites out. */
    static void multiply(float[] view, float[] model, float[] out) {
        for (int row = 0; row < 3; row++) {
            float a = view[row * 4];
            float b = view[row * 4 + 1];
            float c = view[row * 4 + 2];
            float d = view[row * 4 + 3];
            out[row * 4] = a * model[0] + b * model[4] + c * model[8];
            out[row * 4 + 1] = a * model[1] + b * model[5] + c * model[9];
            out[row * 4 + 2] = a * model[2] + b * model[6] + c * model[10];
            out[row * 4 + 3] = a * model[3] + b * model[7] + c * model[11] + d;
        }
    }
}
