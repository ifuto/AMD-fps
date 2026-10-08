package net.amdfaster.light;

/**
 * One axis-aligned face, addressed the way the mesher addresses it.
 *
 * <p>Exists so {@link SmoothLight} can be handed a face without either of them knowing about
 * winding tables. The four corners are identified by their {@code (u,v)} coordinate, which the
 * caller already has, and the outward direction along each in-plane axis is derived from whether
 * that coordinate is the minimum or the maximum. Deriving it rather than passing it is deliberate:
 * a winding table indexed by corner number is where this kind of code quietly goes wrong, and this
 * project has already shipped one such bug.
 *
 * <p>The in-plane axes follow the mesher's convention: a face on the X axis varies over
 * {@code (u=Y, v=Z)}, on the Y axis over {@code (u=X, v=Z)}, and on the Z axis over
 * {@code (u=X, v=Y)}.
 *
 * @param axis     the face's normal axis: 0 = X, 1 = Y, 2 = Z
 * @param positive true for the face pointing along {@code +axis}
 * @param plane    coordinate of the face plane along the normal axis; a positive face of the block
 *                 at {@code b} sits at {@code b+1}, a negative one at {@code b}
 * @param minU,maxU extent along the first in-plane axis, in blocks, half-open at {@code maxU}
 * @param minV,maxV extent along the second in-plane axis, half-open at {@code maxV}
 */
public record FaceRef(int axis, boolean positive, int plane, int minU, int maxU, int minV, int maxV) {

    public FaceRef {
        if (axis < 0 || axis > 2) {
            throw new IllegalArgumentException("axis must be 0..2: " + axis);
        }
        if (maxU <= minU || maxV <= minV) {
            throw new IllegalArgumentException("empty face: u " + minU + ".." + maxU
                    + ", v " + minV + ".." + maxV);
        }
    }

    /** World-space X of the point at in-plane coordinate {@code (u,v)}. */
    public int x(int u, int v, int planeBlock) {
        return this.axis == 0 ? planeBlock : u;
    }

    /** World-space Y of the point at in-plane coordinate {@code (u,v)}. */
    public int y(int u, int v, int planeBlock) {
        return switch (this.axis) {
            case 0 -> u;
            case 1 -> planeBlock;
            default -> v;
        };
    }

    /** World-space Z of the point at in-plane coordinate {@code (u,v)}. */
    public int z(int u, int v, int planeBlock) {
        return switch (this.axis) {
            case 0 -> v;
            case 1 -> v;
            default -> planeBlock;
        };
    }

    /**
     * Coordinate along the normal axis of the block that sits directly in front of this face.
     *
     * <p>A positive face at plane {@code p} belongs to the block starting at {@code p-1}, so the air
     * in front of it starts at {@code p}. A negative face at {@code p} belongs to the block starting
     * at {@code p}, so the air in front starts at {@code p-1}.
     */
    public int frontBlock() {
        return this.positive ? this.plane : this.plane - 1;
    }

    /** True when the in-plane coordinate {@code u} is at the low end of the face. */
    public boolean isLowU(int u) {
        return u == this.minU;
    }

    public boolean isLowV(int v) {
        return v == this.minV;
    }

    /** Block index along U containing the corner at {@code u}. */
    public int baseU(int u) {
        return isLowU(u) ? this.minU : this.maxU - 1;
    }

    /** Block index along V containing the corner at {@code v}. */
    public int baseV(int v) {
        return isLowV(v) ? this.minV : this.maxV - 1;
    }

    /** Block index along U just outside the face's own footprint, at the corner {@code u}. */
    public int sideU(int u) {
        return isLowU(u) ? this.minU - 1 : this.maxU;
    }

    /** Block index along V just outside the face's own footprint, at the corner {@code v}. */
    public int sideV(int v) {
        return isLowV(v) ? this.minV - 1 : this.maxV;
    }
}
