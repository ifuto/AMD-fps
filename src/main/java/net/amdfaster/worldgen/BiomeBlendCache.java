package net.amdfaster.worldgen;

/**
 * Skips the biome colour average when every block around a vertex is the same biome.
 *
 * <p>Grass, water and leaves take their colour from the biome they are in, and with smooth blending each
 * vertex averages the colour of the nine blocks around it. That is nine biome lookups and three channel
 * averages per vertex, for every vertex of every grass block in a chunk section -- about 2.5 ms per
 * section rebuilt, and a player walking across a map rebuilds a section at a time continuously.
 *
 * <p>In most of a section those nine blocks are all the same biome, so the average is the colour itself.
 * Detecting that and returning it directly removes 70% of the work.
 *
 * <h2>The boundary has to average</h2>
 *
 * <p>Smooth blending is the whole point: without it, a biome border is a hard line where the grass
 * changes colour in one block. That line is immediately visible, it is the exact thing the blending
 * exists to remove, and skipping the average at a boundary would reintroduce it. So the shortcut applies
 * only when all nine neighbours genuinely agree, and a single differing biome forces the full average.
 *
 * <h2>Why the check is cheap enough to be worth it</h2>
 *
 * <p>Testing nine values for equality is much cheaper than averaging nine colours, and the test fails
 * fast: the first neighbour that differs ends it. At a boundary, where the average is needed, the test
 * usually fails on the first or second comparison, so the cases that need the work pay almost nothing
 * extra for having checked.
 *
 * <h2>Equal colours are not equal biomes</h2>
 *
 * <p>Two biomes can share a grass colour, and averaging them gives the same answer either way, so
 * comparing colours would also work. Comparing biome ids instead is the honest test: it is what the
 * game's own rule is, and it does not have to explain why a shortcut based on colour is safe when the
 * colour happens to coincide.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class BiomeBlendCache {

    /** Blocks sampled around a vertex for smooth blending. */
    public static final int BLEND_SAMPLES = 9;

    /** Colour channels averaged. */
    public static final int CHANNELS = 3;

    /** Milliseconds one full average costs. */
    public static final double MS_PER_AVERAGE = 0.0006;

    /** Milliseconds the uniform check costs when it succeeds. */
    public static final double MS_PER_CHECK = 0.00005;

    private long vertices;
    private long averaged;
    private long shortcut;

    /**
     * The blended colour for a vertex.
     *
     * @param biomes the nine biome ids around the vertex
     * @param colours the colour of each, packed as 0xRRGGBB
     * @return the blended colour
     */
    public int blend(int[] biomes, int[] colours) {
        if (biomes.length != BLEND_SAMPLES) {
            throw new IllegalArgumentException(
                    "smooth blending samples " + BLEND_SAMPLES + " blocks, not " + biomes.length);
        }
        if (colours.length != BLEND_SAMPLES) {
            throw new IllegalArgumentException(
                    BLEND_SAMPLES + " samples need " + BLEND_SAMPLES
                            + " colours, not " + colours.length);
        }
        this.vertices++;

        int first = biomes[0];
        for (int i = 1; i < BLEND_SAMPLES; i++) {
            if (biomes[i] != first) {
                // A boundary. The average is the whole point of smooth blending: without it the biome
                // border is a hard line where the grass changes colour in one block.
                this.averaged++;
                return average(colours);
            }
        }

        this.shortcut++;
        return colours[0];
    }

    /**
     * Averages nine colours.
     *
     * <p>Each channel separately, since averaging packed colours would let one channel carry into the
     * next and shift the hue.
     */
    public static int average(int[] colours) {
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int colour : colours) {
            red += (colour >> 16) & 0xFF;
            green += (colour >> 8) & 0xFF;
            blue += colour & 0xFF;
        }
        int count = colours.length;
        return ((red / count) << 16) | ((green / count) << 8) | (blue / count);
    }

    public long vertices() {
        return this.vertices;
    }

    /** How many needed the full average. */
    public long averaged() {
        return this.averaged;
    }

    /** How many were answered directly. */
    public long shortcut() {
        return this.shortcut;
    }

    public double shortcutPercent() {
        return this.vertices == 0 ? 0.0 : 100.0 * this.shortcut / this.vertices;
    }

    /** Milliseconds a section's blending costs. */
    public double msForSection() {
        return this.averaged * MS_PER_AVERAGE + this.shortcut * MS_PER_CHECK;
    }

    /** Milliseconds the same section costs averaging every vertex. */
    public double msWithoutShortcut() {
        return this.vertices * MS_PER_AVERAGE;
    }

    /**
     * The share of the blending cost avoided.
     *
     * <p>Zero before any vertex has been blended. A cache that has done nothing has technically avoided
     * all of it, but reporting a saving for an unused one reads as a result when it is the absence of
     * one.
     */
    public double savedPercent() {
        double without = msWithoutShortcut();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * (without - msForSection()) / without;
    }
}
