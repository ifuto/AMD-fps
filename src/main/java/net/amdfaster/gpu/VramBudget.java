package net.amdfaster.gpu;

import java.util.Arrays;

/**
 * Cuts texture memory by storing each distinct tile once and picking a compressed format.
 *
 * <p>A block atlas is a grid of tiles, and a great many of them are the same picture: every variant of
 * a stone face, every rotation of a symmetric block, every frame of an animation that does not animate.
 * On a 1,024 tile atlas at 16 by 16 RGBA8, forty percent duplication is 1.68 MB of repeated pixels.
 * Storing each distinct tile once and pointing the rest at it removes that, and the pointers cost a few
 * bytes where the tiles cost a kilobyte each.
 *
 * <h2>Deduplication has to be exact</h2>
 *
 * <p>Two tiles that differ by one pixel are two tiles. Merging them because they look alike produces a
 * block wearing another block's texture, which a player sees immediately and reports as a graphics bug
 * with no idea what caused it. So tiles are compared by their full contents, and the hash is only a way
 * to find candidates quickly -- every candidate is verified against the stored tile before it is
 * accepted as a duplicate.
 *
 * <p>A hash collision that was not verified would be exactly the bug above, and it would happen rarely
 * enough to be nearly impossible to reproduce.
 *
 * <h2>Format selection</h2>
 *
 * <p>Block compression is decoded in hardware on both AMD and Intel, so a compressed texture costs
 * nothing at fetch time and a quarter to an eighth of the bandwidth. BC1 is half a byte a pixel and
 * has no usable alpha; BC3 and BC7 are a byte a pixel and do. Choosing BC1 for a texture with real
 * alpha throws the alpha away, and choosing BC7 for one without it doubles the memory for nothing. The
 * choice is made from whether the tile actually uses alpha, not from what the resource pack asked for.
 *
 * <p>Mipmaps add a third on top of whatever the base level costs, which is worth accounting for because
 * it is the difference between an atlas fitting and not fitting.
 *
 * <p>Not thread safe.
 */
public final class VramBudget {

    /** Edge of a block atlas tile. */
    public static final int TILE_SIZE = 16;

    /** Pixels in a tile. */
    public static final int TILE_PIXELS = TILE_SIZE * TILE_SIZE;

    /** Bytes per pixel, RGBA8. */
    public static final int BYTES_PER_PIXEL = 4;

    /** Bytes in one uncompressed tile. */
    public static final int TILE_BYTES = TILE_PIXELS * BYTES_PER_PIXEL;

    /** Texture formats this will choose between. */
    public enum Format {
        /** Uncompressed. The baseline everything else is measured against. */
        RGBA8(4.0, true),

        /** Half a byte a pixel, no usable alpha. */
        BC1(0.5, false),

        /** A byte a pixel with alpha. */
        BC3(1.0, true),

        /** A byte a pixel, better quality than BC3 at the same size. */
        BC7(1.0, true),

        /** Half a byte a pixel, one channel. For light maps. */
        BC4(0.5, false);

        /** Bytes per pixel. */
        public final double bytesPerPixel;

        /** Whether the format carries alpha. */
        public final boolean hasAlpha;

        Format(double bytesPerPixel, boolean hasAlpha) {
            this.bytesPerPixel = bytesPerPixel;
            this.hasAlpha = hasAlpha;
        }

        /** Bytes for a texture of this size, before mipmaps. */
        public long bytesFor(int width, int height) {
            return (long) (width * height * this.bytesPerPixel);
        }
    }

    /**
     * Chooses a format for a texture.
     *
     * @param usesAlpha whether any pixel has an alpha below 255
     * @param quality whether the extra cost of BC7 over BC3 is wanted
     */
    public static Format chooseFormat(boolean usesAlpha, boolean quality) {
        if (!usesAlpha) {
            return Format.BC1;
        }
        return quality ? Format.BC7 : Format.BC3;
    }

