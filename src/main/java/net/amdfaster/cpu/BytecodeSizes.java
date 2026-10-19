package net.amdfaster.cpu;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a method's bytecode size out of its own class file, so inlining decisions are made against the
 * number the compiler will see instead of a number someone wrote down.
 *
 * <p>{@link InliningBudget} compares a bytecode size against HotSpot's limits. Every threshold in it is
 * public and stable, but the size it is compared against is not something the running program can ask the
 * JVM for: there is no API that reports how many bytes a method's {@code Code} attribute occupies. The
 * alternative is to guess, and a guess is worse than useless here -- the whole point of the check is to
 * find out whether a method that <em>looks</em> small is small, and 35 bytes is few enough that a
 * judgement call from the source is wrong as often as it is right.
 *
 * <p>So this reads the class file. It is already loaded and already on the classpath, at
 * {@code /} + the binary name + {@code .class}, and the {@code Code} attribute is a fixed offset into it:
 * skip the magic, the constant pool, the field table and the method table, and the fourth byte of the
 * {@code Code} attribute's body is a big-endian {@code u4} code length. Parsing the constant pool is the
 * only part that needs care, because two of its entry shapes -- {@code Long} and {@code Double} -- occupy
 * two slots, and skipping one of them wrong puts every following index out of step.
 *
 * <p>What this makes possible is checking the mod's own hot methods, and Minecraft's, without a profiler
 * and without running the game: {@code methodBytes(SectionMesher.class, "mesh")} is the same number C2
 * will compare against {@code FreqInlineSize}.
 *
 * <p>This is a read-only walk of a byte array. It never loads the class, never triggers static
 * initialisation, and does not need the class to be one this mod owns.
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html">The Java Virtual Machine
 *      Specification, Java SE 21 Edition, Chapter 4: The class File Format -- {@code cp_info} sizes,
 *      {@code method_info}, and the {@code Code} attribute whose body begins
 *      {@code max_stack}, {@code max_locals}, {@code code_length}</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html">The java Command,
 *      JDK 21 -- the inlining limits this measurement is compared against</a>
 */
public final class BytecodeSizes {

    /** Returned when the size could not be measured, so that a caller cannot mistake it for "small". */
    public static final int UNMEASURED = -1;

    private static final int MAGIC = 0xCAFEBABE;

    /** {@code cp_info} tags, from the class file format. Only their sizes matter here. */
    private static final int TAG_UTF8 = 1;
    private static final int TAG_INTEGER = 3;
    private static final int TAG_FLOAT = 4;
    private static final int TAG_LONG = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_FIELDREF = 9;
    private static final int TAG_METHODREF = 10;
    private static final int TAG_INTERFACE_METHODREF = 11;
    private static final int TAG_NAME_AND_TYPE = 12;
    private static final int TAG_METHOD_HANDLE = 15;
    private static final int TAG_METHOD_TYPE = 16;
    private static final int TAG_DYNAMIC = 17;
    private static final int TAG_INVOKE_DYNAMIC = 18;
    private static final int TAG_MODULE = 19;
    private static final int TAG_PACKAGE = 20;

    private static final String CODE_ATTRIBUTE = "Code";

    private BytecodeSizes() {
    }

    /**
     * One method's measured body.
     *
     * @param name       the method's name, or {@code <init>} / {@code <clinit>} for a constructor or
     *                   static initialiser
     * @param descriptor the JVM descriptor, which is what distinguishes overloads
     * @param bytes      the {@code Code} attribute's {@code code_length}, which is what the inlining
     *                   limits are compared against
     */
    public record Method(String name, String descriptor, int bytes) {
    }

    /**
     * Measures one method.
     *
     * @param owner      the class declaring it; the class file is read, the class itself is not touched
     * @param methodName the method's name
     * @param descriptor the JVM descriptor, for example {@code (I)D}; pass {@code null} to take the first
     *                   overload found, which is only safe for a method that is not overloaded
     * @return the code length in bytes, or {@link #UNMEASURED} if the class file cannot be read or the
     *         method is not in it
     */
    public static int methodBytes(Class<?> owner, String methodName, String descriptor) {
        for (Method method : methodsOf(owner)) {
            if (method.name().equals(methodName)
                    && (descriptor == null || method.descriptor().equals(descriptor))) {
                return method.bytes();
            }
        }
        return UNMEASURED;
    }

    /**
     * The largest method in a class.
     *
     * <p>The number that answers "is anything in this class too big to inline, or too big to compile". A
     * class whose largest method is under {@link InliningBudget#FREQ_INLINE_SIZE} is a class where no
     * method can fail the hot limit, which is worth knowing before splitting anything.
     *
     * @return the largest code length, or {@link #UNMEASURED} if the class file cannot be read or the
     *         class has no methods
     */
    public static int largestMethodBytes(Class<?> owner) {
        require(owner != null, "a class is required");
        int largest = UNMEASURED;
        for (Method method : methodsOf(owner)) {
            if (method.bytes() > largest) {
                largest = method.bytes();
            }
        }
        return largest;
    }

    /**
     * Every method in a class, constructors and static initialisers included.
     *
     * @return the methods, empty if the class file cannot be read
     */
    public static List<Method> methodsOf(Class<?> owner) {
        require(owner != null, "a class is required");
        byte[] bytes = readClassFile(owner);
        if (bytes == null) {
            return List.of();
        }
        try {
            return parse(bytes);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            // A truncated or unexpected class file is not worth failing over: the measurement is used to
            // make a performance decision, and the honest answer for a class that cannot be read is that
            // it cannot be read.
            return List.of();
        }
    }

