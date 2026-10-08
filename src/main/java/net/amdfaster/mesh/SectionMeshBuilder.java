package net.amdfaster.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the quads of one section and emits a {@link SectionMesh}.
 *
 * <p>Each quad goes into the builder for its orientation; when that builder fills up at
 * {@link Meshlet#MAX_QUADS} the finished meshlet is taken and a fresh builder started, so a section
 * can hold any number of meshlets per bucket.
 */
public final class SectionMeshBuilder {

    private final int originX;
    private final int originY;
    private final int originZ;
    private final MeshletBuilder[] current = new MeshletBuilder[Orientation.COUNT];
    private final List<Meshlet>[] finished;

    @SuppressWarnings("unchecked")
    public SectionMeshBuilder(int originX, int originY, int originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.finished = new List[Orientation.COUNT];
        for (Orientation o : Orientation.values()) {
            this.current[o.ordinal()] = new MeshletBuilder(o);
            this.finished[o.ordinal()] = new ArrayList<>();
        }
    }

    /** Adds a quad whose sidedness is unknown, so its meshlet will not be back-face culled. */
    public void add(Quad quad) {
        add(quad, false);
    }

    /**
     * Adds a quad, declaring whether it is seen from one side only.
     *
     * <p>Forwarded to the per-orientation builder, which ANDs the declarations: a meshlet is
     * back-face culled only when every quad in it can be.
     */
    public void add(Quad quad, boolean singleSided) {
        if (quad.isDegenerate()) {
            return;
        }
        MeshletBuilder builder = this.current[quad.orientation().ordinal()];
        if (builder.isFull()) {
            this.finished[quad.orientation().ordinal()].add(builder.build());
        }
        this.current[quad.orientation().ordinal()].add(quad, singleSided);
    }

    public SectionMesh build() {
        Meshlet[][] meshlets = new Meshlet[Orientation.COUNT][];
        int[] quadCounts = new int[Orientation.COUNT];
        int totalQuads = 0;
        int totalVertices = 0;

        for (Orientation o : Orientation.values()) {
            MeshletBuilder builder = this.current[o.ordinal()];
            if (!builder.isEmpty()) {
                this.finished[o.ordinal()].add(builder.build());
            }
            meshlets[o.ordinal()] = this.finished[o.ordinal()].toArray(new Meshlet[0]);
            for (Meshlet meshlet : meshlets[o.ordinal()]) {
                quadCounts[o.ordinal()] += meshlet.quadCount();
                totalVertices += meshlet.vertexCount();
            }
            totalQuads += quadCounts[o.ordinal()];
        }

        return new SectionMesh(this.originX, this.originY, this.originZ,
                meshlets, quadCounts, totalQuads, totalVertices);
    }
}
