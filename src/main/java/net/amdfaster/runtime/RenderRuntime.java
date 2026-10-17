package net.amdfaster.runtime;

import net.amdfaster.render.AnimatedTextureScheduler;
import net.amdfaster.render.CloudMeshCache;
import net.amdfaster.render.GuiBatcher;
import net.amdfaster.render.HudTextureCache;
import net.amdfaster.render.HudUpdateScheduler;
import net.amdfaster.render.InventoryIconCache;
import net.amdfaster.render.RenderElementToggle;
import net.amdfaster.render.TextVertexBuffer;
import net.amdfaster.render.WeatherMeshCache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the render-side decisions in one place, in the order that makes each cache worth having.
 *
 * <p>Everything the game draws that is not terrain falls into a small number of cases: a HUD that mostly does not
 * change, a GUI that is a list of quads, text that is the same string every frame, clouds and weather whose mesh
 * changes slowly, and animated textures that only matter when something is looking at them. Each has a component
 * that decides whether it needs rebuilding, and each is correct on its own -- but they share one property that
 * none of them can provide for itself: they all have to be invalidated at the same moments, and missing one means
 * the player sees a stale HUD over a fresh world.
 *
 * <p>This owns them, drives them from the frame, and invalidates them together.
 *
 * <h2>Order: toggle, then update, then cache</h2>
 *
 * <p>An element the player turned off should not cost an update check, and an element that does not need updating
 * should not cost a cache lookup. So the toggle is asked first, the update schedule second, and the caches last --
 * each stage only sees what the previous one kept.
 *
 * <h2>The HUD is a texture, not a rebuild</h2>
 *
 * <p>Blitting one quad is cheaper than drawing forty elements, so a HUD that did not change is blitted rather than
 * redrawn. The texture is rebuilt only when the update schedule says the HUD changed, or the window was resized --
 * because a texture built at the old size would be stretched or cropped, and the health bar would be the wrong
 * width.
 *
 * <h2>Text is keyed on the whole string</h2>
 *
 * <p>A hash collision would show the wrong words, which is worse than rebuilding: a sign reading "Chest of
 * diamonds" as "Chest of diamond!" is a bug the player can read. So the buffer compares the text, and a key that
 * is not the text is not a cache key.
 *
 * <h2>Animated textures are skipped when nothing is looking</h2>
 *
 * <p>A water texture animating in a chunk nobody can see is work for nothing, and the phase is derived from the
 * world time rather than accumulated, so a texture that was skipped for a while resumes at the right frame rather
 * than jumping.
 *
 * <p>Not thread safe. Everything here runs on the render thread.
 */
public final class RenderRuntime {

    private final RenderElementToggle toggles = new RenderElementToggle();
    private final HudUpdateScheduler hudSchedule = new HudUpdateScheduler();
    private final HudTextureCache hudTexture = new HudTextureCache();
    private final CloudMeshCache clouds = new CloudMeshCache();
    private final WeatherMeshCache weather = new WeatherMeshCache();
    private final AnimatedTextureScheduler animated = new AnimatedTextureScheduler();
    private final GuiBatcher gui = new GuiBatcher();
    private final InventoryIconCache icons = new InventoryIconCache();
    private final Map<String, TextVertexBuffer> textBuffers = new HashMap<>();

    /**
     * The HUD's bounds in pixels, which the texture is sized to.
     *
     * <p>The texture is the size of the HUD rather than of the screen, because a texture covering the whole
     * window would waste most of its pixels on empty space. It cannot be zero, so it has a default and the
     * render path should set the real value.
     */
    private int hudWidth = DEFAULT_HUD_WIDTH;
    private int hudHeight = DEFAULT_HUD_HEIGHT;

    /** The HUD bounds used when the render path has not set them. */
    public static final int DEFAULT_HUD_WIDTH = 400;
    public static final int DEFAULT_HUD_HEIGHT = 120;

    private long frames;

