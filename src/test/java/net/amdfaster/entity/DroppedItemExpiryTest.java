package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroppedItemExpiryTest {

    @Test
    void anUnreachableItemExpiresEarly() {
        // Nobody can see it and nobody can reach it in the time it has left, so it is already lost -- it
        // would despawn before anyone got to it.
        DroppedItemExpiry expiry = new DroppedItemExpiry();

        assertFalse(expiry.shouldExpire(DroppedItemExpiry.UNREACHABLE_LIFETIME - 1, 200.0));
        assertTrue(expiry.shouldExpire(DroppedItemExpiry.UNREACHABLE_LIFETIME, 200.0));
        assertEquals(1L, expiry.expired());
    }

    @Test
    void anItemAPlayerCanReachSurvivesTheFullFiveMinutes() {
        // An item a player dropped and is walking back toward has to stay. Removing it would be taking the
        // player's things, which is not a performance adjustment -- it is the mod eating their inventory.
        DroppedItemExpiry expiry = new DroppedItemExpiry();

        assertFalse(expiry.shouldExpire(DroppedItemExpiry.UNREACHABLE_LIFETIME, 10.0),
                "past the short lifetime, but a player is standing on it");
        assertEquals(1L, expiry.keptReachable());

        assertTrue(expiry.shouldExpire(DroppedItemExpiry.VANILLA_LIFETIME, 10.0),
                "and it still despawns at five minutes like any other");
    }

    @Test
    void theEarlyExpiryIsMinutesNotSeconds() {
        // An item that vanishes in a second is indistinguishable from a bug, and a player who dies and
        // respawns would find nothing where their things were.
        assertEquals(1200, DroppedItemExpiry.UNREACHABLE_LIFETIME, "a minute of game time");
        assertTrue(DroppedItemExpiry.UNREACHABLE_LIFETIME > 20 * 30);
        assertEquals(6000, DroppedItemExpiry.VANILLA_LIFETIME);
    }

    @Test
    void theReachRangeIsTheBoundary() {
        DroppedItemExpiry expiry = new DroppedItemExpiry();

        expiry.shouldExpire(DroppedItemExpiry.UNREACHABLE_LIFETIME, DroppedItemExpiry.REACH_RANGE);
        assertEquals(1L, expiry.keptReachable(), "at the range is still reachable");

        expiry.shouldExpire(DroppedItemExpiry.UNREACHABLE_LIFETIME, DroppedItemExpiry.REACH_RANGE + 1);
        assertEquals(1L, expiry.expired(), "and past it is not");
    }

    @Test
    void aFarmPileIsMostlyUnreachable() {
        DroppedItemExpiry expiry = new DroppedItemExpiry();

        for (int i = 0; i < 2000; i++) {
            expiry.shouldExpire(2000, 300.0);
        }
        for (int i = 0; i < 50; i++) {
            expiry.shouldExpire(2000, 20.0);
        }

        assertEquals(2050L, expiry.checked());
        assertEquals(2000L, expiry.expired());
        assertEquals(97.56, expiry.expiredPercent(), 0.01);
        assertEquals(8.0, expiry.msSavedPerTick(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        DroppedItemExpiry expiry = new DroppedItemExpiry();

        assertThrows(IllegalArgumentException.class, () -> expiry.shouldExpire(-1, 10.0));
        assertThrows(IllegalArgumentException.class, () -> expiry.shouldExpire(10, -1.0));
    }

    @Test
    void aFreshPolicyReportsZeroNotNaN() {
        // An expiry policy that has seen nothing has technically removed everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        DroppedItemExpiry expiry = new DroppedItemExpiry();

        assertEquals(0.0, expiry.expiredPercent());
        assertEquals(0.0, expiry.savedPercent());
        assertEquals(0.0, expiry.msSavedPerTick(), 1e-12);
        assertEquals(0L, expiry.checked());
        assertEquals(0L, expiry.keptReachable());
        assertEquals(64.0, DroppedItemExpiry.REACH_RANGE, 1e-12);
    }
}
