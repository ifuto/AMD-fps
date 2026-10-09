package net.amdfaster.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageTimerTest {

    private static final long MS = 1_000_000L;

    @Test
    void registeringTheSameStageTwiceReturnsTheSameIndex() {
        // Stage lookup happens once at setup and the index is reused, so a duplicate registration
        // has to collapse rather than create a second bucket that silently splits the samples.
        StageTimer timer = new StageTimer();
        int mesh = timer.stage("mesh");
        int light = timer.stage("light");
        assertEquals(mesh, timer.stage("mesh"));
        assertEquals(light, timer.stage("light"));
        assertEquals(2, timer.stageCount());
        assertEquals("mesh", timer.name(mesh));
    }

    @Test
    void totalsCountsAndWorstAccumulate() {
        StageTimer timer = new StageTimer();
        int mesh = timer.stage("mesh");

        timer.add(mesh, 5 * MS);
        timer.add(mesh, 1 * MS);
        timer.add(mesh, 9 * MS);

        assertEquals(15 * MS, timer.totalNanos(mesh));
        assertEquals(3, timer.count(mesh));
        assertEquals(5 * MS, timer.meanNanos(mesh));
        assertEquals(9 * MS, timer.worstNanos(mesh), "the worst run is kept, not just the total");
    }

    @Test
    void aStageThatNeverRanAnswersZero() {
        StageTimer timer = new StageTimer();
        int upload = timer.stage("upload");
        assertEquals(0, timer.totalNanos(upload));
        assertEquals(0, timer.count(upload));
        assertEquals(0, timer.meanNanos(upload), "and the mean must not divide by zero");
        assertEquals(0, timer.worstNanos(upload));
    }

    @Test
    void aStageIsTimedEvenWhenTheWorkThrows() {
        // A stage that fails still cost something. Dropping the sample would bias the average
        // towards the runs that succeeded, which is exactly the wrong direction for a tool meant to
        // find what is expensive.
        StageTimer timer = new StageTimer();
        int rebuild = timer.stage("rebuild");

        assertThrows(IllegalStateException.class, () -> timer.run(rebuild, () -> {
            throw new IllegalStateException("rebuild failed");
        }));

        assertEquals(1, timer.count(rebuild), "the failed run was still recorded");
        assertTrue(timer.totalNanos(rebuild) >= 0);
    }

    @Test
    void negativeDurationsAreRejected() {
        StageTimer timer = new StageTimer();
        int stage = timer.stage("cull");
        assertThrows(IllegalArgumentException.class, () -> timer.add(stage, -1),
                "a negative duration would silently cancel out real work in the total");
    }

    @Test
    void perThreadTimersMergeIntoOne() {
        // Parallel meshing gives every worker its own timer, because sharing one would need a lock
        // per stage per section. The merge has to sum totals and counts and keep the worst of the
        // worst -- taking the last worker's worst would understate the tail.
        StageTimer a = new StageTimer();
        StageTimer b = new StageTimer();
        a.add(a.stage("mesh"), 4 * MS);
        a.add(a.stage("mesh"), 6 * MS);
        b.add(b.stage("mesh"), 10 * MS);
        b.add(b.stage("light"), 2 * MS);

        StageTimer merged = new StageTimer();
        merged.addFrom(a);
        merged.addFrom(b);

        int mesh = merged.stage("mesh");
        assertEquals(20 * MS, merged.totalNanos(mesh));
        assertEquals(3, merged.count(mesh));
        assertEquals(10 * MS, merged.worstNanos(mesh), "the worst across all workers, not the last");
        assertEquals(2 * MS, merged.totalNanos(merged.stage("light")));
    }

    @Test
    void aStageRegisteredOnlyInTheOtherTimerSurvivesTheMerge() {
        StageTimer worker = new StageTimer();
        worker.add(worker.stage("upload"), 3 * MS);
        StageTimer merged = new StageTimer();
        merged.stage("mesh");
        merged.addFrom(worker);

        assertEquals(2, merged.stageCount(), "the worker's stage was added rather than dropped");
        assertEquals(3 * MS, merged.totalNanos(merged.stage("upload")));
        assertEquals(0, merged.count(merged.stage("mesh")), "and untouched stages stay at zero");
    }

    @Test
    void stagesWithNoSamplesDoNotCreateBucketsOnMerge() {
        StageTimer worker = new StageTimer();
        worker.stage("never-ran");
        StageTimer merged = new StageTimer();
        merged.addFrom(worker);
        assertEquals(0, merged.stageCount(), "an empty stage is not worth a bucket");
    }

    @Test
    void theReportIsOrderedByTotalLargestFirst() {
        // The question the report answers is "what do I look at first", so the ordering is the
        // content, not a presentation detail.
        StageTimer timer = new StageTimer();
        timer.add(timer.stage("mesh"), 10 * MS);
        timer.add(timer.stage("light"), 40 * MS);
        timer.add(timer.stage("cull"), 20 * MS);

        String report = timer.report(100 * MS);
        int light = report.indexOf("light:");
        int cull = report.indexOf("cull:");
        int mesh = report.indexOf("mesh:");
        assertTrue(light >= 0 && cull > light && mesh > cull,
                "largest total first, got:\n" + report);
        assertTrue(report.contains("40.0% of span"), "percentages are relative to the span");
    }

    @Test
    void theReportOmitsPercentagesWhenThereIsNoSpan() {
        StageTimer timer = new StageTimer();
        timer.add(timer.stage("mesh"), 10 * MS);
        String report = timer.report(0);
        assertTrue(report.contains("mesh:"));
        assertTrue(!report.contains("% of span"), "no span means no percentage, not a division by zero");
    }

    @Test
    void clearResetsTotalsButKeepsTheStageNames() {
        StageTimer timer = new StageTimer();
        int mesh = timer.stage("mesh");
        timer.add(mesh, 10 * MS);
        timer.clear();

        assertEquals(0, timer.totalNanos(mesh));
        assertEquals(0, timer.count(mesh));
        assertEquals(0, timer.worstNanos(mesh));
        assertEquals(1, timer.stageCount(), "the stage is still registered");
        assertEquals("mesh", timer.name(mesh));
        assertEquals(mesh, timer.stage("mesh"), "and re-registering it returns the same index");
    }

    @Test
    void manyStagesGrowTheArrays() {
        // Growth is on the registration path, which runs once per stage at setup, so a doubling
        // copy is affordable there and keeps recording an array index lookup.
        StageTimer timer = new StageTimer();
        for (int i = 0; i < 100; i++) {
            int stage = timer.stage("stage-" + i);
            timer.add(stage, i * MS);
        }
        assertEquals(100, timer.stageCount());
        for (int i = 0; i < 100; i++) {
            assertEquals("stage-" + i, timer.name(i));
            assertEquals(i * MS, timer.totalNanos(i));
        }
        assertEquals(100, timer.totalsByName().size());
    }

    @Test
    void totalsByNamePreservesRegistrationOrder() {
        StageTimer timer = new StageTimer();
        timer.add(timer.stage("z-last"), 1 * MS);
        timer.add(timer.stage("a-first"), 2 * MS);
        assertEquals("[z-last, a-first]", timer.totalsByName().keySet().toString());
    }
}