    /** Sets the HUD's bounds, so the texture is the right size. */
    public void setHudBounds(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException(
                    "the HUD has a size: " + width + "x" + height);
        }
        this.hudWidth = width;
        this.hudHeight = height;
        this.hudTexture.invalidate();
    }

    /** The element toggles, so a player can turn clouds or weather off. */
    public RenderElementToggle toggles() {
        return this.toggles;
    }

    public HudUpdateScheduler hudSchedule() {
        return this.hudSchedule;
    }

    public HudTextureCache hudTexture() {
        return this.hudTexture;
    }

    public CloudMeshCache clouds() {
        return this.clouds;
    }

    public WeatherMeshCache weather() {
        return this.weather;
    }

    public AnimatedTextureScheduler animated() {
        return this.animated;
    }

    public GuiBatcher gui() {
        return this.gui;
    }

    public InventoryIconCache icons() {
        return this.icons;
    }

    /**
     * The text buffer for one piece of text, creating it on first use.
     *
     * <p>Keyed by the text's identity in the caller rather than by a hash of the text, because a hash collision
     * would show the wrong words -- a sign reading "Chest of diamonds" as "Chest of diamond!" is a bug the
     * player can read, and worse than rebuilding the glyphs.
     */
    public TextVertexBuffer textBuffer(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("a key is required");
        }
        return this.textBuffers.computeIfAbsent(key, k -> new TextVertexBuffer());
    }

    /** How many text buffers are held. */
    public int textBuffers() {
        return this.textBuffers.size();
    }

    /**
     * Runs one frame of the render-side decisions.
     *
     * @param playerActed whether the player did something the HUD shows
     * @param debugOpen whether the debug overlay is shown
     * @param screenWidth the window width, for the HUD texture
     * @param screenHeight the window height
     * @param worldTime the world time, which the animated textures and the cloud shape derive from
     * @param raining whether it is raining
     * @return what to draw
     */
    public Frame frame(boolean playerActed, boolean debugOpen, int screenWidth, int screenHeight,
            long worldTime, boolean raining) {
        this.frames++;

        // 1. Toggle, first: an element the player turned off should not cost an update check.
        boolean cloudsOn = this.toggles.shouldProcess(RenderElementToggle.Element.CLOUDS);
        boolean weatherOn = this.toggles.shouldProcess(RenderElementToggle.Element.WEATHER) && raining;

        // 2. Update schedule, then the cache.
        boolean hudUpdated = this.hudSchedule.shouldUpdate(playerActed, debugOpen);
        boolean blit = this.hudTexture.canBlit(screenWidth, screenHeight, hudUpdated,
                this.hudWidth, this.hudHeight);

        // 3. Meshes that change slowly.
        boolean rebuildClouds = cloudsOn && this.clouds.needsRebuild(CloudMeshCache.shapeAt(worldTime));
        boolean rebuildWeather = weatherOn && this.weather.needsRebuild(raining ? 1 : 0);

        return new Frame(blit, !blit, rebuildClouds, rebuildWeather, cloudsOn, weatherOn);
    }

    /** What one frame should draw. */
    public record Frame(boolean blitHud, boolean rebuildHud, boolean rebuildClouds,
            boolean rebuildWeather, boolean clouds, boolean weather) { }

    /**
     * Batches one frame's GUI elements.
     *
     * <p>Grouped by atlas and blend mode, in the order they were given, because reordering GUI elements changes
     * what is drawn over what.
     */
    public List<GuiBatcher.Batch> batchGui(List<GuiBatcher.Element> elements) {
        return this.gui.batchFrame(elements);
    }

    /**
     * Ends a frame.
     *
     * <p>Nothing here accumulates across frames except the caches, which is the point -- but the HUD texture has
     * to be told when the window changed size, and that comes through the frame call rather than here.
     */
    public void endFrame() {
        this.clouds.frames();
        this.weather.frames();
    }

    /**
     * Invalidates everything, on a world change or a resource reload.
     *
     * <p>They all have to be invalidated at the same moment, because missing one means the player sees a stale
     * HUD over a fresh world, or yesterday's clouds over today's weather.
     */
    public void invalidateAll() {
        this.hudTexture.invalidate();
        this.clouds.invalidate();
        this.weather.invalidate();
        this.icons.clear();
        this.textBuffers.clear();
    }

    public long frames() {
        return this.frames;
    }

    /** Milliseconds the HUD scheduling and texture saved this session. */
    public double msSavedOnHud() {
        return this.hudSchedule.msSaved() + this.hudTexture.msSaved();
    }

    /**
     * Milliseconds the mesh caches saved this session.
     *
     * <p>Each cache reports what a frame costs with it and what one would cost without, so the saving is the
     * difference times the frames drawn. A negative result would mean the cache cost more than it saved, which
     * is possible in principle and is reported rather than clamped, because a cache that is not paying for
     * itself should say so.
     */
    public double msSavedOnMeshes() {
        double cloudSaving = CloudMeshCache.msPerFrameWithoutCache() - this.clouds.msPerFrame();
        double weatherSaving = WeatherMeshCache.msPerFrameWithoutCache() - this.weather.msPerFrame();
        return (cloudSaving + weatherSaving) * this.frames;
    }

    /**
     * The share of the HUD frames that reused the texture.
     *
     * <p>Zero before any frame has been drawn. A runtime that has drawn nothing has technically reused
     * everything, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double hudBlitPercent() {
        return this.hudTexture.blitPercent();
    }
}
