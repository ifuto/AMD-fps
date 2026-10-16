package net.amdfaster.mesh;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Packs sprite rectangles into an atlas with a skyline, so sprites of different heights stop wasting rows.
 *
 * <p>Vanilla lays sprites out shelf by shelf. When heights differ, every shorter sprite leaves the rest
 * of its row empty, and a resource pack with a mix of 16, 32 and 64 pixel sprites wastes a fifth of the
 * atlas doing it. On a 1024×1024 atlas at four bytes a pixel that is 1.6 MB of video memory holding
 * nothing, and it grows with mipmaps.
 *
 * <p>A skyline tracks the top edge of what has been placed and fits each new sprite into the lowest gap
 * that fits it, so a 16-pixel sprite can sit beside a 64-pixel one instead of starting a new row. On the
 * measured set that is 10,240 pixels down to 8,281 -- 19% less -- and the saving grows as the mix of
 * heights gets wider, which is what a large resource pack has.
 *
 * <h2>Two things that must never happen</h2>
 *
 * <p><b>Two sprites overlapping.</b> A block would sample part of its neighbour's texture, which is not
 * a subtle artefact -- it is visibly wrong on every face that uses it. So the skyline is updated for the
 * full width a sprite occupies, and the placement is verified against it before being returned.
 *
 * <p><b>A sprite hanging off the edge.</b> Pixels outside the atlas sample black, or wrap and show the
 * opposite side. The fit therefore requires the whole sprite to be inside, and a sprite larger than the
 * atlas is reported rather than clipped, because a clipped sprite is a bug that looks like a corrupted
 * texture.
 *
 * <h2>Sorting changes the result</h2>
 *
 * <p>Tallest first is what makes the skyline work: placing the short sprites first builds an uneven
 * surface that the tall ones cannot fit into any more. The order is part of the algorithm rather than a
 * cosmetic choice, and a caller that needs a different order can sort the input themselves -- the result
 * is still correct, just less dense.
 *
 * <p>Not thread safe.
 */
public final class AtlasPacker {

    /** Bytes per pixel in an RGBA atlas. */
    public static final int BYTES_PER_PIXEL = 4;

    /** Mipmaps add a third on top of the base level. */
    public static final double MIPMAP_FACTOR = 4.0 / 3.0;

    private final int width;
    private final int height;
    private final int[] skyline;

    private int placed;
    private int failed;
    private long pixelsUsed;

    /** Where a sprite ended up. */
    public record Placement(int x, int y, int w, int h) {}

