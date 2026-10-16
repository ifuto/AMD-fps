package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticGeometryBakerTest {

    private static final StaticGeometryBaker.Part BODY =
            new StaticGeometryBaker.Part("body", StaticGeometryBaker.CHEST_BODY_QUADS, false);
    private static final StaticGeometryBaker.Part LID =
            new StaticGeometryBaker.Part("lid", StaticGeometryBaker.CHEST_LID_QUADS, true);

    @Test
    void theUnmovingPartsAreBaked() {
        // A chest that has sat unopened for an hour is rebuilt sixty times a second. Baking the body makes
        // it an ordinary block: one draw call for the whole section, no per-frame assembly.
        StaticGeometryBaker baker = new StaticGeometryBaker();

        int quads = baker.bake("minecraft:chest", List.of(BODY), List.of(LID));

        assertEquals(22, quads);
        assertTrue(baker.isBaked("minecraft:chest"));
        assertEquals(1L, baker.drawCallsRemoved());
    }

    @Test
    void theMovingPartsStayWithTheRenderer() {
        // A chest lid opens. Baking it would freeze it in one position, and a chest that cannot be seen to
        // open tells the player nothing about whether the click registered -- worse than the frame time
        // saved.
        StaticGeometryBaker baker = new StaticGeometryBaker();

        int quads = baker.bake("minecraft:chest", List.of(BODY), List.of(LID));

        assertEquals(22, quads, "the body only; the six lid quads are not baked");
        assertEquals(22L, baker.quadsBaked());
    }

    @Test
    void aFullyAnimatedEntityKeepsItsRenderer() {
        // A beacon beam has nothing static to bake. Baking would produce a static object where the game
        // intends a moving one.
        StaticGeometryBaker baker = new StaticGeometryBaker();

        int quads = baker.bake("minecraft:beacon", List.of(), List.of(LID));

        assertEquals(0, quads);
        assertTrue(baker.keptRenderer("minecraft:beacon"));
        assertFalse(baker.isBaked("minecraft:beacon"));
        assertEquals(0L, baker.drawCallsRemoved());
    }

    @Test
    void bakedGeometryAndDrawCallsAreCounted() {
        StaticGeometryBaker baker = new StaticGeometryBaker();

        baker.bake("minecraft:chest", List.of(BODY), List.of(LID));
        baker.bake("minecraft:bed", List.of(BODY), List.of());

        assertEquals(44L, baker.quadsBaked());
        assertEquals(44L * StaticGeometryBaker.BYTES_PER_QUAD, baker.bytesBaked());
        assertEquals(2L, baker.drawCallsRemoved());
        assertEquals(2 * StaticGeometryBaker.MS_PER_ENTITY, baker.msSavedPerFrame(), 1e-9);
    }

    @Test
    void aKeptRendererStillCostsItsAssembly() {
        // The saving is against assembling every block entity separately, including the ones that could not
        // be baked.
        StaticGeometryBaker baker = new StaticGeometryBaker();

        baker.bake("minecraft:chest", List.of(BODY), List.of(LID));
        baker.bake("minecraft:beacon", List.of(), List.of(LID));

        assertEquals(3 * StaticGeometryBaker.MS_PER_ENTITY, baker.msWithoutBaking(), 1e-9);
        assertEquals(2 * StaticGeometryBaker.MS_PER_ENTITY, baker.msSavedPerFrame(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        StaticGeometryBaker baker = new StaticGeometryBaker();

        assertThrows(IllegalArgumentException.class,
                () -> baker.bake(null, List.of(BODY), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> baker.bake("", List.of(BODY), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> baker.bake("minecraft:chest", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StaticGeometryBaker.Part("", 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new StaticGeometryBaker.Part("body", -1, false));
    }

    @Test
    void aFreshBakerReportsZeroNotNaN() {
        // A baker that has done nothing has technically avoided all of it, but reporting a saving for an
        // unused one reads as a result when it is the absence of one.
        StaticGeometryBaker baker = new StaticGeometryBaker();

        assertEquals(0.0, baker.savedPercent());
        assertEquals(0.0, baker.msSavedPerFrame());
        assertEquals(0L, baker.quadsBaked());
        assertEquals(0L, baker.bytesBaked());
        assertEquals(0L, baker.drawCallsRemoved());
    }
}
