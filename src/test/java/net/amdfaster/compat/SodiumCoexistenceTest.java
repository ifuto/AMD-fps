package net.amdfaster.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.compat.SodiumCoexistence.Detection;
import net.amdfaster.compat.SodiumCoexistence.Subsystem;
import org.junit.jupiter.api.Test;

/**
 * Every number here was printed by a model of the same logic before the test was written, rather than
 * typed from what the assertion seemed to want.
 */
class SodiumCoexistenceTest {

    @Test
    void withoutSodiumEverythingStaysOn() {
        Detection absent = SodiumCoexistence.detect(false, false, null);

        assertFalse(absent.sodiumActive(), "nothing to stand aside for");
        assertEquals(11, SodiumCoexistence.subsystemsKept(absent), "all eleven subsystems");
        for (Subsystem subsystem : Subsystem.values()) {
            assertTrue(SodiumCoexistence.amdFasterOwns(absent, subsystem), subsystem.name());
        }
    }

    @Test
    void anActiveSodiumTakesOnlyTheThreeTerrainPaths() {
        Detection active = SodiumCoexistence.detect(true, false, "0.8.1+mc1.21.11");

        assertTrue(active.sodiumActive());
        // Three of the eleven are Sodium's: section meshing, section culling and build scheduling.
        assertEquals(8, SodiumCoexistence.subsystemsKept(active));

        assertFalse(SodiumCoexistence.amdFasterOwns(active, Subsystem.TERRAIN_MESHING));
        assertFalse(SodiumCoexistence.amdFasterOwns(active, Subsystem.TERRAIN_CULLING));
        assertFalse(SodiumCoexistence.amdFasterOwns(active, Subsystem.CHUNK_BUILD_SCHEDULING));

        // And the eight Sodium does not touch are not given up, which is the point of the add-on mode.
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.ENTITIES));
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.BLOCK_ENTITIES));
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.PARTICLES));
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.SOUND));
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.NETWORK));
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.MEMORY));
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.WORLDGEN));
        assertTrue(SodiumCoexistence.amdFasterOwns(active, Subsystem.LIGHT));
    }

    @Test
    void embeddiumSwitchesSodiumOffSoNothingIsYielded() {
        // Sodium's mixin plugin disables itself when Embeddium is present. Standing aside here would
        // leave the terrain path optimised by nobody: Sodium turned itself off and AMD-Faster walked away.
        Detection withEmbeddium = SodiumCoexistence.detect(true, true, "0.8.1+mc1.21.11");

        assertTrue(withEmbeddium.sodiumPresent(), "the jar is loaded");
        assertFalse(withEmbeddium.sodiumActive(), "but its mixins are off");
        assertEquals(11, SodiumCoexistence.subsystemsKept(withEmbeddium),
                "so AMD-Faster keeps everything");
        assertTrue(SodiumCoexistence.amdFasterOwns(withEmbeddium, Subsystem.TERRAIN_MESHING),
                "the vanilla terrain path is live again");
    }

    @Test
    void embeddiumAloneChangesNothing() {
        Detection embeddiumOnly = SodiumCoexistence.detect(false, true, null);

        assertFalse(embeddiumOnly.sodiumActive());
        assertEquals(11, SodiumCoexistence.subsystemsKept(embeddiumOnly));
    }

    @Test
    void versionStringsAreParsedFromTheirLeadingNumberOnly() {
        // The +mc suffix has to be dropped, not parsed: reading it as a minor would report 21 and reject
        // a version that is in fact the one this build targets.
        assertArrayEquals(new int[] {0, 8}, SodiumCoexistence.parseVersion("0.8.1+mc1.21.11"));
        assertArrayEquals(new int[] {0, 8}, SodiumCoexistence.parseVersion("0.8.12"));
        assertArrayEquals(new int[] {0, 6}, SodiumCoexistence.parseVersion("0.6.0-beta.1"));
        assertArrayEquals(new int[] {0, 9}, SodiumCoexistence.parseVersion("0.9.0"));
        assertArrayEquals(new int[] {1, 0}, SodiumCoexistence.parseVersion("1.0.0"));
        // A bare "0.8" still carries a minor.
        assertArrayEquals(new int[] {0, 8}, SodiumCoexistence.parseVersion("0.8"));
    }

    @Test
    void unparseableVersionsReportUnknownRatherThanZero() {
        // Zero would read as a real major and be compared against the validated one.
        assertArrayEquals(new int[] {-1, -1}, SodiumCoexistence.parseVersion(null));
        assertArrayEquals(new int[] {-1, -1}, SodiumCoexistence.parseVersion("abc"));
        // A leading dot gives no major, and a trailing dot gives no minor; split drops the empty tail.
        assertArrayEquals(new int[] {-1, 8}, SodiumCoexistence.parseVersion(".8"));
        assertArrayEquals(new int[] {0, -1}, SodiumCoexistence.parseVersion("0."));
    }

    @Test
    void onlyAnActiveUnvalidatedVersionWarns() {
        assertFalse(SodiumCoexistence.shouldWarn(SodiumCoexistence.detect(false, false, null)),
                "Sodium absent is not a problem");
        assertFalse(SodiumCoexistence.shouldWarn(SodiumCoexistence.detect(true, false, "0.8.1+mc1.21.11")),
                "a validated version needs no warning");
        assertFalse(SodiumCoexistence.shouldWarn(SodiumCoexistence.detect(true, true, "0.9.0")),
                "an inactive Sodium cannot conflict");
        assertFalse(SodiumCoexistence.shouldWarn(SodiumCoexistence.detect(true, false, null)),
                "an unknown version is not claimed to be wrong");

        assertTrue(SodiumCoexistence.shouldWarn(SodiumCoexistence.detect(true, false, "0.9.0")),
                "active on a minor nobody validated");
        assertTrue(SodiumCoexistence.shouldWarn(SodiumCoexistence.detect(true, false, "0.6.0-beta.1")));
        assertTrue(SodiumCoexistence.shouldWarn(SodiumCoexistence.detect(true, false, "1.0.0")));
    }

    @Test
    void theDescriptionSaysWhyItStoodAside() {
        assertEquals("Sodium absent; AMD-Faster owns all 11 subsystems",
                SodiumCoexistence.describe(SodiumCoexistence.detect(false, false, null)));

        assertEquals("Sodium present but Embeddium disables its mixins; vanilla terrain path is live, "
                        + "AMD-Faster owns all 11 subsystems",
                SodiumCoexistence.describe(SodiumCoexistence.detect(true, true, "0.8.1")));

        assertEquals("Sodium 0.8 is rendering terrain; AMD-Faster keeps 8 of 11 subsystems",
                SodiumCoexistence.describe(SodiumCoexistence.detect(true, false, "0.8.1+mc1.21.11")));

        assertEquals("Sodium 0.9 is rendering terrain; AMD-Faster keeps 8 of 11 subsystems "
                        + "(unvalidated version)",
                SodiumCoexistence.describe(SodiumCoexistence.detect(true, false, "0.9.0")));

        assertEquals("Sodium unknown is rendering terrain; AMD-Faster keeps 8 of 11 subsystems",
                SodiumCoexistence.describe(SodiumCoexistence.detect(true, false, null)));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        Detection absent = SodiumCoexistence.detect(false, false, null);

        assertThrows(IllegalArgumentException.class,
                () -> SodiumCoexistence.amdFasterOwns(null, Subsystem.SOUND), "a detection is required");
        assertThrows(IllegalArgumentException.class,
                () -> SodiumCoexistence.amdFasterOwns(absent, null), "a subsystem is required");
    }

    @Test
    void everySubsystemDeclaresWhetherSodiumReplacesIt() {
        // Guard against a new subsystem being added without deciding, which would silently default to
        // "AMD-Faster keeps it" and double up with Sodium on a path nobody considered.
        assertEquals(11, Subsystem.values().length, "the count the descriptions quote");

        int replaced = 0;
        for (Subsystem subsystem : Subsystem.values()) {
            if (subsystem.sodiumReplaces()) {
                replaced++;
            }
        }
        assertEquals(3, replaced, "meshing, culling and build scheduling");
    }
}
