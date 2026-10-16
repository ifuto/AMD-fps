package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectedTextureSelectorTest {

    private static final boolean[] links(boolean... flags) {
        return flags;
    }

    @Test
    void anIsolatedFaceUsesTheFirstRegion() {
        // Glass beside nothing is the edge of a window, drawn as a separate pane.
        ConnectedTextureSelector selector = new ConnectedTextureSelector();

        assertEquals(0, selector.select(links(false, false, false, false)));
        assertEquals(0L, selector.connected());
    }

    @Test
    void eachNeighbourSetsItsOwnBit() {
        // Four neighbours with two states each give sixteen regions, and the mapping has to be stable or
        // two identical walls look different.
        ConnectedTextureSelector selector = new ConnectedTextureSelector();

        assertEquals(1, selector.select(links(true, false, false, false)));
        assertEquals(2, selector.select(links(false, true, false, false)));
        assertEquals(4, selector.select(links(false, false, true, false)));
        assertEquals(8, selector.select(links(false, false, false, true)));
    }

    @Test
    void everyCombinationMapsToADistinctRegion() {
        ConnectedTextureSelector selector = new ConnectedTextureSelector();
        boolean[] seen = new boolean[ConnectedTextureSelector.VARIANTS];

        for (int mask = 0; mask < ConnectedTextureSelector.VARIANTS; mask++) {
            boolean[] connects = new boolean[4];
            for (int bit = 0; bit < 4; bit++) {
                connects[bit] = (mask & (1 << bit)) != 0;
            }
            int region = selector.select(connects);

            assertTrue(region >= 0 && region < ConnectedTextureSelector.VARIANTS);
            assertFalse(seen[region], "region " + region + " used twice");
            seen[region] = true;
        }
        assertEquals(16, selector.selections());
    }

    @Test
    void aDifferentBlockDoesNotConnect() {
        // Glass beside stone is the edge of a window. Drawing it as though the surface continued would
        // show glass running into the wall.
        assertFalse(ConnectedTextureSelector.connects(false, true));
        assertTrue(ConnectedTextureSelector.connects(true, true));
    }

    @Test
    void theNeighbourHasToFaceBack() {
        // A pane on the north side and one on the east side meet at a corner rather than continuing one
        // surface. Connecting them would smear a texture across a real change of plane.
        assertFalse(ConnectedTextureSelector.connects(true, false));
    }

    @Test
    void theDiagonalsDoNotTakePart() {
        // Including them would produce the inner-corner variants that some packs define separately, and a
        // pack that does not define them would show the wrong region at every inside corner.
        ConnectedTextureSelector selector = new ConnectedTextureSelector();

        assertEquals(0, selector.select(links(false, false, false, false)),
                "four inputs only; a diagonal has no place to be expressed");
        assertEquals(4, ConnectedTextureSelector.NEIGHBOURS);
        assertEquals(16, ConnectedTextureSelector.VARIANTS);
    }

    @Test
    void theWrongNeighbourCountIsRejected() {
        ConnectedTextureSelector selector = new ConnectedTextureSelector();

        assertThrows(IllegalArgumentException.class,
                () -> selector.select(links(false, false, false)));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(links(false, false, false, false, false)));
    }

    @Test
    void aFreshSelectorReportsZeroNotNaN() {
        // A selector that has done nothing has connected nothing, and reporting that as a share would
        // describe an unused one as though it had a result.
        ConnectedTextureSelector selector = new ConnectedTextureSelector();

        assertEquals(0.0, selector.connectedPercent());
        assertEquals(0L, selector.selections());
        assertEquals(0L, selector.connected());
    }

    @Test
    void aFullySurroundedFaceReportsAllLinksConnected() {
        ConnectedTextureSelector selector = new ConnectedTextureSelector();

        selector.select(links(true, true, true, true));

        assertEquals(15, selector.select(links(true, true, true, true)));
        assertEquals(100.0, selector.connectedPercent(), 1e-9);
    }
}
