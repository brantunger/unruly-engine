package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A boxed primitive that no setter accepts as it is goes to a setter taking a primitive that Java widens it to (JLS
 * 5.1.2), the most specific one, as Java would call it. Nothing else is converted: not narrowed, not from one wrapper
 * to another, and not a {@link BigDecimal}. A write that still fails names the setters that take a primitive or a
 * wrapper (#524).
 */
@DisplayName("the default OutputWriter widens a boxed primitive as Java would, when no setter accepts it as it is")
class OutputWriterWideningTest {

    /** A setter for a byte. */
    public static final class ByteR {
        String setter;

        public void setR(byte r) {
            setter = "byte " + r;
        }
    }

    /** A setter for a short. */
    public static final class ShortR {
        String setter;

        public void setR(short r) {
            setter = "short " + r;
        }
    }

    /** A setter for a char. */
    public static final class CharR {
        String setter;

        public void setR(char r) {
            setter = "char " + r;
        }
    }

    /** A setter for an int. */
    public static final class IntR {
        String setter;

        public void setR(int r) {
            setter = "int " + r;
        }
    }

    /** A setter for a long. */
    public static final class LongR {
        String setter;

        public void setR(long r) {
            setter = "long " + r;
        }
    }

    /** A setter for a float. */
    public static final class FloatR {
        String setter;

        public void setR(float r) {
            setter = "float " + r;
        }
    }

    /** A setter for a double. */
    public static final class DoubleR {
        String setter;

        public void setR(double r) {
            setter = "double " + r;
        }
    }

    /** A setter for a boolean. */
    public static final class BooleanR {
        String setter;

        public void setR(boolean r) {
            setter = "boolean " + r;
        }
    }

    /** Setters for a long and a double, which sorts first by name. */
    public static final class LongOrDouble {
        String setter;

        public void setR(long r) {
            setter = "long " + r;
        }

        public void setR(double r) {
            setter = "double " + r;
        }
    }

    /** Setters for a float and a double, which sorts first by name. */
    public static final class FloatOrDouble {
        String setter;

        public void setR(float r) {
            setter = "float " + r;
        }

        public void setR(double r) {
            setter = "double " + r;
        }
    }

    /** Setters for a long and a float, which a long widens to. */
    public static final class LongOrFloat {
        String setter;

        public void setR(long r) {
            setter = "long " + r;
        }

        public void setR(float r) {
            setter = "float " + r;
        }
    }

    /** Setters for a char and an int, which a char widens to and a byte reaches only as an int. */
    public static final class CharOrInt {
        String setter;

        public void setR(char r) {
            setter = "char " + r;
        }

        public void setR(int r) {
            setter = "int " + r;
        }
    }

    /** Setters for a short and an int, which a short widens to. */
    public static final class ShortOrInt {
        String setter;

        public void setR(short r) {
            setter = "short " + r;
        }

        public void setR(int r) {
            setter = "int " + r;
        }
    }

    /** Setters for text and for a long. */
    public static final class StringOrLong {
        String setter;

        public void setR(String r) {
            setter = "String " + r;
        }

        public void setR(long r) {
            setter = "long " + r;
        }
    }

    /** A generic setter narrowed to a number. */
    public static class NumberBox<T extends Number> {
        public void setContent(T content) {
        }
    }

    /** A number setter overridden for an Integer, so the class has a bridge for a number, beside one for a long. */
    public static final class IntegerNumberOrLongBox extends NumberBox<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer " + content;
        }

        public void setContent(long content) {
            setter = "long " + content;
        }
    }

    /** Setters for any object and for a long. */
    public static final class ObjectOrLong {
        String setter;

        public void setR(Object r) {
            setter = "Object " + r;
        }

        public void setR(long r) {
            setter = "long " + r;
        }
    }

    /** Setters for a number and for a long. */
    public static final class NumberOrLong {
        String setter;

        public void setR(Number r) {
            setter = "Number " + r;
        }

        public void setR(long r) {
            setter = "long " + r;
        }
    }

    /** Setters for an Integer and for a long. */
    public static final class IntegerOrLong {
        String setter;

        public void setR(Integer r) {
            setter = "Integer " + r;
        }

        public void setR(long r) {
            setter = "long " + r;
        }
    }

    /** A setter for a Long. */
    public static final class BoxedLongR {
        String setter;

        public void setR(Long r) {
            setter = "Long " + r;
        }
    }

    /** A setter for text. */
    public static final class TextR {
        String setter;

        public void setR(String r) {
            setter = "String " + r;
        }
    }

    /** Setters for wrappers, primitives and text, declared out of the order a failure lists them in. */
    public static final class Several {
        String setter;

        public void setR(Short r) {
            setter = "Short " + r;
        }

        public void setR(String r) {
            setter = "String " + r;
        }

        public void setR(boolean r) {
            setter = "boolean " + r;
        }

        public void setR(Integer r) {
            setter = "Integer " + r;
        }

        public void setR(char r) {
            setter = "char " + r;
        }

        public void setR(short r) {
            setter = "short " + r;
        }

        public void setR(byte r) {
            setter = "byte " + r;
        }
    }

    /** The output with a single setter, for each primitive type. */
    private static final Map<Class<?>, Supplier<Object>> OUTPUTS = Map.of(byte.class, ByteR::new,
            short.class, ShortR::new, char.class, CharR::new, int.class, IntR::new, long.class, LongR::new,
            float.class, FloatR::new, double.class, DoubleR::new, boolean.class, BooleanR::new);

    /** The primitive types, in the order of each row of {@link #cells()}. */
    private static final List<Class<?>> TYPES = List.of(byte.class, short.class, char.class, int.class, long.class,
            float.class, double.class, boolean.class);

    /** What a failure adds after the name of a setter. */
    private static final String NOTE = ", but a value is only widened as Java widens a primitive, never narrowed or"
            + " converted)";

    /**
     * Each value, then what a setter for each of {@link #TYPES} receives, or {@code null} where it throws. JLS 5.1.2
     * widens byte to short, int, long, float and double; short to int, long, float and double; char to int, long,
     * float and double; int to long, float and double; long to float and double; and float to double. A byte or a
     * short never becomes a char, and a char never becomes a short. A boolean becomes nothing else. Every other cell
     * is a narrowing, or a boolean, which Java wouldn't pass either.
     */
    static Stream<Arguments> cells() {
        Object[][] rows = {
            {(byte) 5, "5", "5", null, "5", "5", "5.0", "5.0", null},
            {(short) 5, null, "5", null, "5", "5", "5.0", "5.0", null},
            {'a', null, null, "a", "97", "97", "97.0", "97.0", null},
            {5, null, null, null, "5", "5", "5.0", "5.0", null},
            {5L, null, null, null, null, "5", "5.0", "5.0", null},
            {1.5f, null, null, null, null, null, "1.5", "1.5", null},
            {1.5, null, null, null, null, null, null, "1.5", null},
            {true, null, null, null, null, null, null, null, "true"},
        };
        List<Arguments> cells = new ArrayList<>();
        for (Object[] row : rows) {
            for (int i = 0; i < TYPES.size(); i++) {
                cells.add(Arguments.of(row[0].getClass().getSimpleName(), row[0], TYPES.get(i), row[i + 1]));
            }
        }
        return cells.stream();
    }

    private static String set(Object output, Object value) throws Exception {
        OutputWriter.beansAndMaps().set(output, "r", value);
        return (String) output.getClass().getDeclaredField("setter").get(output);
    }

    @ParameterizedTest(name = "a {0} {1} into setR({2}) receives {3}")
    @MethodSource("cells")
    @DisplayName("a boxed primitive reaches a primitive setter exactly where Java widens it, and fails elsewhere")
    void matrix(String kind, Object value, Class<?> type, String received) throws Exception {
        Object output = OUTPUTS.get(type).get();

        if (received == null) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> OutputWriter.beansAndMaps().set(output, "r", value));
            assertTrue(thrown.getMessage().startsWith(output.getClass().getName() + " has no public method setR that"
                    + " accepts a java.lang." + kind), thrown.getMessage());
        } else {
            assertEquals(type.getName() + " " + received, set(output, value));
        }
    }

    @Test
    @DisplayName("a lossy widening gives what Java gives")
    void lossyWidening() throws Exception {
        // Java widens these without a cast, losing precision: the float is 16777216.
        float javaFloat = 16_777_217;
        double javaDouble = Long.MAX_VALUE;

        assertEquals("float " + javaFloat, set(new FloatR(), 16_777_217));
        assertEquals("double " + javaDouble, set(new DoubleR(), Long.MAX_VALUE));
    }

    @Test
    @DisplayName("an Integer goes to the long setter, not the double one, as Java calls it")
    void longBeforeDouble() throws Exception {
        assertEquals("long 5", set(new LongOrDouble(), 5));
    }

    @Test
    @DisplayName("an Integer goes to the float setter, not the double one, as Java calls it")
    void floatBeforeDouble() throws Exception {
        assertEquals("float 5.0", set(new FloatOrDouble(), 5));
    }

    @Test
    @DisplayName("an Integer goes to the long setter, not the float one, as Java calls it")
    void longBeforeFloat() throws Exception {
        assertEquals("long 5", set(new LongOrFloat(), 5));
    }

    @Test
    @DisplayName("a Character goes to the char setter, not the int one it also widens to")
    void charBeforeInt() throws Exception {
        assertEquals("char a", set(new CharOrInt(), 'a'));
    }

    @Test
    @DisplayName("a Byte goes to the int setter, as a byte never becomes a char")
    void byteSkipsChar() throws Exception {
        assertEquals("int 5", set(new CharOrInt(), (byte) 5));
    }

    @Test
    @DisplayName("a Byte goes to the short setter, not the int one, as Java calls it")
    void shortBeforeInt() throws Exception {
        assertEquals("short 5", set(new ShortOrInt(), (byte) 5));
    }

    @Test
    @DisplayName("an Integer that the String setter doesn't accept goes to the long one")
    void wideningAfterAReferenceSetterRefuses() throws Exception {
        assertEquals("long 5", set(new StringOrLong(), 5));
    }

    @Test
    @DisplayName("a Short that the Integer setter doesn't accept goes to the long one")
    void wideningAfterAWrapperSetterRefuses() throws Exception {
        assertEquals("long 5", set(new IntegerOrLong(), (short) 5));
    }

    @Test
    @DisplayName("a Short goes to the long setter beside an override for an Integer, not through the override's bridge")
    void wideningBesideAGenericBridge() throws Exception {
        IntegerNumberOrLongBox output = new IntegerNumberOrLongBox();

        OutputWriter.beansAndMaps().set(output, "content", (short) 5);

        assertEquals("long 5", output.setter);
    }

    @Test
    @DisplayName("a Character goes to an int setter as its code")
    void characterToInt() throws Exception {
        assertEquals("int 97", set(new IntR(), 'a'));
    }

    @Test
    @DisplayName("an Integer goes to the Object setter, not the long one, as Java calls it without unboxing")
    void objectBeforeWidening() throws Exception {
        assertEquals("Object 5", set(new ObjectOrLong(), 5));
    }

    @Test
    @DisplayName("an Integer goes to the Number setter, not the long one, as Java calls it without unboxing")
    void numberBeforeWidening() throws Exception {
        assertEquals("Number 5", set(new NumberOrLong(), 5));
    }

    @Test
    @DisplayName("an Integer goes to the Integer setter, not the long one")
    void wrapperBeforeWidening() throws Exception {
        assertEquals("Integer 5", set(new IntegerOrLong(), 5));
    }

    @Test
    @DisplayName("an Integer isn't converted to a Long")
    void noWrapperToWrapper() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new BoxedLongR(), 5));

        assertTrue(thrown.getMessage().startsWith(BoxedLongR.class.getName() + " has no public method setR that"
                + " accepts a java.lang.Integer"), thrown.getMessage());
    }

    @Test
    @DisplayName("a Map output keeps the Integer as it is")
    void mapKeepsTheValue() throws Exception {
        Map<String, Object> output = new HashMap<>();

        OutputWriter.beansAndMaps().set(output, "r", 5);

        assertEquals(Integer.class, output.get("r").getClass());
    }

    @Test
    @DisplayName("a Long into an int setter fails, naming the setter and saying a value is never narrowed")
    void narrowingNamesTheSetter() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> set(new IntR(), 5L));

        assertEquals(IntR.class.getName() + " has no public method setR that accepts a java.lang.Long (setR(int) exists"
                + NOTE, thrown.getMessage());
    }

    @Test
    @DisplayName("a Character into a short setter fails, naming the setter")
    void characterNamesTheSetter() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> set(new ShortR(), 'a'));

        assertEquals(ShortR.class.getName() + " has no public method setR that accepts a java.lang.Character"
                + " (setR(short) exists" + NOTE, thrown.getMessage());
    }

    @Test
    @DisplayName("a BigDecimal isn't converted to a primitive, and the failure names the setter")
    void bigDecimalNamesTheSetter() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new DoubleR(), new BigDecimal("1.5")));

        assertEquals(DoubleR.class.getName() + " has no public method setR that accepts a java.math.BigDecimal"
                + " (setR(double) exists" + NOTE, thrown.getMessage());
    }

    @Test
    @DisplayName("a failure names the setters that take a primitive or a wrapper, the primitives first in Java's"
            + " widening order, then the wrappers by name")
    void severalSettersInOrder() {
        String exist = " (setR(byte), setR(short), setR(char), setR(boolean), setR(java.lang.Integer),"
                + " setR(java.lang.Short) exist" + NOTE;

        IllegalArgumentException forLong = assertThrows(IllegalArgumentException.class,
                () -> set(new Several(), 5L));
        IllegalArgumentException forDouble = assertThrows(IllegalArgumentException.class,
                () -> set(new Several(), 1.5));

        assertEquals(Several.class.getName() + " has no public method setR that accepts a java.lang.Long" + exist,
                forLong.getMessage());
        assertEquals(Several.class.getName() + " has no public method setR that accepts a java.lang.Double" + exist,
                forDouble.getMessage());
    }

    @Test
    @DisplayName("a Boolean into setters for numbers fails, naming them")
    void booleanNamesTheSetters() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new BoxedLongR(), true));

        assertEquals(BoxedLongR.class.getName() + " has no public method setR that accepts a java.lang.Boolean"
                + " (setR(java.lang.Long) exists" + NOTE, thrown.getMessage());
    }

    @Test
    @DisplayName("text that no setter accepts fails without naming the setters")
    void textNamesNoSetter() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> set(new IntR(), "5"));

        assertEquals(IntR.class.getName() + " has no public method setR that accepts a java.lang.String",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a number that only a setter for text is beside fails without naming it")
    void numberBesideOnlyAReferenceSetterNamesNoSetter() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> set(new TextR(), 5));

        assertEquals(TextR.class.getName() + " has no public method setR that accepts a java.lang.Integer",
                thrown.getMessage());
    }
}
