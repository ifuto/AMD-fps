package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vertex order emitted for a quad decides which side is front-facing. Getting it backwards
 * would cull the outside of every block and show the inside of the world, and nothing downstream
 * would catch it -- the geometry would still be perfectly valid.
 *
 * <p>So the winding is derived from the corner tables in {@link Orientation} and checked here
 * against the declared normal, for all six orientations.
 */
class WindingTest {

    static Quad unitQuad(Orientation o) {
        return switch (o) {
            case NEG_X -> Quad.uniform(o, 0, 0, 0, 0, 1, 1, 0f, 0f, 1f, 1f, 0xFFFFFFFF, 0);
            case POS_X -> Quad.uniform(o, 1, 0, 0, 1, 1, 1, 0f, 0f, 1f, 1f, 0xFFFFFFFF, 0);
            case NEG_Y -> Quad.uniform(o, 0, 0, 0, 1, 0, 1, 0f, 0f, 1f, 1f, 0xFFFFFFFF, 0);
            case POS_Y -> Quad.uniform(o, 0, 1, 0, 1, 1, 1, 0f, 0f, 1f, 1f, 0xFFFFFFFF, 0);
            case NEG_Z -> Quad.uniform(o, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0xFFFFFFFF, 0);
            case POS_Z -> Quad.uniform(o, 0, 0, 1, 1, 1, 1, 0f, 0f, 1f, 1f, 0xFFFFFFFF, 0);
        };
    }

    @Test
    void emittedWindingPointsAlongTheDeclaredNormal() {
        for (Orientation o : Orientation.values()) {
            Quad q = unitQuad(o);
            assertFalse(q.isDegenerate(), o + " unit quad must have area");

            int ax = q.cornerX(1) - q.cornerX(0);
            int ay = q.cornerY(1) - q.cornerY(0);
            int az = q.cornerZ(1) - q.cornerZ(0);
            int bx = q.cornerX(2) - q.cornerX(0);
            int by = q.cornerY(2) - q.cornerY(0);
            int bz = q.cornerZ(2) - q.cornerZ(0);

            int nx = ay * bz - az * by;
            int ny = az * bx - ax * bz;
            int nz = ax * by - ay * bx;

            assertEquals(Integer.signum(nx), o.normalX(), o + ": normal X");
            assertEquals(Integer.signum(ny), o.normalY(), o + ": normal Y");
            assertEquals(Integer.signum(nz), o.normalZ(), o + ": normal Z");
        }
    }

    @Test
    void oppositeOrientationsEmitOppositeWinding() {
        for (Orientation o : Orientation.values()) {
            assertEquals(o, o.opposite().opposite());
            // The two ends of an axis must disagree on their normal's sign on that axis.
            int axis = o.axis();
            int self = axis == 0 ? o.normalX() : axis == 1 ? o.normalY() : o.normalZ();
            Orientation opp = o.opposite();
            int other = axis == 0 ? opp.normalX() : axis == 1 ? opp.normalY() : opp.normalZ();
            assertEquals(-self, other, o + " vs " + opp);
        }
    }

    @Test
    void cornerTablesStayInsideTheQuad() {
        for (Orientation o : Orientation.values()) {
            for (int i = 0; i < Quad.VERTICES; i++) {
                assertTrue(o.cornerA(i) == 0 || o.cornerA(i) == 1, o + " cornerA " + i);
                assertTrue(o.cornerB(i) == 0 || o.cornerB(i) == 1, o + " cornerB " + i);
            }
        }
    }

    @Test
    void cornersStayInSectionRange() {
        for (Orientation o : Orientation.values()) {
            Quad q = unitQuad(o);
            for (int i = 0; i < Quad.VERTICES; i++) {
                assertTrue(q.cornerX(i) >= 0 && q.cornerX(i) <= 16, o + " x " + i);
                assertTrue(q.cornerY(i) >= 0 && q.cornerY(i) <= 16, o + " y " + i);
                assertTrue(q.cornerZ(i) >= 0 && q.cornerZ(i) <= 16, o + " z " + i);
            }
        }
    }
}
