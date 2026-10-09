package net.amdfaster.particle;

/**
 * Particles held as parallel primitive arrays, with culling and an overdraw estimate.
 *
 * <p>What this is for. A TNT explosion spawns hundreds of particles in one tick, and a chain of them
 * thousands. The instinct is that the cost is draw calls, so the fix is batching. It usually is not.
 * Particles are alpha blended, which disables the early depth test, so every particle pixel is shaded
 * whether or not something is already drawn there. A thousand particles behind a wall covering a few
 * pixels cost almost nothing; a hundred particles filling the screen cost a great deal. The number
 * that matters is pixels shaded, and the only lever on the CPU side is not drawing the ones that are
 * not going to be seen.
 *
 * <p>So this does three things:
 *
 * <p><b>Structure of arrays.</b> Position, velocity, age and sprite each in their own array. Updating
 * a particle touches one value per array, and the update walks every particle, so this layout keeps
 * each pass reading one contiguous run instead of striding across a record.
 *
 * <p><b>Culling before anything else.</b> A particle is tested against the view once per frame and
 * dropped from the draw list if it fails. This is where the saving is, and it is proportional to how
 * much of an explosion is off screen or behind terrain -- which for an explosion the player is running
 * away from is most of it.
 *
 * <p><b>An overdraw estimate.</b> The sum of screen-space quad area, reported so the cost can be
 * reasoned about rather than guessed. If a frame is slow and this number is large, the answer is fewer
 * or smaller particles, not a faster batcher.
 *
 * <p>Dead particles are removed by swapping with the last live one. Order does not matter here because
 * the draw order is decided later by the transparency sort; preserving insertion order in the store
 * would cost a compaction pass for nothing.
 *
 * <p>Not thread safe.
 */
public final class ParticleField {

    /** Gravity applied per second squared, in blocks. */
    public static final float DEFAULT_GRAVITY = 9.81f;

    /** Fraction of velocity retained per second of air drag. */
    public static final float DEFAULT_DRAG = 0.98f;

    private final int capacity;

    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final float[] velocityX;
    private final float[] velocityY;
    private final float[] velocityZ;
    private final float[] age;
    private final float[] lifetime;
    private final float[] size;
    private final int[] sprite;

    private int count;
    private int visibleCount;

    /** Indices of the particles that passed culling, reused across frames. */
    private int[] drawList;

    private long spawned;
    private long expired;
    private long culled;
    private double overdrawPixels;

    public ParticleField(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.x = new float[capacity];
        this.y = new float[capacity];
        this.z = new float[capacity];
        this.velocityX = new float[capacity];
        this.velocityY = new float[capacity];
        this.velocityZ = new float[capacity];
        this.age = new float[capacity];
        this.lifetime = new float[capacity];
        this.size = new float[capacity];
        this.sprite = new int[capacity];
        this.drawList = new int[Math.min(capacity, 256)];
    }

    /** Sized for a heavy scene: several simultaneous explosions plus ambient weather particles. */
    public static ParticleField forClient() {
        return new ParticleField(16384);
    }

    public int capacity() {
        return this.capacity;
    }

    public int count() {
        return this.count;
    }

    public boolean isFull() {
        return this.count == this.capacity;
    }

    public long spawned() {
        return this.spawned;
    }

    public long expired() {
        return this.expired;
    }

    /** Particles rejected by the last {@link #cull}. */
    public long culled() {
        return this.culled;
    }

    /** Particles that passed the last {@link #cull}. */
    public int visibleCount() {
        return this.visibleCount;
    }

    /** Screen pixels shaded by the visible particles in the last {@link #cull}. */
    public double overdrawPixels() {
        return this.overdrawPixels;
    }

    /**
     * Adds a particle.
     *
     * <p>Randomness belongs to the caller and happens here, once. A particle re-randomised every frame
     * instead of integrated from a stored velocity costs a random number per frame for something a
     * multiply-add would do, and it flickers.
     *
     * @return false if the field is full, in which case the particle is dropped. Dropping is the right
     *         answer for a particle: the alternative is an unbounded allocation or evicting a live one,
     *         and an explosion that spawns more than the budget allows should lose the excess rather
     *         than cost frame time.
     */
    public boolean spawn(float px, float py, float pz, float vx, float vy, float vz, float lifetimeSeconds,
            float quadSize, int spriteId) {
        if (this.count == this.capacity || lifetimeSeconds <= 0.0f) {
            return false;
        }
        int i = this.count++;
        this.x[i] = px;
        this.y[i] = py;
        this.z[i] = pz;
        this.velocityX[i] = vx;
        this.velocityY[i] = vy;
        this.velocityZ[i] = vz;
        this.age[i] = 0.0f;
        this.lifetime[i] = lifetimeSeconds;
        this.size[i] = quadSize;
        this.sprite[i] = spriteId;
        this.spawned++;
        return true;
    }

