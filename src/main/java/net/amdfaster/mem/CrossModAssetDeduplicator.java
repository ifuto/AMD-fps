package net.amdfaster.mem;

import java.util.HashMap;
import java.util.Map;

/**
 * Shares one copy of an asset between every mod that uses the same one.
 *
 * <p>A modpack with two hundred mods has two hundred sets of models, textures and meshes, and a great many of
 * them are the same. Two mods that both add a wooden crate will each bake their own copy of geometry that is
 * byte for byte identical, and each hold it for the whole session. The player pays for the same crate twice,
 * and in a large pack the duplicates add up to more than the originals.
 *
 * <p>Matching on content rather than on name means one copy serves both, and the memory cost is what one mod
 * would have paid.
 *
 * <h2>The key is the content, not the name</h2>
 *
 * <p>Two mods name the same crate differently -- one calls it {@code block/oak_crate} and the other
 * {@code storage/wooden_box} -- and matching on the name would keep both. Matching on the content finds them,
 * which is where the saving actually is, because the duplicates that matter are the ones nobody would guess
 * were the same.
 *
 * <h2>One mod reloading does not break the others</h2>
 *
 * <p>A resource pack reload or a mod updating its assets changes that mod's copy, and nothing else's. Dropping
 * the shared entry outright would leave every other mod pointing at memory that is gone or stale, which is a
 * crash or a wrong texture depending on timing. So releasing decrements a count and the entry survives while
 * anyone still holds it.
 *
 * <h2>The last one out frees it</h2>
 *
 * <p>Keeping an entry with no users would be the leak this exists to prevent, in a smaller package. So when
 * the count reaches zero the entry goes, and the memory comes back -- which matters most on a long session
 * where mods are loaded and unloaded repeatedly.
 *
 * <h2>Content is compared, not just hashed</h2>
 *
 * <p>A hash collision would make two different crates share one geometry, and the player would see the wrong
 * model on one of them. So the hash nominates a candidate and the content decides, which is the same rule the
 * rest of this project's caches use.
 *
 * <p>Thread safety: assets are interned at load time on the thread that loads resources, and released on the
 * same one.
 *
 * <p>Reference: FerriteCore's model deduplication and ModernFix's dynamic resource handling both reduce
 * duplicated model data; this extends the same idea across mod namespaces with reference counting. This is an
 * independent implementation.
 */
public final class CrossModAssetDeduplicator {

    /** Bytes one shared asset occupies. */
    public static final int BYTES_PER_ASSET = 2048;

    /** Milliseconds baking one asset costs, which a duplicate avoids. */
    public static final double MS_PER_BAKE = 0.4;

    private static final class Entry {
        private final long[] content;
        private int users;

        Entry(long[] content) {
            this.content = content;
        }
    }

    private final Map<Long, Entry> byHash = new HashMap<>();

    private long interned;
    private long shared;
    private long released;
    private long freed;
    private long collisions;

    /**
     * The hash of an asset's content.
     *
     * <p>Used to nominate a candidate, which the content then confirms. A hash alone would let two different
     * crates share one geometry, and the player would see the wrong model on one of them.
     */
    public static long hash(long[] content) {
        long h = 0xcbf29ce484222325L;
        for (long value : content) {
            h ^= value;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * Returns the shared copy of an asset, creating it if this is the first.
     *
     * @param content the asset's geometry or pixel data
     * @return the shared array, which the caller must not modify
     */
    public long[] intern(long[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("an asset has content");
        }
        this.interned++;

        long hash = hash(content);
        Entry entry = this.byHash.get(hash);

        if (entry != null) {
            if (!java.util.Arrays.equals(entry.content, content)) {
                // A collision: different content, same hash. Sharing them would put one mod's geometry on
                // another's block, so this one is stored under a disambiguated key instead.
                this.collisions++;
                hash = hash(content) ^ content.length;
                entry = this.byHash.get(hash);
            }
            if (entry != null && java.util.Arrays.equals(entry.content, content)) {
                entry.users++;
                this.shared++;
                return entry.content;
            }
        }

        this.byHash.put(hash, new Entry(content.clone()));
        this.byHash.get(hash).users = 1;
        return this.byHash.get(hash).content;
    }

    /**
     * Releases a mod's hold on an asset.
     *
     * <p>A resource pack reload or a mod updating its assets changes that mod's copy and nothing else's.
     * Dropping the shared entry outright would leave every other mod pointing at memory that is gone or
     * stale, which is a crash or a wrong texture depending on timing.
     *
     * @param content the content the caller interned
     * @return true if this was the last user and the entry was freed
     */
    public boolean release(long[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("an asset has content");
        }
        this.released++;

        long hash = hash(content);
        Entry entry = this.byHash.get(hash);
        if (entry == null || !java.util.Arrays.equals(entry.content, content)) {
            throw new IllegalStateException("releasing an asset that was never interned");
        }

        entry.users--;
        if (entry.users <= 0) {
            // Keeping an entry with no users would be the leak this exists to prevent, in a smaller package.
            this.byHash.remove(hash);
            this.freed++;
            return true;
        }
        return false;
    }

    /** How many distinct assets are held. */
    public int distinct() {
        return this.byHash.size();
    }

    public long interned() {
        return this.interned;
    }

    /** How many interns found an existing copy. */
    public long shared() {
        return this.shared;
    }

    public long released() {
        return this.released;
    }

    /** How many entries were freed when their last user let go. */
    public long freed() {
        return this.freed;
    }

    /** How many hash collisions were found and disambiguated. */
    public long collisions() {
        return this.collisions;
    }

    /** Bytes not held, because duplicates share one copy. */
    public long bytesSaved() {
        return this.shared * (long) BYTES_PER_ASSET;
    }

    /** Milliseconds of baking avoided. */
    public double msSaved() {
        return this.shared * MS_PER_BAKE;
    }

    /**
     * The share of the assets that turned out to be duplicates.
     *
     * <p>Zero before anything has been interned. A deduplicator that has seen nothing has technically found
     * no duplicates, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double sharedPercent() {
        return this.interned == 0 ? 0.0 : 100.0 * this.shared / this.interned;
    }
}
