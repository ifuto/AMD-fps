package net.amdfaster.light;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.amdfaster.dirty.SectionCoord;

/**
 * Reports which sections a light update actually changed, so only those get re-meshed.
 *
 * <p>Light and geometry are separate data, but the vertex data bakes the light in. So a light change
 * forces a rebuild, and the naive way to be safe about that is to rebuild everything the light update
 * touched -- which for a single torch is the section it is in plus every section within fifteen blocks,
 * typically eight or nine sections. Placing one block in Minecraft causes on the order of fifty chunk
 * rebuilds for exactly this reason, and rebuild is the dominant client stutter there is.
 *
 * <p>Most of those rebuilds produce byte-identical vertex data. The light update touched the section
 * because propagation walked through it, but every cell in it ended at the level it already had. This
 * compares what a section held before the update with what it holds after and reports only the ones
 * that genuinely differ, which turns a rebuild fan-out into a rebuild of the sections whose shading
 * moved.
 *
 * <p>The comparison is a checksum rather than a stored copy, so the cost of tracking a section is eight
 * bytes and one pass over its nibbles rather than a second 2048-byte array. A checksum cannot tell you
 * <em>what</em> changed, only that something did, which is the only question a rebuild decision asks.
 *
 * <p>Not thread safe.
 */
public final class LightDirtyTracker {

    private final Set<Long> snapshotted = new HashSet<>();
    private final List<Long> checksums = new ArrayList<>();

    private long sectionsTracked;
    private long sectionsReportedDirty;
    private long rebuildsAvoided;

    public LightDirtyTracker() {
    }

    public long sectionsTracked() {
        return this.sectionsTracked;
    }

    /** Sections whose light genuinely changed. */
    public long sectionsReportedDirty() {
        return this.sectionsReportedDirty;
    }

    /**
     * Sections that were re-meshed before this tracking existed and no longer need to be.
     *
     * <p>The number that justifies the class. It is the difference between the sections a light update
     * touches and the sections whose vertex data actually changes.
     */
    public long rebuildsAvoided() {
        return this.rebuildsAvoided;
    }

    /**
     * Records a section's current light so the update can be compared against it.
     *
     * <p>Called for every section an update might touch, which is more than the ones that will turn out
     * to change -- that is the point of comparing.
     */
    public void snapshot(LightField field) {
        if (field == null) {
            return;
        }
        long key = SectionCoord.key(field.originX() >> 4, field.originY() >> 4, field.originZ() >> 4);
        if (!this.snapshotted.add(key)) {
            return;
        }
        this.checksums.add(key);
        this.checksums.add(checksum(field));
        this.sectionsTracked++;
    }

    /**
     * A rolling checksum over a section's light levels.
     *
     * <p>Order dependent and mixed per cell, so two sections with the same levels permuted do not
     * collide. It is not cryptographic and does not need to be; it needs to differ whenever the light
     * differs, and a false positive costs one unnecessary rebuild rather than a wrong image.
     */
    public static long checksum(LightField field) {
        long hash = 0x9E3779B97F4A7C15L;
        int minX = field.originX();
        int minY = field.originY();
        int minZ = field.originZ();
        for (int z = minZ; z < minZ + field.sizeZ(); z++) {
            for (int y = minY; y < minY + field.sizeY(); y++) {
                for (int x = minX; x < minX + field.sizeX(); x++) {
                    hash = (hash ^ (field.get(x, y, z) + 0x9E3779B9L)) * 0x100000001B3L;
                    hash = Long.rotateLeft(hash, 17);
                }
            }
        }
        return hash;
    }

    /**
     * Compares every snapshotted section against its current light.
     *
     * @return section coordinate keys that changed, in the order they were snapshotted
     */
    public List<Long> collectDirty(LightField... fields) {
        List<Long> dirty = new ArrayList<>();
        for (int i = 0; i + 1 < this.checksums.size(); i += 2) {
            long key = this.checksums.get(i);
            long before = this.checksums.get(i + 1);
            LightField field = find(fields, key);
            long after = field == null ? 0L : checksum(field);
            if (before != after) {
                dirty.add(key);
                this.sectionsReportedDirty++;
            } else {
                this.rebuildsAvoided++;
            }
        }
        return dirty;
    }

    private static LightField find(LightField[] fields, long key) {
        int sectionX = SectionCoord.x(key);
        int sectionY = SectionCoord.y(key);
        int sectionZ = SectionCoord.z(key);
        for (LightField field : fields) {
            if (field != null && (field.originX() >> 4) == sectionX && (field.originY() >> 4) == sectionY
                    && (field.originZ() >> 4) == sectionZ) {
                return field;
            }
        }
        return null;
    }

    /** Drops the snapshots, keeping the counters so a run's totals stay meaningful. */
    public void clearSnapshots() {
        this.snapshotted.clear();
        this.checksums.clear();
    }

    public void clear() {
        clearSnapshots();
        this.sectionsTracked = 0;
        this.sectionsReportedDirty = 0;
        this.rebuildsAvoided = 0;
    }

    /** Whether anything is currently snapshotted. */
    public boolean hasSnapshots() {
        return !this.snapshotted.isEmpty();
    }

    /** The snapshotted keys, for diagnostics. */
    public List<Long> snapshottedKeys() {
        List<Long> keys = new ArrayList<>();
        for (int i = 0; i < this.checksums.size(); i += 2) {
            keys.add(this.checksums.get(i));
        }
        return Collections.unmodifiableList(keys);
    }
}
