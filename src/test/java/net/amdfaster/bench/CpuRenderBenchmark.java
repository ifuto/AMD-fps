package net.amdfaster.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The FPS number, measured rather than modelled.
 *
 * <p>Everything before this class produced arithmetic: a frame split, a set of percentages, a ratio. That
 * arithmetic was wrong once in a way that mattered -- a 4.0 ms "irreducible" residual silently capped
 * every speedup the model could report at 1.93x, so the 3x the project is aiming at was unreachable
 * before any code was written. A cost model can be self-consistent and still be fiction.
 *
 * <p>So this renders. Real PNG textures and real model JSON from {@code bench/assets}, a real voxel world
 * with a real mesher, a real perspective-correct software rasteriser writing 1280x720 pixels, and a
 * stopwatch. Nothing is displayed, which is the point: the number is the deliverable.
 *
 * <p>It fails until AMD-Faster is three times the all-mods stack, and while it fails the assertion message
 * carries the measurement. That is deliberate. The CI log cannot be downloaded in this environment and the
 * annotations are the only channel out of it, so the numbers travel in the failure text.
 *
 * <h2>What this benchmark can and cannot see</h2>
 *
 * <p>The cost of a pixel and the cost of a submitted vertex are identical for both pipelines, so what the
 * ratio measures is how much less geometry and how much less fill AMD-Faster submits. It cannot see the
 * price of a GL draw call, because a software rasteriser has no driver -- so {@code EntityBatcher} turning
 * three hundred draws into nine shows up here as a count and not as time. On AMD hardware, where a draw
 * call costs an order of magnitude more than on NVIDIA, that count is a large part of the real win and this
 * benchmark understates it. Stating the blind spot is the honest thing to do; quietly letting the number
 * stand for more than it measures is not.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CpuRenderBenchmark {

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    /** The acceptance criterion: three times the all-mods stack. */
    static final double TARGET = 3.0;

    /** Frames run before the clock starts, so the measured loop is compiled code rather than interpreted. */
    static final int WARMUP_FRAMES = 10;

    /** How long each pipeline is timed for, in nanoseconds. */
    static final long BUDGET_NANOS = 1_500_000_000L;

    /** A ceiling on frames, so a very fast machine does not turn this into a long test. */
    static final int MAX_FRAMES = 400;

    /**
     * How much of the baseline's picture AMD-Faster must still draw.
     *
     * <p>This is the correctness guard, and it is the reason the benchmark cannot be won by culling too
     * much. Everything AMD-Faster removes is supposed to be invisible already -- occluded geometry, faces
     * turned away, entities two pixels tall -- so the pixel count should barely move. If a mask or a
     * matrix is wrong, the frame rate goes up and this goes down, and the test fails on the second number
     * rather than passing on the first.
     */
    static final double MIN_PIXEL_RETENTION = 0.90;

    private static Assets assets;

    @BeforeAll
    static void loadAssets() throws Exception {
        assets = Assets.load();
    }

    @Test
    @Order(1)
    void plainsRd12() {
        run(Scene.PLAINS);
    }

    @Test
    @Order(2)
    void villageOverCave() {
        run(Scene.VILLAGE_CAVE);
    }

    @Test
    @Order(3)
    void hundredTnt() {
        run(Scene.TNT);
    }

    private static final class Result {
        double fps;
        long frames;
        double trianglesPerFrame;
        double pixelsPerFrame;
        double testedPerFrame;
        double drawsPerFrame;
        int drawnPixels;
    }

    private void run(int kind) {
        Scene scene = Scene.build(kind, assets);
        SoftRenderer renderer = new SoftRenderer(WIDTH, HEIGHT);
        float aspect = WIDTH / (float) HEIGHT;

        // Two weightings of the same frame, because a software rasteriser cannot be the judge of a
        // client whose pixels belong to a GPU. Fill on charges both for every pixel and reports a floor;
        // fill off leaves the CPU-side pipeline -- culling, sorting, batching, transforms, draw calls --
        // which is what the frame rate of a CPU-bound client is actually made of. The truth for a real
        // client sits between them, and at this resolution it sits near the top.
        Result baseFill = measureBest(BaselinePipeline::new, scene, renderer, aspect, true);
        Result fastFill = measureBest(AmdFasterPipeline::new, scene, renderer, aspect, true);
        Result baseCpu = measureBest(BaselinePipeline::new, scene, renderer, aspect, false);
        lastBaseline = (BaselinePipeline) lastPipeline;
        Result fastCpu = measureBest(AmdFasterPipeline::new, scene, renderer, aspect, false);

        double fillRatio = fastFill.fps / baseFill.fps;
        double cpuRatio = fastCpu.fps / baseCpu.fps;
        double retention = fastFill.drawnPixels / (double) Math.max(1, baseFill.drawnPixels);

        String report = report(scene, baseFill, fastFill, baseCpu, fastCpu, fillRatio, cpuRatio, retention);
        // Retention first: a pipeline that is fast because it stopped drawing the world is not a result,
        // and reporting its frame rate without saying so would be the same mistake the cost model made.
        assertTrue(retention >= MIN_PIXEL_RETENTION,
                "AMD-Faster drew only " + percent(retention) + " of the baseline's pixels, which means a "
                        + "culler is removing visible geometry and the frame rate below is meaningless.\n"
                        + report);
        assertTrue(cpuRatio >= TARGET,
                "cpu-bound " + String.format(Locale.ROOT, "%.2f", cpuRatio) + "x, fill-bound "
                        + String.format(Locale.ROOT, "%.2f", fillRatio) + "x, target is " + TARGET + "x\n"
                        + report);
    }

    /**
     * How many times each pipeline is timed, keeping the fastest.
     *
     * <p>The runner has four cores and other things on them. Two runs of the same commit put the
     * baseline's cpu-bound frame at 881 and 603 microseconds, which is a swing of forty-six percent --
     * wider than anything the last several changes were worth, so a single timing cannot tell an
     * improvement from a quieter moment. Contention only ever makes a run slower, so the fastest of
     * several is the one closest to the machine actually doing the work, and taking it for both
     * pipelines keeps the comparison honest.
     */
    static final int REPEATS = 3;

    private static Result measureBest(java.util.function.Supplier<Pipeline> factory, Scene scene,
            SoftRenderer renderer, float aspect, boolean fill) {
        Result best = null;
        for (int attempt = 0; attempt < REPEATS; attempt++) {
            Result result = measure(factory, scene, renderer, aspect, fill);
            if (best == null || result.fps > best.fps) {
                best = result;
            }
        }
        return best;
    }

    /** The pipeline the last {@link #measure} ran, so its stage timings can be reported. */
    private static Pipeline lastPipeline;

    /** The baseline the cpu-bound {@link #measure} ran, for the same reason. */
    private static BaselinePipeline lastBaseline;

    private static Result measure(java.util.function.Supplier<Pipeline> factory, Scene scene,
            SoftRenderer renderer, float aspect, boolean fill) {
        Pipeline pipeline = factory.get();
        lastPipeline = pipeline;
        renderer.setFillEnabled(fill);
        pipeline.prepare(scene, assets);
        Scene.View view = new Scene.View();
        for (int frame = 0; frame < WARMUP_FRAMES; frame++) {
            scene.view(frame, aspect, view);
            renderer.setCamera(view.x, view.y, view.z,
                    view.rightX, view.rightY, view.rightZ,
                    view.upX, view.upY, view.upZ,
                    view.fwdX, view.fwdY, view.fwdZ,
                    view.nearHalfWidth, view.nearHalfHeight, view.near);
            pipeline.renderFrame(scene, assets, renderer, view, frame);
        }

        renderer.resetCounters();
        int firstDrawn = renderer.drawnPixels();
        long start = System.nanoTime();
        int frames = 0;
        do {
            scene.view(frames, aspect, view);
            renderer.setCamera(view.x, view.y, view.z,
                    view.rightX, view.rightY, view.rightZ,
                    view.upX, view.upY, view.upZ,
                    view.fwdX, view.fwdY, view.fwdZ,
                    view.nearHalfWidth, view.nearHalfHeight, view.near);
            pipeline.renderFrame(scene, assets, renderer, view, frames);
            frames++;
        } while (System.nanoTime() - start < BUDGET_NANOS && frames < MAX_FRAMES);
        long elapsed = System.nanoTime() - start;

        Result result = new Result();
        result.frames = frames;
        result.fps = frames * 1_000_000_000.0 / elapsed;
        result.trianglesPerFrame = renderer.trianglesSubmitted / (double) frames;
        result.pixelsPerFrame = renderer.pixelsShaded / (double) frames;
        result.testedPerFrame = renderer.pixelsTested / (double) frames;
        result.drawsPerFrame = renderer.drawCalls / (double) frames;
        result.drawnPixels = firstDrawn;
        return result;
    }

    private static String report(Scene scene, Result base, Result fast, Result baseCpu, Result fastCpu,
            double fillRatio, double cpuRatio, double retention) {
        // The pipeline the last measure() ran, which is the AMD-Faster one, and the reason this method
        // can quote its stage timings without them being threaded through its signature.
        AmdFasterPipeline timed = (AmdFasterPipeline) lastPipeline;
        BaselinePipeline baseStages = CpuRenderBenchmark.lastBaseline;
        // The counters live on the pipelines, which are gone by the time the report is built, so the
        // per-pass detail is collected in a second untimed pass over the same scene. Cheap, and it keeps
        // the timed loop free of bookkeeping.
        Detail detail = detail(scene);
        return String.format(Locale.ROOT,
                "%s %dx%d | fill-bound base %.1f fast %.1f (%.2fx)"
                        + " | cpu-bound base %.1f fast %.1f (%.2fx) | pixels retained %.1f%%%n"
                        + "per frame  base: %.0f tris, %.0f px, %.0f draws, %.1f%% of screen painted"
                        + "  ||  fast: %.0f tris, %.0f px, %.0f draws, %.1f%% of screen painted%n"
                        + "sections %d->%d (occluded %d) | face meshes culled %d | entities %d->%d"
                        + " (coarse %d, occluded %d) | shadows %d->%d | particles %d->%d"
                        + " | block entities animated %d skipped %d | hud rebuilds %d (%d elements)"
                        + " | gui batches %d | rain rebuilds %d->%d, drops %d->%d | texture uploads %d->%d"
                        + " | world %d blocks, %d textures, %d texels%n"
                        + "AMD-Faster microseconds per frame by stage: terrain %.0f occlusion %.0f"
                        + " entities %.0f particles %.0f clouds %.0f gui %.0f%n"
                        + "  of which terrain is cull+sort %.0f, occluder %.0f, submit %.0f,"
                        + " translucent %.0f%n"
                        + "  triangles per frame: terrain %.0f clouds %.0f particles %.0f"
                        + " of %.0f total | frustum tests %d leaves %d%n"
                        + "baseline microseconds per frame by stage: terrain %.0f entities %.0f"
                        + " particles %.0f clouds %.0f gui %.0f",
                scene.name, WIDTH, HEIGHT,
                base.fps, fast.fps, fillRatio, baseCpu.fps, fastCpu.fps, cpuRatio, retention * 100.0,
                base.trianglesPerFrame, base.pixelsPerFrame, base.drawsPerFrame,
                100.0 * base.drawnPixels / (WIDTH * HEIGHT),
                fast.trianglesPerFrame, fast.pixelsPerFrame, fast.drawsPerFrame,
                100.0 * fast.drawnPixels / (WIDTH * HEIGHT),
                detail.baseSections, detail.fastSections, detail.occluded, detail.faceMeshesCulled,
                detail.baseEntities, detail.fastEntities, detail.coarse, detail.entitiesOccluded,
                detail.baseShadows, detail.fastShadows, detail.baseParticles, detail.fastParticles,
                detail.blockEntitiesAnimated, detail.blockEntitiesSkipped, detail.hudRebuilds,
                detail.hudElements, detail.guiBatches,
                detail.baseRainRebuilds, detail.fastRainRebuilds,
                detail.baseRainDrops, detail.fastRainDrops,
                detail.baseTextureUpdates, detail.fastTextureUploads,
                scene.worldBlocks(), assets.textureCount(), assets.texelCount(),
                timed.nsTerrain / 1000.0 / fastCpu.frames,
                timed.nsOcclusion / 1000.0 / fastCpu.frames,
                timed.nsEntities / 1000.0 / fastCpu.frames,
                timed.nsParticles / 1000.0 / fastCpu.frames,
                timed.nsClouds / 1000.0 / fastCpu.frames,
                timed.nsGui / 1000.0 / fastCpu.frames,
                timed.nsCullSort / 1000.0 / fastCpu.frames,
                timed.nsOccluder / 1000.0 / fastCpu.frames,
                timed.nsSubmit / 1000.0 / fastCpu.frames,
                timed.nsTranslucent / 1000.0 / fastCpu.frames,
                timed.terrainTriangles / (double) fastCpu.frames,
                timed.cloudTriangles / (double) fastCpu.frames,
                timed.particleTriangles / (double) fastCpu.frames,
                fastCpu.trianglesPerFrame, timed.frustumTests, timed.frustumLeaves,
                baseStages.nsTerrain / 1000.0 / baseCpu.frames,
                baseStages.nsEntities / 1000.0 / baseCpu.frames,
                baseStages.nsParticles / 1000.0 / baseCpu.frames,
                baseStages.nsClouds / 1000.0 / baseCpu.frames,
                baseStages.nsGui / 1000.0 / baseCpu.frames);
    }

    /** One untimed frame per pipeline, for the counters the report quotes. */
    private static Detail detail(Scene scene) {
        SoftRenderer renderer = new SoftRenderer(WIDTH, HEIGHT);
        Scene.View view = new Scene.View();
        scene.view(0, WIDTH / (float) HEIGHT, view);
        renderer.setCamera(view.x, view.y, view.z,
                view.rightX, view.rightY, view.rightZ,
                view.upX, view.upY, view.upZ,
                view.fwdX, view.fwdY, view.fwdZ,
                view.nearHalfWidth, view.nearHalfHeight, view.near);
        BaselinePipeline base = new BaselinePipeline();
        base.prepare(scene, assets);
        base.renderFrame(scene, assets, renderer, view, 0);
        renderer.setCamera(view.x, view.y, view.z,
                view.rightX, view.rightY, view.rightZ,
                view.upX, view.upY, view.upZ,
                view.fwdX, view.fwdY, view.fwdZ,
                view.nearHalfWidth, view.nearHalfHeight, view.near);
        AmdFasterPipeline fast = new AmdFasterPipeline();
        fast.prepare(scene, assets);
        fast.renderFrame(scene, assets, renderer, view, 0);

        Detail d = new Detail();
        d.baseSections = base.sectionsDrawn;
        d.fastSections = fast.sectionsDrawn;
        d.occluded = fast.sectionsOccluded;
        d.faceMeshesCulled = fast.faceMeshesCulled;
        d.baseEntities = base.entitiesDrawn;
        d.fastEntities = fast.entitiesDrawn;
        d.coarse = fast.entitiesCoarse;
        d.entitiesOccluded = fast.entitiesOccluded;
        d.baseShadows = base.shadowsDrawn;
        d.fastShadows = fast.shadowsDrawn;
        d.baseParticles = base.particlesDrawn;
        d.fastParticles = fast.particlesDrawn;
        d.blockEntitiesAnimated = fast.blockEntitiesAnimated;
        d.blockEntitiesSkipped = fast.blockEntitiesSkipped;
        d.hudRebuilds = fast.hudRebuilds;
        d.hudElements = fast.hudElements;
        d.guiBatches = fast.guiBatches;
        d.baseRainRebuilds = base.rainRebuilds;
        d.fastRainRebuilds = fast.rainRebuilds;
        d.baseRainDrops = base.rainDrops;
        d.fastRainDrops = fast.rainDrawn;
        d.baseRainDrops = base.rainDrops;
        d.fastRainDrops = fast.rainDrawn;
        d.baseTextureUpdates = base.textureUpdates;
        d.fastTextureUploads = fast.textureUploads;
        return d;
    }

    private static final class Detail {
        long baseSections;
        long fastSections;
        long occluded;
        long faceMeshesCulled;
        long baseEntities;
        long fastEntities;
        long coarse;
        long entitiesOccluded;
        long baseShadows;
        long fastShadows;
        long baseParticles;
        long fastParticles;
        long blockEntitiesAnimated;
        long blockEntitiesSkipped;
        long hudRebuilds;
        long hudElements;
        long guiBatches;
        long baseRainRebuilds;
        long fastRainRebuilds;
        long baseRainDrops;
        long fastRainDrops;
        long baseTextureUpdates;
        long fastTextureUploads;
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }
}
