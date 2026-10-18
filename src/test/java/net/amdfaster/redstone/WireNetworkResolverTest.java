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
    void theUpdateCountsAreTheCountedOnesNotARoundNumber() {
        // A vanilla wire emits 42 block updates and up to 22 shape updates per power change. Of the 42, six
        // target the wire itself and make it recalculate again, and only 24 neighbours exist within
        // Manhattan distance 2, so 12 of the other 36 are duplicates. Of the 22 shape updates only 6 are
        // needed for a level change; the other 16 go to blocks diagonally above and below, which care about
        // connections and not about level.
        assertEquals(42, WireNetworkResolver.VANILLA_BLOCK_UPDATES);
        assertEquals(22, WireNetworkResolver.VANILLA_SHAPE_UPDATES);
        assertEquals(64, WireNetworkResolver.VANILLA_UPDATES_PER_CHANGE);
        assertEquals(24, WireNetworkResolver.BLOCK_UPDATES);
        assertEquals(6, WireNetworkResolver.SHAPE_UPDATES);
        assertEquals(30, WireNetworkResolver.UPDATES_PER_CHANGE);
        assertEquals(2, WireNetworkResolver.UPDATES_PER_WIRE_LINK, "one block and one shape per link");
    }

    @Test
    void eachWireEmitsOneRoundOfTheReducedUpdates() {
        // A wire in the middle of a network can recalculate its own level half a dozen times before it
        // settles, and every one of those recalculations emits shape and block updates. The single pass
        // emits one round, and that round is 30 rather than 64 because the self-updates, the duplicates and
        // the connection-only shape updates are known before the network is walked.
        WireNetworkResolver resolver = new WireNetworkResolver();

        int emitted = resolver.resolve(WIRES, COMPONENTS, WIRE_LINKS);

        // 100 wires * 30, less 150 links * 2 that stay inside the network.
        assertEquals(2700, emitted);
        assertEquals(100L, resolver.wires());
        assertEquals(0L, resolver.recalculations());
    }

    @Test
    void linksInsideOneNetworkAreNotAnnounced() {
        // The network's levels are all worked out before any of them is written, so telling one wire in it
        // that another wire in it changed is telling it something it already knows. Two updates per link.
        WireNetworkResolver resolver = new WireNetworkResolver();

        resolver.resolve(WIRES, COMPONENTS, WIRE_LINKS);

        assertEquals(300L, resolver.intraNetworkSkipped(), "150 links at two updates each");
        assertEquals(3000L - 300L, resolver.updates());
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
    void theVanillaPathRecalculatesAndEmitsEverythingEveryTime() {
        WireNetworkResolver resolver = new WireNetworkResolver();

        resolver.resolveVanilla(WIRES, COMPONENTS, WIRE_LINKS);

        assertEquals(600L, resolver.recalculations());
        // Six recalculations, each emitting the full 64 -- self-updates and duplicates included, because
        // vanilla has no point at which it knows they are redundant.
        assertEquals(38_400L, resolver.updates());
        assertEquals(0L, resolver.intraNetworkSkipped(), "vanilla announces everything to everything");
    }

    @Test
    void aSinglePassRemovesMostOfTheUpdates() {
        WireNetworkResolver resolver = new WireNetworkResolver();

        resolver.resolve(WIRES, COMPONENTS, WIRE_LINKS);

        assertEquals(2.16, resolver.msUpdates(), 1e-9);
        assertEquals(92.97, resolver.savedPercent(WIRES, COMPONENTS, WIRE_LINKS), 0.01);
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

        assertEquals(30, resolver.resolve(1, 1, 0), "one wire, no links, the reduced round");
        // Five of the six recalculations are gone and the round itself is smaller, so the share avoided is
        // slightly better than the recalculation count alone would give.
        assertEquals(92.19, resolver.savedPercent(1, 1, 0), 0.01);
    }

    @Test
    void aNetworkCannotEmitFewerThanZeroUpdates() {
        // A caller can describe a network with more links than the wires could possibly have, which is
        // nonsense input rather than an error worth throwing over. Clamping says "everything was internal"
        // rather than reporting a negative number of updates.
        WireNetworkResolver resolver = new WireNetworkResolver();

        assertEquals(0, resolver.resolve(1, 0, 1000));
        assertEquals(30L, resolver.intraNetworkSkipped(), "capped at the gross total, not the link count");
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
        assertEquals(0L, resolver.intraNetworkSkipped());
        assertEquals(0L, resolver.componentReads());
        assertEquals(1, WireNetworkResolver.COMPONENT_READS);
        assertEquals(2, WireNetworkResolver.WIRE_READS);
    }
}
