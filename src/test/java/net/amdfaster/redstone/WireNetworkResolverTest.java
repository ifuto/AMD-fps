package net.amdfaster.redstone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireNetworkResolverTest {

    private static final int WIRES = 100;
    private static final int COMPONENTS = 40;
    private static final int WIRE_LINKS = 150;

    @Test
    void eachWireEmitsOneRoundOfUpdates() {
        // A wire in the middle of a network can recalculate its own level half a dozen times before it
        // settles, and every one of those recalculations emits shape and block updates.
        WireNetworkResolver resolver = new WireNetworkResolver();

        int emitted = resolver.resolve(WIRES, COMPONENTS, WIRE_LINKS);

        assertEquals(4200, emitted, "one round of 42 per wire, at the settled level");
        assertEquals(100L, resolver.wires());
        assertEquals(0L, resolver.recalculations());
    }

    @Test
    void aComponentIsReadOnceAndAWireTwice() {
        // A component's output does not depend on the network, so it is read once. Another wire's does, so it
        // is read twice -- once to work the network out, once to settle against the final values. That fixed
        // number is what makes the cost predictable where a recursive walk costs whatever the shape makes it.
        WireNetworkResolver resolver = new WireNetworkResolver();

        resolver.resolve(WIRES, COMPONENTS, WIRE_LINKS);

        assertEquals(40L, resolver.componentReads());
        assertEquals(300L, resolver.wireReads());
    }

    @Test
    void theVanillaPathRecalculatesAndEmitsEveryTime() {
        WireNetworkResolver resolver = new WireNetworkResolver();

        resolver.resolveVanilla(WIRES, COMPONENTS, WIRE_LINKS);

        assertEquals(600L, resolver.recalculations());
        assertEquals(25_200L, resolver.updates(),
                "six recalculations each emitting 42 updates per wire");
    }

    @Test
    void aSinglePassRemovesMostOfTheUpdates() {
        WireNetworkResolver resolver = new WireNetworkResolver();

        resolver.resolve(WIRES, COMPONENTS, WIRE_LINKS);

        assertEquals(3.36, resolver.msUpdates(), 1e-9);
        assertEquals(83.33, resolver.savedPercent(WIRES, COMPONENTS, WIRE_LINKS), 0.01);
    }

    @Test
    void theResolverIsOffByDefault() {
        // The update order becomes directional rather than locational, which a contraption timed around the
        // old order can notice, so a player who has built machines around vanilla's quirks should choose to
        // change them rather than have them changed.
        WireNetworkResolver resolver = new WireNetworkResolver();

        assertFalse(resolver.isEnabled());
        resolver.enable();
        assertTrue(resolver.isEnabled());
    }

    @Test
    void aSingleWireIsStillOneRound() {
        WireNetworkResolver resolver = new WireNetworkResolver();

        assertEquals(42, resolver.resolve(1, 1, 0));
        assertEquals(16.67, resolver.savedPercent(1, 1, 0), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        WireNetworkResolver resolver = new WireNetworkResolver();

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveVanilla(-1, 0, 0));
    }

    @Test
    void aFreshResolverReportsZeroNotNaN() {
        // A resolver that has done nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        WireNetworkResolver resolver = new WireNetworkResolver();

        assertEquals(0.0, resolver.savedPercent(WIRES, COMPONENTS, WIRE_LINKS));
        assertEquals(0.0, resolver.msUpdates(), 1e-12);
        assertEquals(0L, resolver.wires());
        assertEquals(0L, resolver.updates());
        assertEquals(0L, resolver.componentReads());
        assertEquals(1, WireNetworkResolver.COMPONENT_READS);
        assertEquals(2, WireNetworkResolver.WIRE_READS);
    }
}