    /**
     * Whether the class's bytecode is readable at all.
     *
     * <p>False for a class whose file is not on the classpath in a readable form. Worth distinguishing
     * from a measurement of zero, because a caller that treats {@link #UNMEASURED} as "tiny" would conclude
     * that the method always inlines and stop looking.
     */
    public static boolean isMeasurable(Class<?> owner) {
        require(owner != null, "a class is required");
        return readClassFile(owner) != null;
    }

    // ---------------------------------------------------------------------------------------------
    // The parse. One cursor over a byte array, in the order the specification lays the file out.
    // ---------------------------------------------------------------------------------------------

    private static List<Method> parse(byte[] bytes) {
        Cursor cursor = new Cursor(bytes);
        if (cursor.u4() != MAGIC) {
            throw new IllegalArgumentException("not a class file");
        }
        cursor.u2(); // minor version
        cursor.u2(); // major version

        // The constant pool has to be walked entry by entry rather than skipped, because a Utf8 entry is
        // variable length and Long and Double entries take two slots. It is also what the method names and
        // descriptors are indexes into, so it is kept rather than discarded.
        int constantPoolCount = cursor.u2();
        String[] utf8 = new String[constantPoolCount];
        for (int index = 1; index < constantPoolCount; index++) {
            int tag = cursor.u1();
            switch (tag) {
                case TAG_UTF8 -> {
                    int length = cursor.u2();
                    utf8[index] = new String(bytes, cursor.position, length, java.nio.charset.StandardCharsets.UTF_8);
                    cursor.position += length;
                }
                case TAG_INTEGER, TAG_FLOAT, TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF,
                     TAG_NAME_AND_TYPE, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> cursor.position += 4;
                case TAG_LONG, TAG_DOUBLE -> {
                    cursor.position += 8;
                    // An eight-byte constant occupies the next slot as well, and the specification counts
                    // it as unusable rather than as a separate entry. Skipping this is the single most
                    // common way to get this parse wrong.
                    index++;
                }
                case TAG_CLASS, TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> cursor.position += 2;
                case TAG_METHOD_HANDLE -> cursor.position += 3;
                default -> throw new IllegalArgumentException("unknown constant pool tag " + tag);
            }
        }

        cursor.u2(); // access flags
        cursor.u2(); // this class
        cursor.u2(); // super class
        // Read the count into a local first. A compound assignment takes its left-hand value before the
        // right-hand side runs in some languages and after in others, and here the right-hand side moves
        // the very cursor being incremented -- the one way to get this wrong and have it look plausible.
        int interfaceCount = cursor.u2();
        cursor.skip(interfaceCount * 2); // interfaces

        int fieldCount = cursor.u2();
        for (int i = 0; i < fieldCount; i++) {
            skipMember(cursor);
        }

        List<Method> methods = new ArrayList<>();
        int methodCount = cursor.u2();
        for (int i = 0; i < methodCount; i++) {
            cursor.u2(); // access flags
            String name = utf8[cursor.u2()];
            String descriptor = utf8[cursor.u2()];
            int attributeCount = cursor.u2();
            int codeLength = UNMEASURED;
            for (int a = 0; a < attributeCount; a++) {
                String attributeName = utf8[cursor.u2()];
                int attributeLength = cursor.u4();
                if (CODE_ATTRIBUTE.equals(attributeName) && attributeLength >= 8) {
                    // max_stack (2) + max_locals (2), then the code length itself.
                    codeLength = cursor.peekU4(cursor.position + 4);
                }
                cursor.position += attributeLength;
            }
            if (codeLength >= 0 && name != null && descriptor != null) {
                methods.add(new Method(name, descriptor, codeLength));
            }
        }
        return methods;
    }

    private static void skipMember(Cursor cursor) {
        cursor.u2(); // access flags
        cursor.u2(); // name
        cursor.u2(); // descriptor
        int attributeCount = cursor.u2();
        for (int a = 0; a < attributeCount; a++) {
            cursor.u2(); // attribute name
            // Into a local before the cursor moves, for the same reason as the interface table above.
            int length = cursor.u4();
            cursor.skip(length);
        }
    }

    /**
     * Finds the class file.
     *
     * <p>Asked of the declaring class's own loader rather than the context loader, so a class from another
     * mod or from Minecraft is read through whatever loader loaded it -- which is the loader that can see
     * its resources.
     */
    private static byte[] readClassFile(Class<?> owner) {
        String resource = "/" + owner.getName().replace('.', '/') + ".class";
        try (InputStream in = owner.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** A big-endian cursor over a byte array, with a name for the position so the skips read clearly. */
    private static final class Cursor {

        private final byte[] bytes;
        private int position;

        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private int u1() {
            return this.bytes[this.position++] & 0xFF;
        }

        private int u2() {
            int value = ((this.bytes[this.position] & 0xFF) << 8) | (this.bytes[this.position + 1] & 0xFF);
            this.position += 2;
            return value;
        }

        private int u4() {
            int value = peekU4(this.position);
            this.position += 4;
            return value;
        }

        /** Reads a {@code u4} at an absolute offset without moving, which is what the Code body needs. */
        private int peekU4(int offset) {
            return ((this.bytes[offset] & 0xFF) << 24)
                    | ((this.bytes[offset + 1] & 0xFF) << 16)
                    | ((this.bytes[offset + 2] & 0xFF) << 8)
                    | (this.bytes[offset + 3] & 0xFF);
        }

        private void skip(int count) {
            this.position += count;
        }
    }
}