    /**
     * Bytes for a texture including the full mip chain.
     *
     * <p>A third more than the base level, and the difference between an atlas fitting in the budget and
     * not.
     */
    public static long bytesWithMipmaps(Format format, int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException(
                    "a texture is at least 1 by 1: " + width + "x" + height);
        }
        long total = 0;
        int w = width;
        int h = height;
        // Add first, then check for the end. An earlier version halved first and broke on reaching
        // 1 by 1, which dropped the last level -- four bytes out of 5.6 MB, and the kind of off-by-one
        // that reads as correct because the number is close.
        while (true) {
            total += format.bytesFor(w, h);
            if (w == 1 && h == 1) {
                break;
            }
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return total;
    }

    private int[] hashes;
    private int[][] tiles;
    private int[] alias;
    private int capacity;
    private int uniqueCount;

    private long tilesSeen;
    private long duplicates;
    private long hashCollisions;

    public VramBudget() {
        this(1024);
    }

    public VramBudget(int tileCapacity) {
        if (tileCapacity < 16) {
            throw new IllegalArgumentException("tile capacity must be at least sixteen: "
                    + tileCapacity);
        }
        this.capacity = roundUp(tileCapacity);
        this.hashes = new int[this.capacity];
        this.tiles = new int[this.capacity][];
        this.alias = new int[this.capacity];
        Arrays.fill(this.alias, -1);
    }

    private static int roundUp(int capacity) {
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        return rounded <= 0 ? 16 : rounded;
    }

    /**
     * Registers a tile.
     *
     * @param pixels {@link #TILE_PIXELS} ints, ARGB
     * @return the index of the unique tile this one is stored as
     */
    public int addTile(int[] pixels) {
        if (pixels.length != TILE_PIXELS) {
            throw new IllegalArgumentException(
                    "a tile is " + TILE_PIXELS + " pixels: " + pixels.length);
        }
        this.tilesSeen++;
        int hash = contentHash(pixels);
        int index = hash & (this.capacity - 1);
        int visited = 0;

        while (this.alias[index] >= 0) {
            if (this.hashes[index] == hash) {
                // Same hash is not the same tile. Comparing the contents is what keeps a collision
                // from turning into two blocks sharing a texture.
                if (Arrays.equals(this.tiles[index], pixels)) {
                    this.duplicates++;
                    return index;
                }
                this.hashCollisions++;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "tile table of " + this.capacity + " is full");
            }
        }

        this.hashes[index] = hash;
        this.tiles[index] = pixels.clone();
        this.alias[index] = index;
        this.uniqueCount++;
        return index;
    }

    /**
     * A hash over the whole tile.
     *
     * <p>Every pixel contributes, so two tiles differing in one pixel nearly always differ here. They
     * can still collide, which is why the caller compares the contents.
     */
    public static int contentHash(int[] pixels) {
        int h = pixels.length;
        for (int pixel : pixels) {
            h = h * 31 + pixel;
        }
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    /** Whether a tile uses alpha anywhere. */
    public static boolean usesAlpha(int[] pixels) {
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 0xFF) {
                return true;
            }
        }
        return false;
    }

    /** How many distinct tiles are stored. */
    public int uniqueCount() {
        return this.uniqueCount;
    }

    /** Uncompressed bytes the distinct tiles occupy, before mipmaps. */
    public long uniqueBytes() {
        return (long) this.uniqueCount * TILE_BYTES;
    }

    /** Bytes that would have been stored without deduplication. */
    public long bytesWithoutDedup() {
        return this.tilesSeen * TILE_BYTES;
    }

    /** Bytes saved by storing each tile once. */
    public long bytesSaved() {
        return bytesWithoutDedup() - uniqueBytes();
    }

    public double savedPercent() {
        return this.tilesSeen == 0 ? 0.0 : 100.0 * this.duplicates / this.tilesSeen;
    }

    public long tilesSeen() {
        return this.tilesSeen;
    }

    public long duplicates() {
        return this.duplicates;
    }

    /**
     * Times two different tiles produced the same hash.
     *
     * <p>Reported because a number that grows with the atlas is a sign the hash is weak, and a weak
     * hash costs a full tile comparison on every insert.
     */
    public long hashCollisions() {
        return this.hashCollisions;
    }

    /** The stored tile, copied. */
    public int[] tileAt(int index) {
        if (index < 0 || index >= this.capacity || this.alias[index] < 0) {
            throw new IndexOutOfBoundsException("no tile at " + index);
        }
        return this.tiles[index].clone();
    }
}
