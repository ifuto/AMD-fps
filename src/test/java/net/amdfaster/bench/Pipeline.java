package net.amdfaster.bench;

/**
 * One way of turning a scene and a camera into a frame.
 *
 * <p>Two implementations, and the point of the interface is that they are handed exactly the same scene,
 * the same camera, the same assets and the same rasteriser. Anything one of them does that the other
 * does not is a decision, and the difference in frame rate is the price or the value of that decision.
 */
interface Pipeline {

    /** What this pipeline stands for, in the report. */
    String name();

    /** Builds whatever the pipeline caches, once, before the timed loop starts. */
    void prepare(Scene scene, Assets assets);

    /** Draws one frame. */
    void renderFrame(Scene scene, Assets assets, SoftRenderer renderer, Scene.View view, int frame);
}
