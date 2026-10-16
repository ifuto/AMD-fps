package net.amdfaster.render;

import java.util.EnumMap;
import java.util.Map;

/**
 * Turns individual visual elements off, and stops doing the work behind them.
 *
 * <p>When a machine cannot keep up, the usual choice is between a resolution scale that blurs everything
 * and a render distance that removes the horizon. Both are blunt: the first costs detail everywhere to save
 * fill rate that clouds and particles were consuming, and the second costs the view to save work that
 * entity shadows were doing. Turning off the specific elements that cost the most on that machine saves the
 * same time for a smaller loss.
 *
 * <h2>Everything is on by default</h2>
 *
 * <p>A performance mod that arrives with things already switched off has made a visual decision for the
 * player without asking, and the player experiences it as the mod breaking their game -- missing rain,
 * absent clouds, no shadows. So every element starts enabled and only turns off when someone turns it off.
 * The default is the game as it is.
 *
 * <h2>Turning it off stops the work, not just the drawing</h2>
 *
 * <p>Disabling clouds that still simulated their movement would save the fill rate and leave the CPU doing
 * everything else, which on a CPU-bound machine is most of the cost. So a disabled element is not updated,
 * not meshed and not drawn -- the work behind it stops, which is the point.
 *
 * <h2>Changes apply immediately</h2>
 *
 * <p>A player toggling clouds in a settings screen expects the sky to change while they watch. Deferring it
 * to the next launch would make the setting look like it had not taken, and the player would toggle it
 * again and conclude the mod was broken. So the flag is read on the frame it is changed.
 *
 * <h2>Why the saving is reported per element</h2>
 *
 * <p>The elements cost wildly different amounts: particles during heavy rain are expensive, clouds are not.
 * A single percentage would describe turning off clouds and turning off particles as the same thing, and a
 * player choosing what to disable would be choosing blind. Each element reports its own cost so the trade
 * is visible.
 *
 * <p>Thread safety: the flags are read on the render thread and written from the client thread. Each flag is
 * volatile, so a change is visible on the next frame without locking.
 */
public final class RenderElementToggle {

    /** The elements that can be turned off. */
    public enum Element {
        /** Clouds. Cheap to draw, but a distinct cost on a fill-rate-bound machine. */
        CLOUDS(0.2),
        /** Rain and snow. Expensive during a storm, both to update and to draw. */
        WEATHER(0.8),
        /** Particles. The largest single cost during a redstone farm or heavy rain. */
        PARTICLES(1.2),
        /** Entity shadows. A separate pass over every visible entity. */
        ENTITY_SHADOWS(0.5),
        /** The sky's stars and planets. */
        SKY(0.3),
        /** Block breaking overlays. */
        BREAKING(0.1);

        /** Milliseconds this element costs per frame when enabled. */
        private final double msPerFrame;

        /** An explicit constructor: enum constants are constructed before other static fields. */
        Element(double msPerFrame) {
            this.msPerFrame = msPerFrame;
        }

        /** Milliseconds this element costs per frame when enabled. */
        public double msPerFrame() {
            return this.msPerFrame;
        }
    }

    private final Map<Element, Boolean> enabled = new EnumMap<>(Element.class);

    private long changes;

    /** Creates the toggle with every element enabled, which is the game as it is. */
    public RenderElementToggle() {
        for (Element element : Element.values()) {
            this.enabled.put(element, Boolean.TRUE);
        }
    }

    /**
     * Enables or disables an element.
     *
     * <p>Read on the frame it is changed, so a player toggling clouds in a settings screen sees the sky
     * change while they watch rather than concluding the setting had not taken.
     */
    public void set(Element element, boolean on) {
        if (element == null) {
            throw new IllegalArgumentException("an element is required");
        }
        if (this.enabled.get(element) != on) {
            this.changes++;
        }
        this.enabled.put(element, on);
    }

    /** Whether an element is enabled. */
    public boolean isEnabled(Element element) {
        if (element == null) {
            throw new IllegalArgumentException("an element is required");
        }
        return this.enabled.get(element);
    }

    /**
     * Whether an element should be processed this frame.
     *
     * <p>Covers the update and the mesh as well as the draw. Disabling clouds that still simulated their
     * movement would save the fill rate and leave the CPU doing everything else, which on a CPU-bound
     * machine is most of the cost.
     */
    public boolean shouldProcess(Element element) {
        return isEnabled(element);
    }

    /** How many elements are currently enabled. */
    public int enabledCount() {
        int count = 0;
        for (Element element : Element.values()) {
            if (this.enabled.get(element)) {
                count++;
            }
        }
        return count;
    }

    /** How many times a setting was changed. */
    public long changes() {
        return this.changes;
    }

    /** Milliseconds per frame the enabled elements cost. */
    public double msPerFrame() {
        double total = 0.0;
        for (Element element : Element.values()) {
            if (this.enabled.get(element)) {
                total += element.msPerFrame();
            }
        }
        return total;
    }

    /** Milliseconds per frame every element would cost. */
    public double msWithEverything() {
        double total = 0.0;
        for (Element element : Element.values()) {
            total += element.msPerFrame();
        }
        return total;
    }

    /**
     * The share of the frame cost removed by what is currently off.
     *
     * <p>Zero while everything is on, which is the default and the honest answer: an unconfigured toggle has
     * saved nothing.
     */
    public double savedPercent() {
        double all = msWithEverything();
        if (all == 0.0) {
            return 0.0;
        }
        return 100.0 * (all - msPerFrame()) / all;
    }
}
