package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cell arithmetic is the foundation of the scalable batching path: if a cell boundary is in
 * the wrong place, entities near it are grouped with the wrong neighbours, and the symptom is
 * geometry drawn from the wrong batch bounds rather than anything that looks like a maths error.
 *
 * <p>Every expectation below was produced by a separate implementation and diffed before
 * committing.
 */
class EntityCellTest {

    @Test
    void cellOfRoundsDownIncludingForNegatives() {
        // Truncation toward zero is the bug this avoids: -1 / 16 is 0, so block -1 would land in
        // cell 0 at local position -1. That is precisely a chunk-straddling entity.
        assertEquals(-1, EntityCell.cellOf(-0.1f));
        assertEquals(-1, EntityCell.cellOf(-1f));
        assertEquals(-1, EntityCell.cellOf(-15.9f));
        assertEquals(-1, EntityCell.cellOf(-16f));
        assertEquals(-2, EntityCell.cellOf(-16.1f));
        assertEquals(-2, EntityCell.cellOf(-17f));
        assertEquals(0, EntityCell.cellOf(0f));
        assertEquals(0, EntityCell.cellOf(0.5f));
        assertEquals(0, EntityCell.cellOf(15.9f));
        assertEquals(1, EntityCell.cellOf(16f));
        assertEquals(1, EntityCell.cellOf(16.1f));
    }

    @Test
    void localOfStaysInRangeOnBothSidesOfZero() {
        assertEquals(15, EntityCell.localOf(-0.1f));
        assertEquals(15, EntityCell.localOf(-1f));
        assertEquals(0, EntityCell.localOf(-15.9f));
        assertEquals(0, EntityCell.localOf(-16f));
        assertEquals(0, EntityCell.localOf(0f));
        assertEquals(15, EntityCell.localOf(15.9f));
        assertEquals(0, EntityCell.localOf(16f));
    }

    @Test
    void theCellOriginIsNeverAboveThePosition() {
        // The invariant that makes the cell a valid lower bound. If this ever fails, a batch's
        // bounds would not enclose its instances and the frustum test would cull visible entities.
        for (float v = -40f; v <= 40f; v += 0.37f) {
            int origin = EntityCell.originOf(EntityCell.cellOf(v));
            assertTrue(origin <= v, "origin " + origin + " is above " + v);
            assertTrue(origin + EntityCell.SIZE > v,
                    "position " + v + " escapes its cell, which starts at " + origin);
        }
    }

    @Test
    void theCellSizeIsTheDocumentedOne() {
        assertEquals(16, EntityCell.SIZE);
        assertEquals(4, EntityCell.SHIFT);
        assertEquals(1 << EntityCell.SHIFT, EntityCell.SIZE, "size must stay a power of two");
    }

    @Test
    void keysRoundTripAtTheExtremesAndAroundZero() {
        int[][] cases = {
                {EntityCell.MIN_CELL, EntityCell.MIN_CELL, EntityCell.MIN_CELL},
                {EntityCell.MAX_CELL, EntityCell.MAX_CELL, EntityCell.MAX_CELL},
                {-1, -1, -1},
                {0, 0, 0},
                {1, 0, -1},
                {-2, 3, -4},
                {EntityCell.MAX_CELL, EntityCell.MIN_CELL, 0},
        };
        for (int[] c : cases) {
            long key = EntityCell.key(c[0], c[1], c[2]);
            assertEquals(c[0], EntityCell.cellX(key), "x of " + c[0]);
            assertEquals(c[1], EntityCell.cellY(key), "y of " + c[1]);
            assertEquals(c[2], EntityCell.cellZ(key), "z of " + c[2]);
            // EMPTY is the hash table's "free slot" marker, so no real key may equal it.
            assertTrue(key != EntityCell.EMPTY, "cell " + c[0] + "," + c[1] + "," + c[2]
                    + " packs to the empty marker");
        }
    }

    @Test
    void theMinimumCellPacksToZeroAndTheMaximumToTheLargestPositiveLong() {
        // Both facts matter: zero is a plausible-looking key, so a hash table that used 0 as its
        // free marker would lose the minimum cell, and the maximum must not overflow into the sign
        // bit or unsigned comparisons on the key would be wrong.
        assertEquals(0L, EntityCell.key(EntityCell.MIN_CELL, EntityCell.MIN_CELL,
                EntityCell.MIN_CELL));
        assertEquals(Long.MAX_VALUE, EntityCell.key(EntityCell.MAX_CELL, EntityCell.MAX_CELL,
                EntityCell.MAX_CELL));
        assertTrue(Long.MIN_VALUE == EntityCell.EMPTY);
    }

    @Test
    void distinctCellsNeverShareAKey() {
        Set<Long> seen = new HashSet<>();
        for (int cx = -40; cx < 40; cx += 7) {
            for (int cy = -5; cy < 6; cy += 3) {
                for (int cz = -40; cz < 40; cz += 5) {
                    assertTrue(seen.add(EntityCell.key(cx, cy, cz)),
                            "cells collide at " + cx + "," + cy + "," + cz);
                }
            }
        }
        assertEquals(768, seen.size());
    }

    @Test
    void cellsOutsideTheRepresentableRangeAreRejected() {
        // Wrapping would alias two distant cells onto one batch, which draws entities in the wrong
        // place rather than merely slowly.
        int tooFar = EntityCell.MIN_CELL - 1;
        int tooHigh = EntityCell.MAX_CELL + 1;
        assertThrows(IllegalArgumentException.class, () -> EntityCell.key(tooFar, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> EntityCell.key(0, tooHigh, 0));
        assertThrows(IllegalArgumentException.class, () -> EntityCell.key(0, 0, tooFar));
        assertEquals(-1048576, EntityCell.MIN_CELL);
        assertEquals(1048575, EntityCell.MAX_CELL);
    }

    @Test
    void keyOfAgreesWithComputingTheCellFirst() {
        float x = -33.4f;
        float y = 71.9f;
        float z = 128.05f;
        assertEquals(EntityCell.key(EntityCell.cellOf(x), EntityCell.cellOf(y),
                EntityCell.cellOf(z)), EntityCell.keyOf(x, y, z));
    }
}
