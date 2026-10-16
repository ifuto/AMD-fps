package net.amdfaster.render;

import net.amdfaster.render.RenderElementToggle.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderElementToggleTest {

    @Test
    void everythingIsOnByDefault() {
        // A performance mod that arrives with things already switched off has made a visual decision for
        // the player without asking, and the player experiences it as the mod breaking their game.
        RenderElementToggle toggle = new RenderElementToggle();

        assertEquals(Element.values().length, toggle.enabledCount());
        for (Element element : Element.values()) {
            assertTrue(toggle.isEnabled(element), element.name() + " starts enabled");
        }
        assertEquals(0.0, toggle.savedPercent(), 1e-9, "an unconfigured toggle has saved nothing");
    }

    @Test
    void turningAnElementOffStopsItsWork() {
        // Disabling clouds that still simulated their movement would save the fill rate and leave the CPU
        // doing everything else, which on a CPU-bound machine is most of the cost.
        RenderElementToggle toggle = new RenderElementToggle();

        toggle.set(Element.CLOUDS, false);

        assertFalse(toggle.shouldProcess(Element.CLOUDS), "not updated, not meshed, not drawn");
        assertTrue(toggle.shouldProcess(Element.PARTICLES), "and nothing else is affected");
    }

    @Test
    void aChangeAppliesOnTheFrameItIsMade() {
        // Deferring it to the next launch would make the setting look like it had not taken, and the player
        // would toggle it again and conclude the mod was broken.
        RenderElementToggle toggle = new RenderElementToggle();

        toggle.set(Element.WEATHER, false);
        assertFalse(toggle.isEnabled(Element.WEATHER));

        toggle.set(Element.WEATHER, true);
        assertTrue(toggle.isEnabled(Element.WEATHER));
        assertEquals(2L, toggle.changes());
    }

    @Test
    void settingTheSameValueTwiceIsNotAChange() {
        RenderElementToggle toggle = new RenderElementToggle();

        toggle.set(Element.CLOUDS, true);

        assertEquals(0L, toggle.changes(), "it was already on");
    }

    @Test
    void eachElementReportsItsOwnCost() {
        // The elements cost wildly different amounts: particles during heavy rain are expensive, clouds are
        // not. A single percentage would describe turning off clouds and turning off particles as the same
        // thing, and a player choosing what to disable would be choosing blind.
        assertEquals(1.2, Element.PARTICLES.msPerFrame(), 1e-12);
        assertEquals(0.2, Element.CLOUDS.msPerFrame(), 1e-12);
        assertTrue(Element.PARTICLES.msPerFrame() > Element.CLOUDS.msPerFrame());
    }

    @Test
    void turningOffParticlesSavesMoreThanTurningOffClouds() {
        RenderElementToggle particles = new RenderElementToggle();
        RenderElementToggle clouds = new RenderElementToggle();

        particles.set(Element.PARTICLES, false);
        clouds.set(Element.CLOUDS, false);

        assertEquals(3.1, particles.msWithEverything(), 1e-9);
        assertEquals(1.2 / 3.1 * 100, particles.savedPercent(), 1e-9);
        assertEquals(0.2 / 3.1 * 100, clouds.savedPercent(), 1e-9);
        assertTrue(particles.savedPercent() > clouds.savedPercent() * 5);
    }

    @Test
    void turningEverythingOffRemovesAllOfIt() {
        RenderElementToggle toggle = new RenderElementToggle();

        for (Element element : Element.values()) {
            toggle.set(element, false);
        }

        assertEquals(0, toggle.enabledCount());
        assertEquals(0.0, toggle.msPerFrame(), 1e-12);
        assertEquals(100.0, toggle.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        RenderElementToggle toggle = new RenderElementToggle();

        assertThrows(IllegalArgumentException.class, () -> toggle.set(null, false));
        assertThrows(IllegalArgumentException.class, () -> toggle.isEnabled(null));
    }
}
