package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Orientation;

import java.util.function.Consumer;

/**
 * Greedy meshing: merge runs of coplanar, identically-keyed unit faces into the largest possible
 * rectangles.
 *
 * <p>This is where the vertex count actually comes down. {@code MeshletBuilder} cannot deduplicate
 * corners between neighbouring blocks, because each block face carries its own texture coordinates
 * and so shared corners are not equal; merging the faces before they become quads is the only thing
 * that reduces the count. See {@code docs/notes/11} §5.
 *
 * <p>The algorithm is the standard one: for each of the six orientations and each plane along that
 * orientation's axis, build a 2D mask of merge keys, then sweep it row by row, extending each run
 * right and then down for as long as every cell in the growing rectangle holds the same key.
 */
public final class GreedyMesher {

    private GreedyMesher() {
    }

    public static void mesh(VoxelView view, Consumer<MergedFace> sink) {
        for (Orientation orientation : Orientation.values()) {
            meshOrientation(view, orientation, sink);
        }
    }

    private static void meshOrientation(VoxelView view, Orientation orientation, Consumer<MergedFace> sink) {
        int axis = orientation.axis();
        int sizeAlongAxis = sizeOf(view, axis);
        int sizeU = sizeOf(view, uAxis(axis));
        int sizeV = sizeOf(view, vAxis(axis));

        // Faces sit on the boundary between blocks, so there is one more plane than blocks.
        int[] mask = new int[sizeU * sizeV];

        for (int plane = 0; plane <= sizeAlongAxis; plane++) {
            int block = orientation.isPositive() ? plane - 1 : plane;
            if (block < 0 || block >= sizeAlongAxis) {
                continue;
            }

            for (int v = 0; v < sizeV; v++) {
                for (int u = 0; u < sizeU; u++) {
                    int key = keyAt(view, orientation, axis, block, u, v);
                    mask[v * sizeU + u] =
                            key != VoxelView.NO_GEOMETRY && !neighbourIsOpaque(view, orientation, axis, block, u, v)
                                    ? key
                                    : 0;
                }
            }

            sweep(mask, sizeU, sizeV, orientation, plane, sink);
        }
    }

    private static void sweep(int[] mask, int sizeU, int sizeV, Orientation orientation, int plane,
                              Consumer<MergedFace> sink) {
        for (int v = 0; v < sizeV; v++) {
            int u = 0;
            while (u < sizeU) {
                int key = mask[v * sizeU + u];
                if (key == 0) {
                    u++;
                    continue;
                }

                int width = 1;
                while (u + width < sizeU && mask[v * sizeU + u + width] == key) {
                    width++;
                }

                int height = 1;
                while (v + height < sizeV && rowMatches(mask, sizeU, v + height, u, width, key)) {
                    height++;
                }

                for (int row = v; row < v + height; row++) {
                    for (int col = u; col < u + width; col++) {
                        mask[row * sizeU + col] = 0;
                    }
                }

                sink.accept(new MergedFace(orientation, plane, u, v, u + width, v + height, key));
                u += width;
            }
        }
    }

    private static boolean rowMatches(int[] mask, int sizeU, int row, int u, int width, int key) {
        int base = row * sizeU + u;
        for (int i = 0; i < width; i++) {
            if (mask[base + i] != key) {
                return false;
            }
        }
        return true;
    }

    /** The axis u varies along: (Y,Z) for X-facing, (X,Z) for Y-facing, (X,Y) for Z-facing. */
    private static int uAxis(int axis) {
        return axis == 0 ? 1 : 0;
    }

    /** The axis v varies along: Z for both X- and Y-facing, Y for Z-facing. */
    private static int vAxis(int axis) {
        return axis == 2 ? 1 : 2;
    }

    private static int sizeOf(VoxelView view, int axis) {
        return switch (axis) {
            case 0 -> view.sizeX();
            case 1 -> view.sizeY();
            default -> view.sizeZ();
        };
    }

    private static int keyAt(VoxelView view, Orientation orientation, int axis, int block, int u, int v) {
        return view.key(coordX(axis, block, u, v), coordY(axis, block, u, v), coordZ(axis, block, u, v),
                orientation);
    }

    private static boolean neighbourIsOpaque(VoxelView view, Orientation orientation, int axis,
                                             int block, int u, int v) {
        int neighbour = block + (orientation.isPositive() ? 1 : -1);
        return view.isOpaque(coordX(axis, neighbour, u, v),
                coordY(axis, neighbour, u, v),
                coordZ(axis, neighbour, u, v));
    }

    // Spelled out per axis rather than derived, because the u/v axes are not simply
    // (axis+1, axis+2): X-facing varies along (Y,Z), Y-facing along (X,Z), Z-facing along (X,Y).
    private static int coordX(int axis, int block, int u, int v) {
        return switch (axis) {
            case 0 -> block;
            case 1 -> u;
            default -> u;
        };
    }

    private static int coordY(int axis, int block, int u, int v) {
        return switch (axis) {
            case 0 -> u;
            case 1 -> block;
            default -> v;
        };
    }

    private static int coordZ(int axis, int block, int u, int v) {
        return switch (axis) {
            case 0 -> v;
            case 1 -> v;
            default -> block;
        };
    }
}
