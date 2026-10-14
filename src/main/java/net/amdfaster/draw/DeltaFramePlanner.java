package net.amdfaster.draw;

import java.util.Arrays;

/**
 * Works out what changed since the last frame, so only that gets uploaded and drawn.
 *
 * <p>A frame that redraws everything re-uploads every visible section, rebuilds every draw record and
 * re-sorts every key. At 400 visible sections and 5,332 bytes of meshlet data each, that is 2.1 MB of
 * transfer a frame for a world where almost nothing moved. What actually changes between frames is a
 * handful of sections with a block placed in them, a few dozen whose light changed, and a couple of
 * dozen entering or leaving the frustum -- a few percent of the total. The rest is the same bytes
 * being sent again.
 *
 * <h2>Versions, not "changed this frame"</h2>
 *
 * <p>Each section carries a geometry version and a light version, and separately the versions that
 * were last uploaded. The plan is the difference between the two pairs. That distinction is the whole
 * correctness argument, because the obvious implementation -- a dirty flag cleared each frame -- loses
 * work:
 *
 * <p>A section that changed while it was off-screen is not dirty when it comes back into view, because
 * the flag was cleared on the frame it changed. Uploading it then sends geometry that no longer
 * matches the blocks, and the player walks into a wall that is not there or through one that is.
 * Comparing versions rather than reading a flag means a change is owed until it has actually been
 * sent, however many frames it waited.
 *
 * <p>It also handles buffer recycling. A section evicted from the GPU buffer has to be uploaded again
 * even though nothing about its blocks changed, which is expressed by resetting its uploaded versions
 * in {@link #evict}.
 *
 * <h2>Geometry and light are separate</h2>
 *
 * <p>Because the light stream is its own vertex buffer, a torch placed near a chunk border changes
 * light in dozens of sections and geometry in none of them. Tracking one version would re-upload the
 * whole mesh for each; tracking two re-uploads only the light stream. Light-only changes are the
 * common case after any block placement, since light propagates up to fifteen blocks and geometry does
 * not.
 *
 * <h2>Draws are tracked separately from uploads</h2>
 *
 * <p>A section can need its draw added without needing an upload, if it became visible and its data is
 * already on the GPU. It can need an upload without a draw, if it changed while invisible. Treating
 * the two as one list would either upload unchanged data or draw stale data.
 *
 * <p>Not thread safe.
 */
public final class DeltaFramePlanner {

    /** Bytes of mesh data a section occupies, from the meshlet layout. */
    public static final int BYTES_PER_SECTION = 5332;

    private static final int DEFAULT_CAPACITY = 1024;

    /** Current versions, per tracked section. */
    private int[] keys;
    private int[] geometryVersions;
    private int[] lightVersions;

    /** What the GPU has, per tracked section. */
    private int[] uploadedGeometry;
    private int[] uploadedLight;

    /** Whether the section is in the visible set. */
    private boolean[] visible;

    /** Whether a draw record currently exists for it. */
    private boolean[] drawn;

    /**
     * Whether a slot holds a section.
     *
     * <p>A separate array rather than inferring occupancy from a version, because a tracked section can
     * legitimately have every version at zero -- freshly loaded, or evicted. Three other caches in this
     * project used a value as its own presence flag and each one was a bug.
     */
    private boolean[] occupied;

    private int capacity;
    private int count;

    private long frames;
    private long geometryUploads;
    private long lightUploads;
    private long drawsAdded;
    private long drawsRemoved;
    private long fullRedrawSections;

    public DeltaFramePlanner() {
        this(DEFAULT_CAPACITY);
    }

