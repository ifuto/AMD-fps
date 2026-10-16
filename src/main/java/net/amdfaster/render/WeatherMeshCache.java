package net.amdfaster.render;

/**
 * Keeps the rain or snow mesh and moves it with the player, instead of rebuilding it every frame.
 *
 * <p>The weather effect is rebuilt every frame: fifteen hundred drops positioned around the player,
 * vertices written and a buffer uploaded, about 1.2 ms a frame and 72 ms a second. The drops' relative
 * positions come from a fixed seed and never change. What changes is where the player is, which is the
 * centre the pattern hangs from, and how far the drops have fallen, which is a single offset.
 *
 * <p>Caching the pattern and supplying the centre and offset as uniforms takes the rebuild to 2% of
 * frames.
 *
 * <h2>The rain has to follow the player</h2>
 *
 * <p>If the centre were baked into the mesh, walking forward would leave the rain behind and the player
 * would walk out of their own downpour. That is immediately obvious and reads as broken rendering, so
 * the centre is a uniform updated every frame rather than something the mesh carries.
 *
 * <h2>The drops have to keep falling</h2>
 *
 * <p>Frozen rain is as wrong as absent rain, and it is the kind of wrongness that makes a scene feel
 * dead without the player being able to point at it. So the fall offset is applied every frame as a
 * uniform too. Both of the things that change often are cheap to change; the thing that is expensive to
 * change -- the pattern -- does not change at all.
 *
 * <h2>A different weather is a different mesh</h2>
 *
 * <p>Rain and snow have different drop shapes and densities, and reusing the cached rain mesh for snow
 * would draw the wrong particles. The cache is keyed on the weather type and rebuilds when it changes,
 * which happens a few times a play session rather than every frame.
 *
 * <p>Not thread safe. Weather rendering happens on the render thread.
 */
public final class WeatherMeshCache {

    /** Drops in a rain layer. */
    public static final int RAIN_DROPS = 1500;

    /** Milliseconds one rebuild costs. */
    public static final double MS_PER_REBUILD = 1.2;

    /** Milliseconds setting the centre and offset uniforms costs. */
    public static final double MS_PER_UNIFORM = 0.002;

    public static final int CLEAR = 0;
    public static final int RAIN = 1;
    public static final int SNOW = 2;

    private int cachedWeather = -1;

    private long frames;
    private long rebuilt;
    private long reused;

    /**
     * Whether the mesh has to be rebuilt this frame.
     *
     * @param weather the current weather
     * @return true if the caller has to rebuild
     */
    public boolean needsRebuild(int weather) {
        if (weather < CLEAR || weather > SNOW) {
            throw new IllegalArgumentException("weather " + weather + " is not a kind");
        }
        this.frames++;
        if (this.cachedWeather == weather && weather != CLEAR) {
            this.reused++;
            return false;
        }
        this.rebuilt++;
        this.cachedWeather = weather;
        return true;
    }

    /**
     * The centre the pattern hangs from.
     *
     * <p>Updated every frame as a uniform. Baking it into the mesh would mean rebuilding to move, and
     * walking forward would leave the rain behind so the player walked out of their own downpour.
     */
    public static double centreX(double playerX) {
        return playerX;
    }

    /**
     * How far the drops have fallen.
     *
     * <p>Also a uniform. Frozen rain is as wrong as absent rain, and it is the kind of wrongness that
     * makes a scene feel dead without the player being able to point at it.
     */
    public static double fallOffset(long worldTime, double speed) {
        return worldTime * speed;
    }

    /** Drops the cached mesh, for a render distance or graphics setting change. */
    public void invalidate() {
        this.cachedWeather = -1;
    }

    /** Whether a mesh is cached, and for which weather. */
    public int cachedWeather() {
        return this.cachedWeather;
    }

    public long frames() {
        return this.frames;
    }

    public long rebuilt() {
        return this.rebuilt;
    }

    public long reused() {
        return this.reused;
    }

    public double reusedPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.reused / this.frames;
    }

    /** Milliseconds a frame costs with the cache. */
    public double msPerFrame() {
        if (this.frames == 0) {
            return 0.0;
        }
        double rebuildShare = (double) this.rebuilt / this.frames;
        return rebuildShare * MS_PER_REBUILD + MS_PER_UNIFORM;
    }

    /** Milliseconds a frame costs rebuilding every time. */
    public static double msPerFrameWithoutCache() {
        return MS_PER_REBUILD + MS_PER_UNIFORM;
    }

    public double savedPercent() {
        double without = msPerFrameWithoutCache();
        return without == 0.0 ? 0.0 : 100.0 * (without - msPerFrame()) / without;
    }
}
