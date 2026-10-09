package net.amdfaster.light;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.amdfaster.dirty.SectionCoord;

/**
 * Holds per-section light, allocating nothing for a section that is uniformly dark.
 *
 * <p>Most sections in a loaded world have no light in them at all. Underground sections below the
 * first cave are fully opaque stone; sections above the terrain are empty air with no sky access
 * recorded; and a section that has never been lit has never been written to. Allocating a nibble array
 * for each of those costs 2048 bytes for 4096 cells that all read as zero. At render distance 16 there
 * are thousands of loaded sections, so the arrays for the dark ones are a larger resident footprint
 * than the ones that actually hold light -- for data that is never read, because a read of an
 * unallocated section and a read of an allocated all-zero one return the same thing.
 *
 * <p>So the array is allocated on the first write. Reads of an absent section return 0 without
 * touching any map, which keeps the common read path a single null check rather than a lookup.
 *
 * <p>The store also owns cross-boundary invalidation. Light does not respect chunk boundaries: a torch
 * at the edge of one section lights the next section over, and it reaches up to fifteen blocks away,
 * which is further than one section is wide. So replacing a chunk's light invalidates the light of the
 * eight columns around it as well, and a chunk that was lit before its neighbour arrived has to be
 * relit when the neighbour loads. Getting this wrong leaves seams of darkness at chunk borders that
 * persist until the player walks away and back.
 *
 * <p>Not thread safe.
 */
public final class SectionLightStore {

    private final Map<Long, LightField> sections = new HashMap<>();
    private final Map<Long, SkyHeightmap> heightmaps = new HashMap<>();

    /** Section columns whose light is stale and needs recomputation. */
    private final Map<Long, Boolean> affectedColumns = new HashMap<>();

    private long peakSections;
    private long relightsCausedByLateNeighbour;

    public SectionLightStore() {
    }

    /**
     * The field for a section, or null if it has none.
     *
     * <p>Returning null rather than a shared empty instance is deliberate: a shared instance would have
     * to reject writes, and a caller that writes to it would silently lose the write. A null forces the
     * caller to decide whether it is reading, which does not need a field, or writing, which does.
     */
    public LightField fieldOrNull(int sectionX, int sectionY, int sectionZ) {
        return this.sections.get(SectionCoord.key(sectionX, sectionY, sectionZ));
    }

    /** Reads a light level. An absent section reads as dark, which is the only correct answer. */
    public int get(int x, int y, int z) {
        LightField field = this.sections.get(SectionCoord.key(x >> 4, y >> 4, z >> 4));
        return field == null ? 0 : field.get(x, y, z);
    }

    /**
     * Writes a light level, allocating the section's field on first use.
     *
     * @return true if the value changed
     */
    public boolean set(int x, int y, int z, int level) {
        if (level == 0) {
            // Writing darkness to a section that does not exist would allocate it just to store zeros.
            LightField existing = this.sections.get(SectionCoord.key(x >> 4, y >> 4, z >> 4));
            return existing != null && existing.set(x, y, z, 0);
        }
        LightField field = this.sections.computeIfAbsent(SectionCoord.key(x >> 4, y >> 4, z >> 4),
                key -> LightField.forSection(SectionCoord.x(key), SectionCoord.y(key), SectionCoord.z(key)));
        if (this.sections.size() > this.peakSections) {
            this.peakSections = this.sections.size();
        }
        return field.set(x, y, z, level);
    }

    public SkyHeightmap heightmap(int sectionX, int sectionZ) {
        return this.heightmaps.computeIfAbsent(SectionCoord.key(sectionX, 0, sectionZ),
                key -> SkyHeightmap.forSection(sectionX, sectionZ));
    }

    public int sectionCount() {
        return this.sections.size();
    }

    /** Peak simultaneous allocated sections. */
    public long peakSections() {
        return this.peakSections;
    }

    /** Bytes of light arrays actually allocated. */
    public long allocatedBytes() {
        return this.sections.size() * (long) LightField.SECTION_BYTES;
    }

    /**
     * Bytes a store that allocated eagerly for every section in the same range would have used.
     *
     * <p>The comparison is the whole argument for lazy allocation, so it is worth being able to state
     * it rather than assert it in prose.
     */
    public long bytesIfEager(int columnsWide, int sectionsTall) {
        return (long) columnsWide * columnsWide * sectionsTall * LightField.SECTION_BYTES;
    }

    /**
     * Marks a column, and the eight around it, as needing recomputation.
     *
     * <p>Nine rather than one because propagation crosses the boundary in both directions. A block
     * placed at the edge of a column can change light up to fifteen blocks away, which is more than one
     * column wide, so every neighbouring column may hold stale values.
     *
     * @return how many column marks this call added, excluding ones already pending
     */
    public int markColumnAffected(int sectionX, int sectionZ) {
        int added = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (this.affectedColumns.putIfAbsent(SectionCoord.key(sectionX + dx, 0, sectionZ + dz),
                        Boolean.TRUE) == null) {
                    added++;
                }
            }
        }
        return added;
    }

    /**
     * Records that a neighbour arrived after this column was lit, so its light has to be recomputed.
     *
     * <p>A chunk lit while its neighbour was still unloaded cannot have received light from that
     * neighbour. Without relighting on arrival, the border facing the late neighbour stays dark
     * permanently.
     */
    public void markRelitForLateNeighbour(int sectionX, int sectionZ) {
        this.relightsCausedByLateNeighbour += markColumnAffected(sectionX, sectionZ);
    }

    public long relightsCausedByLateNeighbour() {
        return this.relightsCausedByLateNeighbour;
    }

    /** Drains the pending column marks. */
    public List<long[]> drainAffectedColumns() {
        List<long[]> drained = new ArrayList<>(this.affectedColumns.size());
        for (Long key : this.affectedColumns.keySet()) {
            drained.add(new long[] {key});
        }
        this.affectedColumns.clear();
        return drained;
    }

    public int pendingColumnCount() {
        return this.affectedColumns.size();
    }

    /** Drops a section's light and any heightmap above it. */
    public void removeSection(int sectionX, int sectionY, int sectionZ) {
        this.sections.remove(SectionCoord.key(sectionX, sectionY, sectionZ));
    }

    public void clear() {
        this.sections.clear();
        this.heightmaps.clear();
        this.affectedColumns.clear();
    }
}
