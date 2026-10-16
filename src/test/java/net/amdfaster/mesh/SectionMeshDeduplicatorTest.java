package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SectionMeshDeduplicatorTest {

    private static final AtomicInteger BUILDS = new AtomicInteger();

    private static byte[] section(int filler) {
        byte[] content = new byte[SectionMeshDeduplicator.SECTION_BLOCKS];
        java.util.Arrays.fill(content, (byte) filler);
        return content;
    }

    private static final SectionMeshDeduplicator.Builder COUNTING = content -> {
        BUILDS.incrementAndGet();
        return new byte[SectionMeshDeduplicator.MESH_BYTES];
    };

    @Test
    void identicalSectionsShareOneMesh() {
        // Two hundred of the four hundred sections within render distance are pure air and another
        // hundred and twenty are solid stone. Building a mesh per section means building the same one
        // hundreds of times.
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();
        BUILDS.set(0);

        byte[] first = dedup.mesh(section(1), COUNTING);
        for (int i = 0; i < 319; i++) {
            assertSame(first, dedup.mesh(section(1), COUNTING), "section " + i);
        }

        assertEquals(1, BUILDS.get(), "built once");
        assertEquals(319L, dedup.shared());
        assertEquals(1, dedup.size());
    }

    @Test
    void sectionsDifferingInOneBlockGetDifferentMeshes() {
        // Merging them gives one section the other's geometry, and the symptom is terrain missing or
        // wrong in a way that is immediately visible -- a hole in a hillside, a floor that is not there.
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();
        BUILDS.set(0);

        byte[] a = section(1);
        byte[] b = section(1);
        b[2000] = 2;

        byte[] meshA = dedup.mesh(a, COUNTING);
        byte[] meshB = dedup.mesh(b, COUNTING);

        assertNotSame(meshA, meshB);
        assertEquals(2, BUILDS.get());
        assertEquals(2, dedup.size());
    }

    @Test
    void sectionsOfDifferentLengthsAreDifferent() {
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();

        dedup.mesh(new byte[] {1, 2, 3}, COUNTING);
        dedup.mesh(new byte[] {1, 2, 3, 4}, COUNTING);

        assertEquals(2, dedup.size());
    }

    @Test
    void aHashCollisionWouldStillBeCompared() {
        // The hash only nominates a candidate. Accepting a hash match alone would eventually give one
        // section another's geometry, which is reported as a broken world rather than a broken mod.
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();
        BUILDS.set(0);

        // Many distinct sections; if any two collided on the sampled hash, the comparison has to catch it.
        for (int i = 0; i < 500; i++) {
            dedup.mesh(section(i % 251), COUNTING);
        }

        assertEquals(251, dedup.size(), "one mesh per distinct content");
        assertEquals(251, BUILDS.get());
    }

    @Test
    void theStoredContentIsCopied() {
        // A caller reusing its buffer would otherwise change the mesh every section shares.
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();
        byte[] content = section(1);
        byte[] first = dedup.mesh(content, COUNTING);

        content[0] = 99;
        byte[] second = dedup.mesh(section(1), COUNTING);

        assertSame(first, second, "the mutation did not change what was stored");
    }

    @Test
    void invalidatingForcesARebuild() {
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();
        BUILDS.set(0);
        byte[] content = section(5);
        byte[] first = dedup.mesh(content, COUNTING);

        dedup.invalidate(content);

        assertEquals(0, dedup.size());
        byte[] second = dedup.mesh(content, COUNTING);
        assertNotSame(first, second);
        assertEquals(2, BUILDS.get());
    }

    @Test
    void meshesSurviveGrowth() {
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator(16);
        BUILDS.set(0);

        for (int i = 0; i < 500; i++) {
            dedup.mesh(section(i), COUNTING);
        }
        assertEquals(500, dedup.size());

        int before = BUILDS.get();
        for (int i = 0; i < 500; i++) {
            dedup.mesh(section(i), COUNTING);
        }
        assertEquals(before, BUILDS.get(), "every one still shared after the table grew");
    }

    @Test
    void theSavingIsReportedInBytes() {
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();

        // The measured world: 400 sections, 53 distinct.
        for (int i = 0; i < 400; i++) {
            dedup.mesh(section(i < 320 ? 1 : 2 + (i - 320) % 52), COUNTING);
        }

        assertEquals(53, dedup.size());
        assertEquals(53L * SectionMeshDeduplicator.MESH_BYTES, dedup.bytes());
        assertEquals(400L * SectionMeshDeduplicator.MESH_BYTES, dedup.bytesWithoutSharing());
        assertEquals(347L * SectionMeshDeduplicator.MESH_BYTES, dedup.bytesSaved());
        assertEquals(1_850_204L, dedup.bytesSaved());
        assertEquals(86.75, dedup.sharedPercent(), 0.01);
    }

    @Test
    void theBuilderIsNeverCalledForASharedSection() {
        // Meshing a section costs more than uploading it, so the saving is the build, not the memory.
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();
        SectionMeshDeduplicator.Builder never = content -> {
            throw new AssertionError("should not have been called");
        };

        dedup.mesh(section(7), COUNTING);
        assertArrayEquals(new byte[SectionMeshDeduplicator.MESH_BYTES], dedup.mesh(section(7), never));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SectionMeshDeduplicator(8));
        assertThrows(IllegalArgumentException.class, () -> new SectionMeshDeduplicator(1000));

        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();
        assertThrows(IllegalArgumentException.class, () -> dedup.mesh(new byte[0], COUNTING));
        assertThrows(IllegalArgumentException.class, () -> dedup.mesh(section(1), content -> null));
    }

    @Test
    void aFreshDeduplicatorReportsZeroNotNaN() {
        SectionMeshDeduplicator dedup = new SectionMeshDeduplicator();

        assertEquals(0.0, dedup.sharedPercent());
        assertEquals(0, dedup.size());
        assertEquals(0L, dedup.bytesSaved());
        assertEquals(5332, SectionMeshDeduplicator.MESH_BYTES);
        assertEquals(4096, SectionMeshDeduplicator.SECTION_BLOCKS);
    }
}