    /**
     * Integrates every particle and drops the expired ones.
     *
     * <p>Semi-implicit Euler: velocity is updated first, then position from the new velocity. The
     * explicit form -- position from the old velocity -- loses energy and makes particles fall visibly
     * slower than they should at low frame rates, which is exactly when the difference shows.
     *
     * @return how many particles expired
     */
    public int advance(float deltaTime, float gravity) {
        if (deltaTime <= 0.0f) {
            return 0;
        }
        // Drag is quoted per second, so it has to be raised to the frame's fraction of a second.
        // Applying the per-second factor once per frame makes particles slow down faster at high frame
        // rates, which is a visible difference between a 60 Hz and a 240 Hz machine.
        float drag = (float) Math.pow(DEFAULT_DRAG, deltaTime);
        int expiredThisFrame = 0;
        int i = 0;
        while (i < this.count) {
            this.age[i] += deltaTime;
            if (this.age[i] >= this.lifetime[i]) {
                removeAt(i);
                expiredThisFrame++;
                continue;
            }
            this.velocityY[i] -= gravity * deltaTime;
            this.velocityX[i] *= drag;
            this.velocityY[i] *= drag;
            this.velocityZ[i] *= drag;
            this.x[i] += this.velocityX[i] * deltaTime;
            this.y[i] += this.velocityY[i] * deltaTime;
            this.z[i] += this.velocityZ[i] * deltaTime;
            i++;
        }
        this.expired += expiredThisFrame;
        return expiredThisFrame;
    }

    /**
     * Drops a particle by moving the last one into its slot.
     *
     * <p>Constant time, and it does not preserve order. That is fine because draw order comes from the
     * transparency sort later; keeping insertion order here would need a shifting pass over the rest of
     * the array for no benefit.
     */
    private void removeAt(int index) {
        int last = --this.count;
        if (index != last) {
            this.x[index] = this.x[last];
            this.y[index] = this.y[last];
            this.z[index] = this.z[last];
            this.velocityX[index] = this.velocityX[last];
            this.velocityY[index] = this.velocityY[last];
            this.velocityZ[index] = this.velocityZ[last];
            this.age[index] = this.age[last];
            this.lifetime[index] = this.lifetime[last];
            this.size[index] = this.size[last];
            this.sprite[index] = this.sprite[last];
        }
    }

    /** Whether a particle's bounds can be seen. */
    public interface VisibilityTest {
        boolean isVisible(float centerX, float centerY, float centerZ, float radius);
    }

    /**
     * Builds the draw list and estimates overdraw.
     *
     * @param pixelsPerBlock how many screen pixels one block spans at the particle's distance, which is
     *                       what turns a world-space quad into a pixel count
     * @return the number of visible particles
     */
    public int cull(VisibilityTest test, float pixelsPerBlock) {
        if (this.drawList.length < this.count) {
            this.drawList = new int[this.count];
        }
        int visible = 0;
        double pixels = 0.0;
        long rejected = 0;
        for (int i = 0; i < this.count; i++) {
            float radius = this.size[i] * 0.5f;
            if (!test.isVisible(this.x[i], this.y[i], this.z[i], radius)) {
                rejected++;
                continue;
            }
            this.drawList[visible++] = i;
            // The quad is size x size in world units, so its screen area is the square of its span in
            // pixels. Summed over the visible particles this is the number of fragment invocations the
            // frame owes, and because particles do not write depth, none of it is saved by early Z.
            double span = (double) this.size[i] * pixelsPerBlock;
            pixels += span * span;
        }
        this.visibleCount = visible;
        this.culled = rejected;
        this.overdrawPixels = pixels;
        return visible;
    }

    /** The draw list built by the last {@link #cull}. Valid until the next cull. */
    public int[] drawList() {
        return this.drawList;
    }

    public float xOf(int index) {
        return this.x[index];
    }

    public float yOf(int index) {
        return this.y[index];
    }

    public float zOf(int index) {
        return this.z[index];
    }

    /**
     * Vertical velocity.
     *
     * <p>Exposed because stretched billboards need it, and because the drag calculation is only
     * checkable against a value rather than against a position delta that includes one further frame of
     * gravity.
     */
    public float velocityYOf(int index) {
        return this.velocityY[index];
    }

    public float sizeOf(int index) {
        return this.size[index];
    }

    public int spriteOf(int index) {
        return this.sprite[index];
    }

    /**
     * How far along its life a particle is, 0 to 1.
     *
     * <p>Exposed because fading and shrinking are driven by it, and computing it in the shader would
     * mean uploading the lifetime as well.
     */
    public float progressOf(int index) {
        float life = this.lifetime[index];
        return life <= 0.0f ? 1.0f : Math.min(1.0f, this.age[index] / life);
    }

    /** Bytes of storage. A struct-of-arrays layout's size is worth stating. */
    public int bytesHeld() {
        return this.capacity * (9 * Float.BYTES + Integer.BYTES);
    }

    public void clear() {
        this.count = 0;
        this.visibleCount = 0;
        this.culled = 0;
        this.overdrawPixels = 0.0;
    }
}
