package net.amdfaster.mesh;

import java.util.Arrays;

/**
 * Builds the mesh for a chunk section once and shares it with every section that has the same blocks.
 *
 * <p>Most sections in a loaded world are not interesting. Two hundred of the four hundred within render
 * distance are pure air and need no mesh at all; another hundred and twenty are solid stone or bedrock
 * with a single block type; a seabed is the same sand repeated. Only the surface varies. Building a mesh
 * per section means building the same one hundreds of times and storing hundreds of copies of it -- at
 * 5,332 bytes each, 400 sections is 2.1 MB of video memory where 53 distinct meshes would be 283 KB.
 *
 * <p>Sharing the identical ones saves 1.85 MB and removes the meshing work for them, which is the larger
 * saving: meshing a section costs more than uploading it.
 *
 * <h2>Identical means every block</h2>
 *
 * <p>Two sections share a mesh only if their block contents are the same throughout. Merging sections
 * that differ even slightly gives one of them the other's geometry, and the symptom is terrain that is
 * missing or wrong in a way that is immediately visible -- a hole in a hillside, a floor that is not
 * there. That is the worst kind of bug to ship in a renderer, because it looks like the world is broken
 * rather than like an optimisation.
 *
 * <p>So the content is hashed and then compared, and the hash only nominates a candidate. Accepting a
 * hash collision without comparing would produce exactly that failure, rarely enough to be nearly
 * impossible to reproduce.
 *
 * <h2>Only geometry is shared</h2>
 *
 * <p>Light and ambient occlusion differ between sections even when the blocks do not: a stone section at
 * the surface is lit and one underground is not. Sharing those would make every copy of a section look
 * like whichever was built first, which is a lighting bug rather than a geometry one and much harder for
 * a player to describe. The caller keeps light in its own layer and shares only what this class holds.
 *
 * <p>Not thread safe. Meshing runs on worker threads, so a caller using this from several needs external
 * synchronisation or one deduplicator per worker.
 */
public final class SectionMeshDeduplicator {

    /** Bytes a section mesh occupies. */
    public static final int MESH_BYTES = 5332;

    /** Blocks in a section. */
    public static final int SECTION_BLOCKS = 16 * 16 * 16;

    private int[] hashes;
    private byte[][] contents;
    private byte[][] meshes;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long requests;
    private long built;
    private long shared;

    public SectionMeshDeduplicator() {
        this(1024);
    }

    public SectionMeshDeduplicator(int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.capacity = capacity;
        this.hashes = new int[capacity];
        this.contents = new byte[capacity][];
        this.meshes = new byte[capacity][];
        this.occupied = new boolean[capacity];
    }

    /**
     * The mesh for a section's contents, building it if this is the first section with them.
     *
     * @param content the section's blocks, packed by the caller
     * @param builder makes the mesh when there is nothing to share
     * @return a mesh that may be shared with other sections; the caller must not modify it
     */
    public byte[] mesh(byte[] content, Builder builder) {
        if (content.length == 0) {
            throw new IllegalArgumentException("an empty section has no geometry to decide about");
        }
        this.requests++;
        int hash = hash(content);
        int index = probe(hash, content);

        if (this.occupied[index]) {
            this.shared++;
            return this.meshes[index];
        }

        byte[] mesh = builder.build(content);
        if (mesh == null) {
            throw new IllegalArgumentException("the builder returned no mesh");
        }
        this.built++;

        if (this.size * 4 >= this.capacity * 3) {
            grow();
            index = probe(hash, content);
        }
        this.occupied[index] = true;
        this.hashes[index] = hash;
        this.contents[index] = content.clone();
        this.meshes[index] = mesh;
        this.size++;
        return mesh;
    }

    /** Builds a mesh from a section's contents. */
    public interface Builder {
        byte[] build(byte[] content);
    }

    /**
     * Finds the slot for a content hash, comparing the contents on the way.
     *
     * <p>The comparison is not optional. Accepting a hash match alone would eventually give one section
     * another's geometry, which is terrain missing from the world -- rare enough to be nearly impossible
     * to reproduce, and reported as a broken world rather than a broken mod.
     */
    private int probe(int hash, byte[] content) {
        int index = mix(hash) & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (this.hashes[index] == hash && Arrays.equals(this.contents[index], content)) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "table of " + this.capacity + " is full; probe did not terminate");
            }
        }
        return index;
    }

    private static int hash(byte[] content) {
        // Sampled rather than a full pass: a section is 4,096 blocks and hashing all of it on every
        // request would cost as much as the comparison. The full comparison still runs, so a weak hash
        // only costs time, never correctness.
        long h = content.length;
        int step = Math.max(1, content.length / 64);
        for (int i = 0; i < content.length; i += step) {
            h = h * 31 + content[i];
        }
        h ^= h >>> 32;
        return (int) (h & 0x7FFFFFFFL);
    }

    private static int mix(int hash) {
        int h = hash;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return h & 0x7FFFFFFF;
    }

    private void grow() {
        int[] oldHashes = this.hashes;
        byte[][] oldContents = this.contents;
        byte[][] oldMeshes = this.meshes;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.hashes = new int[this.capacity];
        this.contents = new byte[this.capacity][];
        this.meshes = new byte[this.capacity][];
        this.occupied = new boolean[this.capacity];
        this.size = 0;

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldHashes[i], oldContents[i]);
            this.occupied[index] = true;
            this.hashes[index] = oldHashes[i];
            this.contents[index] = oldContents[i];
            this.meshes[index] = oldMeshes[i];
            this.size++;
        }
    }

    /** Drops a cached mesh, for a section whose contents changed. */
    public void invalidate(byte[] content) {
        int index = probe(hash(content), content);
        if (this.occupied[index]) {
            this.occupied[index] = false;
            this.size--;
        }
    }

    public void clear() {
        Arrays.fill(this.occupied, false);
        this.size = 0;
    }

    /** How many distinct meshes are held. */
    public int size() {
        return this.size;
    }

    public long requests() {
        return this.requests;
    }

    /** How many meshes were actually built. */
    public long built() {
        return this.built;
    }

    /** How many sections were served from an existing mesh. */
    public long shared() {
        return this.shared;
    }

    public double sharedPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.shared / this.requests;
    }

    /** Bytes the distinct meshes occupy. */
    public long bytes() {
        return (long) this.size * MESH_BYTES;
    }

    /** Bytes the same number of sections would occupy with a mesh each. */
    public long bytesWithoutSharing() {
        return this.requests * MESH_BYTES;
    }

    public long bytesSaved() {
        return bytesWithoutSharing() - bytes();
    }
}
