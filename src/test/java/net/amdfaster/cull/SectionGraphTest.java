package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionGraphTest {

    /** A finite world of sections with masks we choose. Anything outside is unloaded. */
    private static final class World implements SectionGraph.SectionSource {
        private final Map<String, SectionBoundaryMask> sections = new HashMap<>();

        void put(int x, int y, int z, int mask) {
            this.sections.put(x + "," + y + "," + z, new SectionBoundaryMask(mask));
        }

        @Override
        public SectionBoundaryMask maskAt(int x, int y, int z) {
            return this.sections.get(x + "," + y + "," + z);
        }
    }

    private static final int ALL = SectionBoundaryMask.SEALED;

    @Test
    void aFullySealedSectionSeesOnlyItself() {
        // This is the case the whole approach exists for. Six integer tests decide that everything behind
        // these walls is unreachable, and no depth buffer will tell you that more cheaply.
        World world = new World();
        world.put(0, 0, 0, ALL);

        SectionGraph graph = new SectionGraph();
        List<String> reached = new ArrayList<>();
        int count = graph.walk(world, 0, 0, 0,
                (x, y, z, depth) -> reached.add(x + "," + y + "," + z));

        assertEquals(1, count);
        assertEquals(List.of("0,0,0"), reached);
        assertEquals(6, graph.blockedBySealedFace(), "all six faces refused the crossing");
    }

    @Test
    void anUnloadedCameraSectionReachesNothing() {
        World world = new World();
        world.put(1, 0, 0, ALL);

        SectionGraph graph = new SectionGraph();
        int count = graph.walk(world, 0, 0, 0, (x, y, z, depth) -> {
            throw new AssertionError("nothing should be reached");
        });

        assertEquals(0, count);
        assertEquals(1, graph.blockedByUnloaded());
    }

    @Test
    void aCorridorIsWalkedToItsEnds() {
        // Three sections along X, sealed on every face that is not an X face. The walk should reach all
        // three and no further, because the neighbours beyond the ends do not exist.
        World world = new World();
        int openOnX = ALL & ~(1 << SectionBoundaryMask.FACE_X_NEG) & ~(1 << SectionBoundaryMask.FACE_X_POS);
        world.put(-1, 0, 0, openOnX);
        world.put(0, 0, 0, openOnX);
        world.put(1, 0, 0, openOnX);

        SectionGraph graph = new SectionGraph();
        int count = graph.walk(world, 0, 0, 0, (x, y, z, depth) -> { });

        assertEquals(3, count);
        // Every section in the corridor refuses its four non-X faces, so the total is four per section
        // rather than four overall. Only the two faces pointing off the ends find no neighbour.
        assertEquals(12, graph.blockedBySealedFace(), "four non-X faces on each of the three sections");
        assertEquals(2, graph.blockedByUnloaded(), "one at each end of the corridor");
    }

    @Test
    void aWallInTheMiddleOfACorridorStopsTheWalk() {
        World world = new World();
        int openOnX = ALL & ~(1 << SectionBoundaryMask.FACE_X_NEG) & ~(1 << SectionBoundaryMask.FACE_X_POS);
        world.put(-1, 0, 0, openOnX);
        world.put(0, 0, 0, openOnX);
        world.put(1, 0, 0, ALL);      // solid, so it stops here
        world.put(2, 0, 0, openOnX);  // and this is never reached

        SectionGraph graph = new SectionGraph();
        List<String> reached = new ArrayList<>();
        int count = graph.walk(world, 0, 0, 0,
                (x, y, z, depth) -> reached.add(String.valueOf(x)));

        assertEquals(3, count, "the camera section, the open one to the left, and the wall itself");
        assertTrue(reached.contains("1"), "the wall section is reachable, it just does not lead anywhere");
        assertTrue(!reached.contains("2"), "and what is behind it is not");
    }

    @Test
    void theDepthLimitStopsTheWalkAndIsReported() {
        World world = new World();
        for (int x = -4; x <= 4; x++) {
            world.put(x, 0, 0, ALL & ~(1 << SectionBoundaryMask.FACE_X_NEG)
                    & ~(1 << SectionBoundaryMask.FACE_X_POS));
        }

        SectionGraph graph = new SectionGraph(1, 1024);
        int count = graph.walk(world, 0, 0, 0, (x, y, z, depth) -> { });

        assertEquals(3, count, "the origin and its two immediate neighbours");
        assertEquals(2, graph.hitDepthLimit(), "both neighbours were at the limit");
    }

    @Test
    void theVisitLimitStopsTheWalkAndIsReported() {
        World world = new World();
        for (int x = -50; x <= 50; x++) {
            world.put(x, 0, 0, ALL & ~(1 << SectionBoundaryMask.FACE_X_NEG)
                    & ~(1 << SectionBoundaryMask.FACE_X_POS));
        }

        SectionGraph graph = new SectionGraph(64, 10);
        int count = graph.walk(world, 0, 0, 0, (x, y, z, depth) -> { });

        assertEquals(10, count, "exactly the budget");
        assertEquals(10, graph.visits());
        assertTrue(graph.hitVisitLimit() > 0, "and hitting it is visible rather than silent");
    }

    @Test
    void eachSectionIsReachedOnceHoweverManyWaysLeadToIt() {
        // An open 3x3 plane of sections reaches the centre from four directions. Counting it four times
        // would make the caller mesh or draw it four times.
        World world = new World();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.put(x, 0, z, SectionBoundaryMask.OPEN);
            }
        }

        SectionGraph graph = new SectionGraph();
        List<String> reached = new ArrayList<>();
        int count = graph.walk(world, 0, 0, 0,
                (x, y, z, depth) -> reached.add(x + "," + z));

        assertEquals(9, count);
        assertEquals(9, reached.size());
        assertEquals(9, new java.util.HashSet<>(reached).size(), "no duplicates");
    }

    @Test
    void theOriginSectionIsAlwaysIncluded() {
        // The camera's own section is never culled by this. Whatever it contains is by definition
        // visible, and dropping it would empty the screen whenever the player stands in a sealed room.
        World world = new World();
        world.put(0, 0, 0, ALL);

        SectionGraph graph = new SectionGraph();
        int[] depths = new int[1];
        graph.walk(world, 0, 0, 0, (x, y, z, depth) -> depths[0] = depth);

        assertEquals(1, graph.reached());
        assertEquals(0, depths[0], "and at depth zero");
    }

    @Test
    void coordinatePackingIsInjective() {
        // Collisions in the visited set would drop sections from the walk, which shows up as geometry
        // vanishing at particular coordinates and nowhere else.
        int[][] points = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0},
            {0, 0, -1}, {100, -50, 25}, {-100, 50, -25}};
        java.util.Set<Long> keys = new java.util.HashSet<>();
        for (int[] point : points) {
            keys.add(SectionGraph.pack(point[0], point[1], point[2]));
        }
        assertEquals(points.length, keys.size());
    }

    @Test
    void countersResetBetweenWalks() {
        World world = new World();
        world.put(0, 0, 0, ALL);

        SectionGraph graph = new SectionGraph();
        graph.walk(world, 0, 0, 0, (x, y, z, depth) -> { });
        assertEquals(6, graph.blockedBySealedFace());

        graph.walk(world, 0, 0, 0, (x, y, z, depth) -> { });
        assertEquals(6, graph.blockedBySealedFace(), "not twelve -- each walk starts from zero");
        assertEquals(1, graph.reached());
    }

    @Test
    void nonsenseLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SectionGraph(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new SectionGraph(8, 0));
    }
}
