package net.amdfaster.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The negative-coordinate cases are the reason this class and its test exist. Java's {@code /} and
 * {@code %} truncate towards zero, so the obvious implementation puts block -1 in section 0 at
 * local -1 instead of section -1 at local 15, and the world renders correctly near spawn and falls
 * apart in three of the four directions.
 */
class SectionCoordsTest {

    @Test
    void theConstantsDescribeASixteenBlockSection() {
        assertEquals(16, SectionCoords.SIZE);
        assertEquals(15, SectionCoords.MASK);
    }

    @Test
    void positiveCoordinatesBehaveTheObviousWay() {
        assertEquals(0, SectionCoords.sectionOf(0));
        assertEquals(0, SectionCoords.localOf(0));
        assertEquals(0, SectionCoords.sectionOf(15));
        assertEquals(15, SectionCoords.localOf(15));
        assertEquals(1, SectionCoords.sectionOf(16));
        assertEquals(0, SectionCoords.localOf(16));
        assertEquals(2, SectionCoords.sectionOf(40));
        assertEquals(8, SectionCoords.localOf(40));
    }

    @Test
    void negativeCoordinatesRoundDownNotTowardsZero() {
        // -1 / 16 is 0 and -1 % 16 is -1 in Java. Both are wrong here.
        assertEquals(-1, SectionCoords.sectionOf(-1));
        assertEquals(15, SectionCoords.localOf(-1));
        assertEquals(-1, SectionCoords.sectionOf(-16));
        assertEquals(0, SectionCoords.localOf(-16));
        assertEquals(-2, SectionCoords.sectionOf(-17));
        assertEquals(15, SectionCoords.localOf(-17));
        assertEquals(-3, SectionCoords.sectionOf(-40));
        assertEquals(8, SectionCoords.localOf(-40));
    }

    @Test
    void localCoordinatesAreAlwaysInRange() {
        for (int coord = -512; coord < 512; coord++) {
            int local = SectionCoords.localOf(coord);
            assertTrue(SectionCoords.isValidLocal(local), "local " + local + " for coord " + coord);
        }
    }

    @Test
    void sectionAndLocalRecomposeTheOriginalCoordinate() {
        for (int coord = -512; coord < 512; coord++) {
            assertEquals(coord,
                    SectionCoords.toWorld(SectionCoords.sectionOf(coord), SectionCoords.localOf(coord)),
                    "coord " + coord);
        }
    }

    @Test
    void originsAreMultiplesOfSixteen() {
        for (int section = -32; section < 32; section++) {
            assertEquals(0, SectionCoords.originOf(section) & SectionCoords.MASK);
            assertEquals(section, SectionCoords.sectionOf(SectionCoords.originOf(section)));
        }
        assertEquals(-16, SectionCoords.originOf(-1));
        assertEquals(-32, SectionCoords.originOf(-2));
        assertEquals(0, SectionCoords.originOf(0));
        assertEquals(16, SectionCoords.originOf(1));
    }

    @Test
    void sectionCountSpansTheHalfOpenRange() {
        assertEquals(1, SectionCoords.sectionCount(0, 16));
        assertEquals(2, SectionCoords.sectionCount(-16, 16));
        assertEquals(1, SectionCoords.sectionCount(0, 1));
        assertEquals(2, SectionCoords.sectionCount(15, 17));
        assertEquals(4, SectionCoords.sectionCount(-17, 17));
        assertEquals(2, SectionCoords.sectionCount(-33, -16));
    }

    @Test
    void anEmptyRangeSpansNoSections() {
        assertEquals(0, SectionCoords.sectionCount(0, 0));
        assertEquals(0, SectionCoords.sectionCount(-1, -1));
        assertEquals(0, SectionCoords.sectionCount(10, 5), "inverted range is empty, not negative");
    }

    @Test
    void isValidLocalRejectsOutOfRangeOffsets() {
        assertTrue(SectionCoords.isValidLocal(0));
        assertTrue(SectionCoords.isValidLocal(15));
        assertFalse(SectionCoords.isValidLocal(-1));
        assertFalse(SectionCoords.isValidLocal(16));
    }

    @Test
    void neighbouringSectionsAreAdjacent() {
        // The property a section border query relies on: the block below a section's origin is the
        // top block of the section below it.
        for (int section = -8; section < 8; section++) {
            int origin = SectionCoords.originOf(section);
            assertEquals(section - 1, SectionCoords.sectionOf(origin - 1));
            assertEquals(15, SectionCoords.localOf(origin - 1));
            assertEquals(section, SectionCoords.sectionOf(origin));
            assertEquals(0, SectionCoords.localOf(origin));
        }
    }
}