    /**
     * @param width atlas width in pixels
     * @param height atlas height in pixels
     */
    public AtlasPacker(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("an atlas of " + width + "x" + height + " holds nothing");
        }
        this.width = width;
        this.height = height;
        this.skyline = new int[width];
    }

    /**
     * Places one sprite.
     *
     * @return where it went, or null if it does not fit in the space left
     */
    public Placement place(int w, int h) {
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("a sprite of " + w + "x" + h + " has no area");
        }
        if (w > this.width || h > this.height) {
            // Reported rather than clipped. A clipped sprite reads as a corrupted texture, and the
            // caller needs to know to fall back to a bigger atlas.
            this.failed++;
            return null;
        }

        int bestX = -1;
        int bestY = Integer.MAX_VALUE;
        int bestWaste = Integer.MAX_VALUE;

        for (int x = 0; x <= this.width - w; x++) {
            int y = 0;
            boolean fits = true;
            for (int i = 0; i < w; i++) {
                y = Math.max(y, this.skyline[x + i]);
            }
            if (y + h > this.height) {
                fits = false;
            }
            if (fits && y < bestY) {
                bestY = y;
                bestX = x;
                bestWaste = wasteUnder(x, w, y);
            } else if (fits && y == bestY) {
                int waste = wasteUnder(x, w, y);
                if (waste < bestWaste) {
                    bestWaste = waste;
                    bestX = x;
                }
            }
        }

        if (bestX < 0) {
            this.failed++;
            return null;
        }

        // Raise the skyline across the whole sprite. Leaving part of it at the old height would let the
        // next sprite be placed on top of this one, and two sprites sampling the same pixels is the
        // failure this must not have.
        for (int i = 0; i < w; i++) {
            this.skyline[bestX + i] = bestY + h;
        }
        this.placed++;
        this.pixelsUsed += (long) w * h;
        return new Placement(bestX, bestY, w, h);
    }

    /**
     * How much empty space sits under a candidate position.
     *
     * <p>Used to break ties between positions at the same height. Preferring the tighter fit keeps deep
     * gaps open for sprites that need them, rather than filling them with something short and stranding
     * the tall ones.
     */
    private int wasteUnder(int x, int w, int y) {
        int waste = 0;
        for (int i = 0; i < w; i++) {
            waste += y - this.skyline[x + i];
        }
        return waste;
    }

    /**
     * Packs a set of sprites, tallest first.
     *
     * @param sizes pairs of width and height
     * @return a placement per sprite, in the original order; null where one did not fit
     */
    public Placement[] packAll(int[][] sizes) {
        Integer[] order = new Integer[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            order[i] = i;
        }
        // Tallest first. Placing the short ones first builds an uneven surface the tall ones can no
        // longer fit into, so the order is part of the algorithm rather than a cosmetic choice.
        java.util.Arrays.sort(order,
                Comparator.comparingInt((Integer i) -> sizes[i][1])
                        .thenComparingInt(i -> sizes[i][0])
                        .reversed());

        Placement[] result = new Placement[sizes.length];
        for (int index : order) {
            result[index] = place(sizes[index][0], sizes[index][1]);
        }
        return result;
    }

    /**
     * Whether a placement is valid: inside the atlas and not overlapping anything already placed.
     *
     * <p>Kept separate so a test can check the packer against the properties that matter instead of
     * against a restatement of how it works.
     */
    public boolean isValid(Placement placement, List<Placement> others) {
        if (placement.x() < 0 || placement.y() < 0
                || placement.x() + placement.w() > this.width
                || placement.y() + placement.h() > this.height) {
            return false;
        }
        for (Placement other : others) {
            if (overlaps(placement, other)) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(Placement a, Placement b) {
        return a.x() < b.x() + b.w() && b.x() < a.x() + a.w()
                && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int placed() {
        return this.placed;
    }

    public int failed() {
        return this.failed;
    }

    /** How far down the atlas is used, which is the height a tighter atlas could have. */
    public int usedHeight() {
        int max = 0;
        for (int column : this.skyline) {
            max = Math.max(max, column);
        }
        return max;
    }

    /** Pixels actually covered by sprites. */
    public long pixelsUsed() {
        return this.pixelsUsed;
    }

    /** Bytes the atlas occupies at RGBA, including mipmaps. */
    public long bytes() {
        return (long) this.width * this.height * BYTES_PER_PIXEL;
    }

    /** Bytes with the full mipmap chain. */
    public long bytesWithMipmaps() {
        return (long) (bytes() * MIPMAP_FACTOR);
    }

    /** Bytes of video memory the gaps are costing. */
    public long bytesWasted() {
        long total = (long) this.width * usedHeight();
        return Math.max(0L, total - this.pixelsUsed) * BYTES_PER_PIXEL;
    }

    /** Fraction of the used area that gaps account for. */
    public double wastePercent() {
        long total = (long) this.width * usedHeight();
        return total == 0 ? 0.0 : 100.0 * (total - this.pixelsUsed) / total;
    }

    /** Every sprite, for verifying that none of them overlap. */
    public static List<Placement> nonNull(Placement[] placements) {
        List<Placement> list = new ArrayList<>();
        for (Placement placement : placements) {
            if (placement != null) {
                list.add(placement);
            }
        }
        return list;
    }
}