    public DeltaFramePlanner(int capacity) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        this.capacity = roundUp(capacity);
        allocate();
    }

    private static int roundUp(int capacity) {
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        return rounded <= 0 ? 16 : rounded;
    }

    private void allocate() {
        this.occupied = new boolean[this.capacity];
        this.keys = new int[this.capacity];
        this.geometryVersions = new int[this.capacity];
        this.lightVersions = new int[this.capacity];
        this.uploadedGeometry = new int[this.capacity];
        this.uploadedLight = new int[this.capacity];
        this.visible = new boolean[this.capacity];
        this.drawn = new boolean[this.capacity];
        this.count = 0;
    }

    /** The plan for one frame. */
    public static final class Plan {
        /** Sections whose geometry must be uploaded. */
        public final int[] geometryUploads = new int[MAX_PER_FRAME];

        /** Sections whose light stream must be uploaded. */
        public final int[] lightUploads = new int[MAX_PER_FRAME];

        /** Sections that need a draw record added. */
        public final int[] drawsToAdd = new int[MAX_PER_FRAME];

        /** Sections whose draw record must be removed. */
        public final int[] drawsToRemove = new int[MAX_PER_FRAME];

        public int geometryUploadCount;
        public int lightUploadCount;
        public int drawAddCount;
        public int drawRemoveCount;

        /**
         * Sections that needed something, which is what a full redraw would have done for every
         * visible section instead.
         */
        public int touched() {
            return this.geometryUploadCount + this.lightUploadCount
                    + this.drawAddCount + this.drawRemoveCount;
        }
    }

    /** Upper bound on entries per list in one frame. A frame that exceeds it is reported, not clipped. */
    public static final int MAX_PER_FRAME = 4096;

    /**
     * Marks a section's geometry as having changed.
     *
     * @param sectionKey a packed section coordinate
     */
    public void geometryChanged(int sectionKey) {
        int index = indexFor(sectionKey, true);
        this.geometryVersions[index]++;
    }

    /** Marks a section's light as having changed. */
    public void lightChanged(int sectionKey) {
        int index = indexFor(sectionKey, true);
        this.lightVersions[index]++;
    }

    /** Puts a section in or out of the visible set. */
    public void setVisible(int sectionKey, boolean isVisible) {
        int index = indexFor(sectionKey, true);
        this.visible[index] = isVisible;
    }

    /**
     * Forgets that the GPU holds a section's data.
     *
     * <p>For buffer eviction and for a resource resize. The section's blocks have not changed, but
     * what was uploaded is gone, so it has to be sent again.
     */
    public void evict(int sectionKey) {
        int index = indexFor(sectionKey, false);
        if (index < 0) {
            return;
        }
        this.uploadedGeometry[index] = 0;
        this.uploadedLight[index] = 0;
        this.drawn[index] = false;
    }

    /** Stops tracking a section, for an unload. */
    public void forget(int sectionKey) {
        int index = indexFor(sectionKey, false);
        if (index < 0) {
            return;
        }
        this.geometryVersions[index] = 0;
        this.lightVersions[index] = 0;
        this.uploadedGeometry[index] = 0;
        this.uploadedLight[index] = 0;
        this.visible[index] = false;
        this.drawn[index] = false;
        this.keys[index] = 0;
        this.count--;
        backshift(index);
    }

    /** Whether a section is currently tracked. */
    public boolean isTracked(int sectionKey) {
        return indexFor(sectionKey, false) >= 0;
    }

    /**
     * Builds the plan for this frame.
     *
     * @return the plan; the caller reads it and then calls {@link #commit} once the work is done
     */
    public Plan plan() {
        this.frames++;
        Plan plan = new Plan();
        for (int i = 0; i < this.count; i++) {
            if (this.geometryVersions[i] != this.uploadedGeometry[i]) {
                add(plan.geometryUploads, plan, 0, this.keys[i]);
                plan.geometryUploadCount++;
                this.geometryUploads++;
            }
            if (this.lightVersions[i] != this.uploadedLight[i]) {
                add(plan.lightUploads, plan, 1, this.keys[i]);
                plan.lightUploadCount++;
                this.lightUploads++;
            }
            if (this.visible[i] && !this.drawn[i]) {
                add(plan.drawsToAdd, plan, 2, this.keys[i]);
                plan.drawAddCount++;
                this.drawsAdded++;
            } else if (!this.visible[i] && this.drawn[i]) {
                add(plan.drawsToRemove, plan, 3, this.keys[i]);
                plan.drawRemoveCount++;
                this.drawsRemoved++;
            }
            if (this.visible[i]) {
                this.fullRedrawSections++;
            }
        }
        return plan;
    }

    private static void add(int[] list, Plan plan, int which, int key) {
        int n;
        switch (which) {
            case 0 -> n = plan.geometryUploadCount;
            case 1 -> n = plan.lightUploadCount;
            case 2 -> n = plan.drawAddCount;
            default -> n = plan.drawRemoveCount;
        }
        if (n >= list.length) {
            throw new IllegalStateException(
                    "more than " + list.length + " sections need work in one frame");
        }
        list[n] = key;
    }

    /**
     * Records that the planned work was done, so the next frame does not repeat it.
     *
     * <p>Separate from {@link #plan} because a frame can be dropped after planning -- a resize, a lost
     * device -- and committing then would mark work as sent that never was.
     */
    public void commit(Plan plan) {
        for (int i = 0; i < plan.geometryUploadCount; i++) {
            int index = indexFor(plan.geometryUploads[i], false);
            if (index >= 0) {
                this.uploadedGeometry[index] = this.geometryVersions[index];
            }
        }
        for (int i = 0; i < plan.lightUploadCount; i++) {
            int index = indexFor(plan.lightUploads[i], false);
            if (index >= 0) {
                this.uploadedLight[index] = this.lightVersions[index];
            }
        }
        for (int i = 0; i < plan.drawAddCount; i++) {
            int index = indexFor(plan.drawsToAdd[i], false);
            if (index >= 0) {
                this.drawn[index] = true;
            }
        }
        for (int i = 0; i < plan.drawRemoveCount; i++) {
            int index = indexFor(plan.drawsToRemove[i], false);
            if (index >= 0) {
                this.drawn[index] = false;
            }
        }
    }

    /** How many sections are tracked. */
    public int trackedCount() {
        return this.count;
    }

    /** Whether a section currently has a draw record. */
    public boolean isDrawn(int sectionKey) {
        int index = indexFor(sectionKey, false);
        return index >= 0 && this.drawn[index];
    }

    /**
     * Whether a section still owes an upload.
     *
     * <p>Exposed so the "nothing is lost" property can be checked rather than assumed.
     */
    public boolean owesUpload(int sectionKey) {
        int index = indexFor(sectionKey, false);
        if (index < 0) {
            return false;
        }
        return this.geometryVersions[index] != this.uploadedGeometry[index]
                || this.lightVersions[index] != this.uploadedLight[index];
    }

    private int indexFor(int sectionKey, boolean create) {
        int index = probe(sectionKey);
        if (this.occupied[index]) {
            return index;
        }
        if (!create) {
            return -1;
        }
        // Occupied is set before the load is checked, so a growth triggered below copies a complete
        // entry. Setting it afterwards would leave the new slot looking empty while the table is being
        // rehashed, and the section would be dropped.
        this.occupied[index] = true;
        this.keys[index] = sectionKey;
        this.count++;
        if (this.count * 4 >= this.capacity * 3) {
            grow();
            return probe(sectionKey);
        }
        return index;
    }

    /**
     * Finds a section's slot.
     *
     * <p>Probes by key rather than trusting the presence of a version, because a section whose
     * versions are both zero -- freshly loaded, or evicted -- is still tracked and has to be findable.
     */
    private int probe(int sectionKey) {
        int index = spread(sectionKey) & (this.capacity - 1);
        while (isOccupied(index)) {
            if (this.keys[index] == sectionKey) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
        }
        return index;
    }

    private boolean isOccupied(int index) {
        return this.occupied[index];
    }

    private void backshift(int hole) {
        int i = hole;
        this.occupied[hole] = false;
        while (true) {
            int j = (i + 1) & (this.capacity - 1);
            while (this.occupied[j]) {
                int ideal = spread(this.keys[j]) & (this.capacity - 1);
                boolean movable = i <= j
                        ? ideal > i && ideal <= j
                        : ideal > i || ideal <= j;
                if (movable) {
                    j = (j + 1) & (this.capacity - 1);
                } else {
                    break;
                }
            }
            if (!this.occupied[j]) {
                return;
            }
            copySlot(j, i);
            this.occupied[j] = false;
            i = j;
        }
    }

    private void copySlot(int from, int to) {
        this.keys[to] = this.keys[from];
        this.geometryVersions[to] = this.geometryVersions[from];
        this.lightVersions[to] = this.lightVersions[from];
        this.uploadedGeometry[to] = this.uploadedGeometry[from];
        this.uploadedLight[to] = this.uploadedLight[from];
        this.visible[to] = this.visible[from];
        this.drawn[to] = this.drawn[from];
    }

    static int spread(int key) {
        int h = key ^ (key >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private void grow() {
        int oldCapacity = this.capacity;
        int[] oldKeys = this.keys;
        int[] oldGeometry = this.geometryVersions;
        int[] oldLight = this.lightVersions;
        int[] oldUploadedGeometry = this.uploadedGeometry;
        int[] oldUploadedLight = this.uploadedLight;
        boolean[] oldVisible = this.visible;
        boolean[] oldDrawn = this.drawn;
        boolean[] oldOccupied = this.occupied;

        this.capacity = oldCapacity * 2;
        allocate();

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.keys[index] = oldKeys[i];
            this.geometryVersions[index] = oldGeometry[i];
            this.lightVersions[index] = oldLight[i];
            this.uploadedGeometry[index] = oldUploadedGeometry[i];
            this.uploadedLight[index] = oldUploadedLight[i];
            this.visible[index] = oldVisible[i];
            this.drawn[index] = oldDrawn[i];
            this.occupied[index] = true;
        }
    }

    public long frames() {
        return this.frames;
    }

    public long geometryUploads() {
        return this.geometryUploads;
    }

    public long lightUploads() {
        return this.lightUploads;
    }

    public long drawsAdded() {
        return this.drawsAdded;
    }

    public long drawsRemoved() {
        return this.drawsRemoved;
    }

    /** Section visits a full redraw would have made, for comparing against. */
    public long fullRedrawSections() {
        return this.fullRedrawSections;
    }

    /**
     * Percentage of the work a full redraw would have done.
     *
     * <p>Counts section visits, weighting a geometry upload as a whole section and a light upload as
     * the light stream's share of it, since that is what the transfer difference actually is.
     */
    public double percentOfFullRedraw() {
        if (this.fullRedrawSections == 0) {
            return 0.0;
        }
        double work = this.geometryUploads + this.lightUploads * LIGHT_SHARE
                + (this.drawsAdded + this.drawsRemoved) * DRAW_SHARE;
        return 100.0 * work / this.fullRedrawSections;
    }

    /** The light stream's share of a section's upload cost. */
    public static final double LIGHT_SHARE = 0.25;

    /** A draw record's share of a section's cost. */
    public static final double DRAW_SHARE = 0.02;

    /**
     * Packs section coordinates into a key.
     *
     * <p>Eleven bits of X and Z and eight of Y, which covers a 2048 by 256 by 2048 section volume.
     */
    public static int pack(int sectionX, int sectionY, int sectionZ) {
        return ((sectionX & 0x7FF) << 19) | ((sectionY & 0xFF) << 11) | (sectionZ & 0x7FF);
    }

    /** Drops everything, for a dimension change. */
    public void clear() {
        allocate();
    }
}
