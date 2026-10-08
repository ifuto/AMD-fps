package net.amdfaster.mc;

/**
 * Section and coordinate arithmetic.
 *
 * <p>Trivial, and therefore exactly where the bug lives. Java's {@code /} and {@code %} truncate
 * towards zero, so {@code -1 / 16} is {@code 0} and {@code -1 % 16} is {@code -1}: a block one step
 * below the origin lands in section 0 at local coordinate -1 instead of section -1 at local
 * coordinate 15. Every section at a negative coordinate is then read from the wrong place, and the
 * symptom is a world that looks correct near the spawn point and shreds itself in three of the four
 * directions.
 *
 * <p>The shift and mask forms are correct for negatives because Java's {@code >>} on an int is an
 * arithmetic shift and {@code &} operates on the two's complement representation. Everything here
 * uses those, and {@link SectionCoordsTest} asserts the round trip across zero.
 */
public final class SectionCoords {

    /** Blocks per section edge. Minecraft sections are 16x16x16. */
    public static final int SIZE = 16;

    /** {@link #SIZE} - 1; the mask for the local coordinate. */
    public static final int MASK = SIZE - 1;

    private SectionCoords() {
    }

    /** Index of the section containing {@code coord}, negative and inclusive of the origin. */
    public static int sectionOf(int coord) {
        return coord >> 4;
    }

    /** Coordinate of {@code coord} within its section, always 0..15. */
    public static int localOf(int coord) {
        return coord & MASK;
    }

    /** World coordinate of the low edge of section {@code sectionIndex}. */
    public static int originOf(int sectionIndex) {
        return sectionIndex << 4;
    }

    /** World coordinate from a section index and a local offset. */
    public static int toWorld(int sectionIndex, int local) {
        return originOf(sectionIndex) + local;
    }

    /** Number of sections spanning the half-open range {@code [min, max)}. */
    public static int sectionCount(int min, int max) {
        if (max <= min) {
            return 0;
        }
        return sectionOf(max - 1) - sectionOf(min) + 1;
    }

    /** True when {@code local} is a valid offset within a section. */
    public static boolean isValidLocal(int local) {
        return local >= 0 && local < SIZE;
    }
}
