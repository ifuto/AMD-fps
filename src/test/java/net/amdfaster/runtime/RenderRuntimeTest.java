package net.amdfaster.runtime;

import net.amdfaster.render.GuiBatcher;
import net.amdfaster.render.RenderElementToggle;
import net.amdfaster.render.TextVertexBuffer;
import net.amdfaster.runtime.RenderRuntime.Frame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderRuntimeTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private static Frame frame(RenderRuntime runtime, long worldTime, boolean raining) {
        return runtime.frame(false, false, W, H, worldTime, raining);
    }

    @Test
    void theFirstFrameRebuildsTheHudAndTheRestBlits() {
        RenderRuntime runtime = new RenderRuntime();

        Frame first = frame(runtime, 0, false);
        assertTrue(first.rebuildHud(), "nothing cached yet");

        Frame second = frame(runtime, 1, false);
        assertTrue(second.blitHud(), "and the next frame blits it");
        assertEquals(2L, runtime.frames(), "two frames were drawn");
        assertEquals(1L, runtime.hudTexture().blitted());
        assertEquals(1L, runtime.hudTexture().rebuilt());
    }

    @Test
    void aPlayerActionForcesAnHudRebuild() {
        RenderRuntime runtime = new RenderRuntime();

        frame(runtime, 0, false);
        Frame afterAction = runtime.frame(true, false, W, H, 1, false);

        assertTrue(afterAction.rebuildHud());
        assertTrue(runtime.hudSchedule().forced() > 0);
    }

    @Test
    void turningCloudsOffSkipsTheCloudMesh() {
        // An element the player turned off should not cost an update check.
        RenderRuntime runtime = new RenderRuntime();

        frame(runtime, 0, false);
        runtime.toggles().set(RenderElementToggle.Element.CLOUDS, false);
        Frame after = frame(runtime, 100_000, false);

        assertFalse(after.clouds());
        assertFalse(after.rebuildClouds());
    }

    @Test
    void theWeatherMeshIsOnlyRebuiltWhenItIsRaining() {
        RenderRuntime runtime = new RenderRuntime();

        assertFalse(frame(runtime, 0, false).weather(), "not raining");
        assertTrue(frame(runtime, 1, true).weather(), "and raining");
    }

    @Test
    void theCloudShapeChangesWithTheWorldTime() {
        RenderRuntime runtime = new RenderRuntime();

        frame(runtime, 0, false);
        Frame later = frame(runtime, 100_000, false);

        assertTrue(later.rebuildClouds(), "the shape coordinate moved");
    }

    @Test
    void textBuffersAreKeyedPerText() {
        // A hash collision would show the wrong words, which is worse than rebuilding -- a sign reading
        // "Chest of diamonds" as "Chest of diamond!" is a bug the player can read.
        RenderRuntime runtime = new RenderRuntime();

        TextVertexBuffer diamonds = runtime.textBuffer("Chest of diamonds");
        TextVertexBuffer diamond = runtime.textBuffer("Chest of diamond!");

        assertNotSame(diamonds, diamond);
        assertSame(diamonds, runtime.textBuffer("Chest of diamonds"));
        assertEquals(2, runtime.textBuffers());
    }

    @Test
    void aTextBufferRebuildsOnlyWhenTheTextChanges() {
        RenderRuntime runtime = new RenderRuntime();
        TextVertexBuffer buffer = runtime.textBuffer("sign");

        assertTrue(buffer.needsRebuild("Hello"));
        buffer.store(new byte[80], 5);

        assertFalse(buffer.needsRebuild("Hello"), "the same text");
        assertTrue(buffer.needsRebuild("Hello!"), "and a different one");
    }

    @Test
    void guiElementsAreBatchedInOrder() {
        // Reordering GUI elements changes what is drawn over what.
        RenderRuntime runtime = new RenderRuntime();

        List<GuiBatcher.Element> elements = List.of(
                new GuiBatcher.Element(0, 0, 0, 0, 16, 16, 0),
                new GuiBatcher.Element(0, 0, 20, 0, 16, 16, 0),
                new GuiBatcher.Element(1, 0, 40, 0, 16, 16, 0));

        List<GuiBatcher.Batch> batches = runtime.batchGui(elements);

        assertEquals(2, batches.size(), "two atlases, so two batches");
        assertTrue(GuiBatcher.coversAll(batches, elements.size()));
        assertTrue(GuiBatcher.preservesOrder(batches.get(0)));
    }

    @Test
    void inventoryIconsAreCachedPerItem() {
        RenderRuntime runtime = new RenderRuntime();

        assertTrue(runtime.icons().needsRebuild("minecraft:diamond_sword", 0, false));
        runtime.icons().store("minecraft:diamond_sword", 0, false);

        assertFalse(runtime.icons().needsRebuild("minecraft:diamond_sword", 0, false));
        assertTrue(runtime.icons().needsRebuild("minecraft:diamond_sword", 5, false),
                "damage changes the icon");
    }

    @Test
    void invalidateAllClearsEverythingTogether() {
        // Missing one means the player sees a stale HUD over a fresh world, or yesterday's clouds over
        // today's weather.
        RenderRuntime runtime = new RenderRuntime();

        frame(runtime, 0, true);
        runtime.textBuffer("sign");
        runtime.icons().store("minecraft:apple", 0, false);

        runtime.invalidateAll();

        assertFalse(runtime.hudTexture().isValid());
        assertFalse(runtime.clouds().hasCache());
        assertEquals(0, runtime.textBuffers());
        assertTrue(runtime.icons().needsRebuild("minecraft:apple", 0, false));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        RenderRuntime runtime = new RenderRuntime();

        assertThrows(IllegalArgumentException.class, () -> runtime.textBuffer(null));
        assertThrows(IllegalArgumentException.class, () -> runtime.textBuffer(""));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has drawn nothing has technically reused everything, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        RenderRuntime runtime = new RenderRuntime();

        assertEquals(0.0, runtime.hudBlitPercent());
        assertEquals(0.0, runtime.msSavedOnHud(), 1e-12);
        assertEquals(0.0, runtime.msSavedOnMeshes(), 1e-12);
        assertEquals(0L, runtime.frames());
        assertEquals(0, runtime.textBuffers());
        assertEquals(RenderElementToggle.Element.values().length, runtime.toggles().enabledCount(),
                "everything is on until a player turns it off");
    }
}
