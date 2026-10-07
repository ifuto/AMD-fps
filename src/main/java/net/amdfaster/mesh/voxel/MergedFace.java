package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;

/**
 * One rectangle produced by greedy meshing: every unit face on {@code plane}, over
 * {@code [u0,u1) x [v0,v1)} in the two axes the orientation varies along, sharing one merge key.
 *
 * <p>The varying axes are (Y,Z) for X-facing orientations, (X,Z) for Y-facing and (X,Y) for
 * Z-facing, which matches {@link Orientation#cornerA}/{@link Orientation#cornerB}.
 *
 * @param plane the fixed coordinate: {@code x} for X-facing, {@code y} for Y-facing, {@code z} for
 *              Z-facing, in the range 0..size inclusive
 */
public record MergedFace(
        Orientation orientation,
        int plane,
        int u0,
        int v0,
        int u1,
        int v1,
        int key
) {

    public int uSize() {
        return this.u1 - this.u0;
    }

    public int vSize() {
        return this.v1 - this.v0;
    }

    /** How many unit faces this one quad replaced. */
    public int area() {
        return this.uSize() * this.vSize();
    }
}
