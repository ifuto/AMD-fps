package net.amdfaster.entity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Names the entities that must be drawn even when occlusion culling says they are hidden.
 *
 * <p>Occlusion culling removes entities that are behind terrain, and for almost everything that is the
 * right answer. But some things are meant to be seen through the world rather than in it. A beacon's beam
 * is visible from behind a hill by design; a boss's health bar is on screen whether or not the boss is.
 * Culling those does not save the player any confusion -- it removes a signal the game intends to give,
 * and the player experiences it as a rendering bug rather than as an optimisation.
 *
 * <h2>Exclusion is a list, not a guess</h2>
 *
 * <p>The alternative would be to infer which entities matter -- anything glowing, anything large, anything
 * with a boss bar -- and every such rule is wrong for some entity in some mod. A list is explicit: it says
 * what is excluded, it can be read, and when something is missing from the world view the answer is in one
 * place. Guessing would put the answer in a heuristic that is hard to inspect and impossible to correct
 * without changing the code.
 *
 * <h2>The default is empty</h2>
 *
 * <p>Nothing is excluded until something is added. A culling optimisation that starts by assuming some
 * entities are special has to be right about every one of them from the first frame; one that starts
 * excluding nothing is simply culling, and the exclusions are additions someone deliberately made. If a
 * mod's entity should be excluded and is not, the fix is to add it -- not to discover that the default
 * was quietly guessing on its behalf.
 *
 * <h2>Why an excluded entity is drawn unconditionally</h2>
 *
 * <p>It bypasses the cull entirely rather than being given a more generous test. A wider frustum or a
 * shorter occlusion distance would still hide a beacon behind enough terrain, which defeats the reason it
 * was excluded. The point of excluding something is that its visibility is not a question the culler
 * should answer.
 *
 * <p>Thread safe for reads: culling runs on worker threads while the list is written from the client
 * thread. The set is only ever replaced, never mutated in place.
 */
public final class CullingExclusionList {

    /** Milliseconds one occlusion test costs. */
    public static final double MS_PER_TEST = 0.004;

    private volatile Set<String> excluded = Collections.emptySet();

    private long tests;
    private long bypassed;
    private long culled;
    private long drawn;

    /**
     * Adds an entity type to the exclusion list.
     *
     * @param type the entity type id, such as {@code minecraft:beacon_beam}
     */
    public void exclude(String type) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("an entity type is required");
        }
        // Replaced rather than mutated, so a culling thread reading the set never sees a half-built one.
        Set<String> next = new HashSet<>(this.excluded);
        next.add(type);
        this.excluded = Collections.unmodifiableSet(next);
    }

    /** Removes an entity type from the exclusion list. */
    public void include(String type) {
        Set<String> next = new HashSet<>(this.excluded);
        if (next.remove(type)) {
            this.excluded = Collections.unmodifiableSet(next);
        }
    }

    /** Whether an entity type is excluded from occlusion culling. */
    public boolean isExcluded(String type) {
        return this.excluded.contains(type);
    }

    /** How many entity types are excluded. */
    public int size() {
        return this.excluded.size();
    }

    /**
     * Whether an entity should be drawn.
     *
     * @param type the entity type id
     * @param occluded whether the occlusion test found it hidden behind terrain
     * @return true if it should be drawn
     */
    public boolean shouldDraw(String type, boolean occluded) {
        this.tests++;

        if (isExcluded(type)) {
            // Unconditional, not a more generous test. A wider frustum or a shorter occlusion distance
            // would still hide a beacon behind enough terrain, which defeats the reason it was excluded.
            if (occluded) {
                // Only an occluded one was actually rescued. An excluded entity that was visible anyway
                // was never going to be culled, and counting it here would claim credit for work the
                // culler did not avoid.
                this.bypassed++;
            } else {
                this.drawn++;
            }
            return true;
        }
        if (occluded) {
            this.culled++;
            return false;
        }
        this.drawn++;
        return true;
    }

    public long tests() {
        return this.tests;
    }

    /** How many entities were drawn despite being occluded, because they are excluded. */
    public long bypassed() {
        return this.bypassed;
    }

    /** How many were culled. */
    public long culled() {
        return this.culled;
    }

    /** How many were drawn normally. */
    public long drawn() {
        return this.drawn;
    }

    public double culledPercent() {
        return this.tests == 0 ? 0.0 : 100.0 * this.culled / this.tests;
    }

    /** Milliseconds of occlusion testing avoided per frame. */
    public double msSavedPerFrame() {
        return this.culled * MS_PER_TEST;
    }
}
