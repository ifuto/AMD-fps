package net.amdfaster.entity;

/**
 * One drawable entity, reduced to the fields a batcher needs.
 *
 * <p>Deliberately not a Minecraft {@code Entity}: the batching decision is worth testing, and it
 * cannot be tested if its input is a live game object. The adapter's job is to turn an
 * {@code ItemEntity} or an armour stand into one of these.
 *
 * <p>{@code y} is the entity's feet, matching Minecraft, so the bounding box runs from {@code y} to
 * {@code y + height}.
 *
 * @param modelKey  identifies the model and texture together. Two entities share a batch only when
 *                  this matches, because a single instanced draw can bind exactly one of each.
 * @param x,y,z     position, world coordinates
 * @param yaw       rotation about Y, radians
 * @param light     packed lightmap coordinate, see {@code LightValue}
 * @param halfWidth half the footprint's width in blocks
 * @param height    height in blocks
 */
public record EntityInstance(
        int modelKey,
        float x, float y, float z,
        float yaw,
        int light,
        float halfWidth, float height
) {

    public EntityInstance {
        if (halfWidth < 0f) {
            throw new IllegalArgumentException("halfWidth must not be negative: " + halfWidth);
        }
        if (height < 0f) {
            throw new IllegalArgumentException("height must not be negative: " + height);
        }
    }

    public float minX() {
        return this.x - this.halfWidth;
    }

    public float maxX() {
        return this.x + this.halfWidth;
    }

    public float minY() {
        return this.y;
    }

    public float maxY() {
        return this.y + this.height;
    }

    public float minZ() {
        return this.z - this.halfWidth;
    }

    public float maxZ() {
        return this.z + this.halfWidth;
    }

    /** The same instance with a different position, for tests that lay out a grid. */
    public EntityInstance at(float newX, float newY, float newZ) {
        return new EntityInstance(this.modelKey, newX, newY, newZ, this.yaw, this.light,
                this.halfWidth, this.height);
    }
}
