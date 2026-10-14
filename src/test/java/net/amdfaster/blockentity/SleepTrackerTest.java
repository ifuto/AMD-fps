package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleepTrackerTest {

    /** A block entity whose sleep behaviour the test controls. */
    private static final class FakeEntity implements SleepTracker.Sleeping {
        private final boolean ticksOnClient;
        private boolean sleepy;
        private int ticks;
        private int wakes;

        FakeEntity(boolean ticksOnClient, boolean sleepy) {
            this.ticksOnClient = ticksOnClient;
            this.sleepy = sleepy;
        }

        @Override
        public boolean canTickOnSide(boolean isClient) {
            return isClient == this.ticksOnClient;
        }

        @Override
        public boolean canSleep() {
            return this.sleepy;
        }

        @Override
        public void wake() {
            this.wakes++;
        }

        @Override
        public void tick() {
            this.ticks++;
        }
    }

    /** A world with a few block entities in known places. */
    private static final class World implements SleepTracker.Resolver {
        final Map<Long, SleepTracker.Sleeping> entities = new HashMap<>();

        void put(int x, int y, int z, SleepTracker.Sleeping entity) {
            this.entities.put(SleepTracker.positionKey(x, y, z), entity);
        }

        @Override
        public SleepTracker.Sleeping at(int x, int y, int z) {
            return this.entities.get(SleepTracker.positionKey(x, y, z));
        }
    }

    @Test
    void anEntityThatCannotTickOnThisSideIsNeverRegistered() {
        // A dragon head animates on the client and does nothing on the server. Registering it on the
        // wrong side is pure waste, and the tracker never holds it so it cannot be ticked by mistake.
        SleepTracker server = new SleepTracker(false);
        FakeEntity clientOnly = new FakeEntity(true, false);

        server.setAwake(SleepTracker.positionKey(0, 64, 0), clientOnly, true);

        assertEquals(0, server.awakeCount());
        assertEquals(0, clientOnly.ticks);
    }

    @Test
    void onlyAwakeEntitiesAreTicked() {
        SleepTracker tracker = new SleepTracker(false);
        FakeEntity a = new FakeEntity(false, false);
        FakeEntity b = new FakeEntity(false, false);
        tracker.setAwake(SleepTracker.positionKey(0, 64, 0), a, true);
        tracker.setAwake(SleepTracker.positionKey(1, 64, 0), b, true);

        tracker.setAwake(SleepTracker.positionKey(1, 64, 0), b, false);
        tracker.tickAll();
        tracker.tickAll();

        assertEquals(2, a.ticks, "the awake one ran twice");
        assertEquals(0, b.ticks, "and the sleeping one not at all");
        assertEquals(2L, tracker.ticksRun());
    }

    @Test
    void anEntityThatReportsItCanSleepStopsBeingTickedImmediately() {
        SleepTracker tracker = new SleepTracker(false);
        FakeEntity idle = new FakeEntity(false, true);
        tracker.setAwake(SleepTracker.positionKey(0, 64, 0), idle, true);

        tracker.tickAll();

        assertEquals(1, idle.ticks, "it ran the tick in which it declared itself idle");
        assertEquals(0, tracker.awakeCount(), "and is not held afterwards");
        assertEquals(1L, tracker.sleepsGranted());

        tracker.tickAll();
        assertEquals(1, idle.ticks, "so it does not run again");
    }

    @Test
    void wakingCallsWakeOnTheEntity() {
        // The part an earlier version of this class did not do. Recording an entity as awake without
        // telling it means it keeps whatever cached state it went to sleep with.
        SleepTracker tracker = new SleepTracker(false);
        World world = new World();
        FakeEntity hopper = new FakeEntity(false, false);
        world.put(0, 64, 0, hopper);

        tracker.wakeAt(world, 0, 64, 0);

        assertEquals(1, hopper.wakes, "wake was called");
        assertEquals(1, tracker.awakeCount());
        assertEquals(1L, tracker.entitiesWoken());
    }

    @Test
    void wakingAnAlreadyAwakeEntityDoesNotWakeItAgain() {
        // Calling wake mid-tick would make the entity re-read caches it is currently using.
        SleepTracker tracker = new SleepTracker(false);
        World world = new World();
        FakeEntity hopper = new FakeEntity(false, false);
        world.put(0, 64, 0, hopper);
        tracker.setAwake(SleepTracker.positionKey(0, 64, 0), hopper, true);

        tracker.wakeAt(world, 0, 64, 0);

        assertEquals(0, hopper.wakes);
        assertEquals(1, tracker.awakeCount());
        assertEquals(0L, tracker.entitiesWoken());
    }

    @Test
    void wakingAnEmptyPositionIsCountedAndHarmless() {
        SleepTracker tracker = new SleepTracker(false);
        World world = new World();

        tracker.wakeAt(world, 5, 70, 5);

        assertEquals(1L, tracker.wakesIssued());
        assertEquals(1L, tracker.wakesFoundNothing());
        assertEquals(0, tracker.awakeCount());
    }

    @Test
    void wakeNeighboursCoversAllSixFaces() {
        // A hopper pulls from above and pushes below, a comparator reads behind, an observer watches
        // what it faces. Covering the six costs six lookups and removes a category of machine that
        // silently stops.
        SleepTracker tracker = new SleepTracker(false);
        World world = new World();
        int[][] neighbours = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        FakeEntity[] entities = new FakeEntity[neighbours.length];
        for (int i = 0; i < neighbours.length; i++) {
            entities[i] = new FakeEntity(false, false);
            world.put(neighbours[i][0], 64 + neighbours[i][1], neighbours[i][2], entities[i]);
        }

        tracker.wakeNeighbours(world, 0, 64, 0);

        assertEquals(6, tracker.awakeCount());
        assertEquals(6L, tracker.entitiesWoken());
        for (FakeEntity entity : entities) {
            assertEquals(1, entity.wakes);
        }
    }

    @Test
    void wakeNeighboursSkipsTheChangedPositionItself() {
        SleepTracker tracker = new SleepTracker(false);
        World world = new World();
        FakeEntity centre = new FakeEntity(false, false);
        world.put(0, 64, 0, centre);

        tracker.wakeNeighbours(world, 0, 64, 0);

        assertEquals(0, tracker.awakeCount(), "the six neighbours only, not the centre");
        assertEquals(6L, tracker.wakesFoundNothing());
    }

    @Test
    void anEntityThatCannotTickOnThisSideIsNotWokenEither() {
        SleepTracker server = new SleepTracker(false);
        World world = new World();
        FakeEntity clientOnly = new FakeEntity(true, false);
        world.put(0, 64, 0, clientOnly);

        server.wakeAt(world, 0, 64, 0);

        assertEquals(0, clientOnly.wakes);
        assertEquals(0, server.awakeCount());
    }

    @Test
    void positionKeyIsInjective() {
        // A collision would make two block entities share one tracker entry, and one of them would
        // stop ticking. Nothing would say which.
        java.util.Set<Long> keys = new java.util.HashSet<>();
        int[][] points = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0},
            {0, 0, -1}, {100, -50, 25}, {-100, 50, -25}, {30000000, 100, -30000000}};
        for (int[] point : points) {
            keys.add(SleepTracker.positionKey(point[0], point[1], point[2]));
        }
        assertEquals(points.length, keys.size());
    }

    @Test
    void countersResetWithoutWakingAnything() {
        SleepTracker tracker = new SleepTracker(false);
        FakeEntity entity = new FakeEntity(false, false);
        tracker.setAwake(SleepTracker.positionKey(0, 64, 0), entity, true);
        tracker.tickAll();

        tracker.resetCounters();

        assertEquals(0L, tracker.ticksRun());
        assertEquals(1, tracker.awakeCount(), "and the entity is still ticking");
    }

    @Test
    void aTrackerKnowsWhichSideItIsOn() {
        assertTrue(new SleepTracker(true).isClient());
        assertFalse(new SleepTracker(false).isClient());
    }
}
