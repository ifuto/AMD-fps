package net.amdfaster.mem;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs a block state's properties into one integer instead of a map of boxed entries.
 *
 * <p>FerriteCore's breakdown of a modded client puts BlockState at roughly 970 MB, and 600 MB of that
 * is the per-state map from property to value. Every state of every block carries its own map, and a
 * stair has forty states, a fence has a few hundred, and a modded pack has tens of thousands of block
 * states. A property has a handful of possible values -- facing has six, waterlogged has two -- so the
 * map is a very expensive way to store three bits.
 *
 * <p>This computes a bit layout for a block's properties, once, and then encodes any state as a single
 * integer: facing in three bits, half in one, shape in three, waterlogged in one. A stair's 120 states
 * -- 6 facings times 2 halves times 5 shapes times 2 waterlogged -- fit in eight bits, with room to
 * spare, against a map object per state.
 *
 * <h2>Where the bits go has to be exact</h2>
 *
 * <p>A property with six values needs three bits, not two, and not four. Two would silently wrap the
 * fifth and sixth values onto the first and second, so a stair facing west renders facing north. Four
 * would waste a bit that another property needs, and on a block with enough properties that is the
 * difference between fitting in an int and not fitting at all. The width is
 * {@code ceil(log2(values))}, computed by counting bits rather than by taking a logarithm, because a
 * floating-point log of a power of two can land just below the integer and floor to one bit short.
 *
 * <p>An encoded value at or above the property's value count is rejected rather than masked. Masking
 * would produce a valid-looking integer holding an impossible state, which is the same class of bug as
 * the wrap and just as invisible.
 *
 * <h2>Properties with one value</h2>
 *
 * <p>They take zero bits. A property that can only be one thing carries no information, and giving it a
 * bit would waste one that a real property needs. Reading it back always yields zero, which decodes to
 * the only value it can have.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not replace the map in Minecraft's BlockState. That would need a mixin over a class this
 * project does not own the source of, and getting it wrong would break every other mod that reads
 * block states. What this provides is the layout and the encoding, so a caller that can reach the state
 * can use it, and the arithmetic is already tested rather than being derived at the call site.
 *
 * <p>Not thread safe while properties are being registered. Encoding and decoding after the layout is
 * fixed are read-only.
 */
public final class BlockStatePacker {

    /** Bits available in the packed representation. */
    public static final int BITS = 32;

    private final List<String> names = new ArrayList<>();
    private final List<Integer> valueCounts = new ArrayList<>();
    private final List<Integer> offsets = new ArrayList<>();
    private final List<Integer> widths = new ArrayList<>();

    private int totalBits;
    private boolean fixed;

    /**
     * Adds a property.
     *
     * @param name the property's name, for diagnostics
     * @param valueCount how many values it can take; at least 1
     * @return the property's index
     */
    public int addProperty(String name, int valueCount) {
        if (this.fixed) {
            throw new IllegalStateException("the layout is already fixed");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a property has a name");
        }
        if (valueCount < 1) {
            throw new IllegalArgumentException(
                    "a property has at least one value: " + name + " has " + valueCount);
        }
        int width = bitsFor(valueCount);
        if (this.totalBits + width > BITS) {
            throw new IllegalArgumentException(
                    "property " + name + " needs " + width + " bits and only "
                            + (BITS - this.totalBits) + " remain; this block's states do not fit"
                            + " in " + BITS + " bits");
        }
        int index = this.names.size();
        this.names.add(name);
        this.valueCounts.add(valueCount);
        this.offsets.add(this.totalBits);
        this.widths.add(width);
        this.totalBits += width;
        return index;
    }

    /**
     * Bits needed for a number of values.
     *
     * <p>Counted, not logged: {@code Math.ceil(Math.log(8) / Math.log(2))} can come out just under 3
     * and floor to 2, which would silently wrap the last two values onto the first two.
     */
    public static int bitsFor(int valueCount) {
        if (valueCount < 1) {
            throw new IllegalArgumentException("a property has at least one value: " + valueCount);
        }
        if (valueCount == 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(valueCount - 1);
    }

    /** Locks the layout. After this, properties cannot be added. */
    public void fix() {
        this.fixed = true;
    }

    /**
     * Encodes a state.
     *
     * @param values one value index per property, in registration order
     * @return the packed state
     */
    public int encode(int[] values) {
        checkFixed();
        if (values.length != this.names.size()) {
            throw new IllegalArgumentException(
                    "expected " + this.names.size() + " values: " + values.length);
        }
        int packed = 0;
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            int count = this.valueCounts.get(i);
            if (value < 0 || value >= count) {
                // Rejected rather than masked. Masking would produce a valid-looking integer holding an
                // impossible state, which is the same bug as a too-narrow field and just as invisible.
                throw new IllegalArgumentException(
                        "property " + this.names.get(i) + " has " + count
                                + " values, not " + value);
            }
            packed |= value << this.offsets.get(i);
        }
        return packed;
    }

    /**
     * Reads one property back out of a packed state.
     *
     * @param packed the packed state
     * @param property the property's index
     */
    public int decode(int packed, int property) {
        checkFixed();
        if (property < 0 || property >= this.names.size()) {
            throw new IndexOutOfBoundsException(
                    "property " + property + " outside " + this.names.size());
        }
        int width = this.widths.get(property);
        if (width == 0) {
            return 0;
        }
        int mask = (1 << width) - 1;
        return (packed >>> this.offsets.get(property)) & mask;
    }

    /** Reads every property back out. */
    public int[] decodeAll(int packed) {
        checkFixed();
        int[] out = new int[this.names.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = decode(packed, i);
        }
        return out;
    }

    /** How many states this block has, which is the product of its property value counts. */
    public int stateCount() {
        int n = 1;
        for (int count : this.valueCounts) {
            n *= count;
        }
        return n;
    }

    /** Bits the packed state uses. */
    public int totalBits() {
        return this.totalBits;
    }

    public int propertyCount() {
        return this.names.size();
    }

    /** A property's width in bits. */
    public int widthOf(int property) {
        if (property < 0 || property >= this.names.size()) {
            throw new IndexOutOfBoundsException(
                    "property " + property + " outside " + this.names.size());
        }
        return this.widths.get(property);
    }

    /** A property's name. */
    public String nameOf(int property) {
        if (property < 0 || property >= this.names.size()) {
            throw new IndexOutOfBoundsException(
                    "property " + property + " outside " + this.names.size());
        }
        return this.names.get(property);
    }

    /**
     * Whether every reachable state fits, which is whether the bit layout is wide enough.
     *
     * <p>The layout can have room the states never use -- three bits for six values leaves two
     * encodings unused -- and that is fine. What is not fine is the reverse, and {@link #addProperty}
     * refuses it.
     */
    public boolean fitsAllStates() {
        return this.stateCount() <= (1L << this.totalBits)
                || this.totalBits == 0 && this.stateCount() == 1;
    }

    /**
     * Bytes a state occupies packed, against a map object holding the same information.
     *
     * @param mapEntryBytes what one map entry costs, for comparison
     */
    public long bytesSavedPerState(int mapEntryBytes) {
        return (long) this.names.size() * mapEntryBytes - 4;
    }

    private void checkFixed() {
        if (!this.fixed) {
            throw new IllegalStateException("call fix() before encoding or decoding");
        }
    }
}
