package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.DoubleNumbers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default writer, given an {@link OutputConverter}, asks it for a value of the type each setter takes, the most
 * specific first, only when no setter takes the value as it is, and calls the first setter that takes what it returns;
 * a conversion the converter refuses doesn't stop it trying the next setter, and fails the write only when none takes
 * the value (#1127). Without a converter, nothing changes (#524).
 */
@DisplayName("the default OutputWriter converts a value with the rule's language when no setter accepts it as it is")
class OutputWriterConversionTest {

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

    /** A setter for an Integer, which starts set, so a null set shows. */
    public static final class BoxedR {
        String setter = "unset";

        public void setR(Integer r) {
            setter = "Integer " + r;
        }
    }

    /** Setters for an int and a long, which records the one called. */
    public static final class IntOrLong {
        String setter;

        public void setR(int r) {
            setter = "int " + r;
        }

        public void setR(long r) {
            setter = "long " + r;
        }
    }

    /** Setters for an int, a long, an Integer and text, which records the one called. */
    public static final class Several {
        String setter;

        public void setR(int r) {
            setter = "int " + r;
        }

        public void setR(long r) {
            setter = "long " + r;
        }

        public void setR(Integer r) {
            setter = "Integer " + r;
        }

        public void setR(String r) {
            setter = "String " + r;
        }
    }

    /** A generic setter narrowed to a number. */
    public static class NumberBox<T extends Number> {
        public void setContent(T content) {
        }
    }

    /**
     * A number setter overridden for an Integer, so the class has a bridge for a number, beside a setter for text,
     * which records the one called.
     */
    public static final class IntegerNumberOrTextBox extends NumberBox<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer " + content;
        }

        public void setContent(String content) {
            setter = "String " + content;
        }
    }

    /** What a failure adds after the name of a setter. */
    private static final String NOTE = ", but a value is only widened as Java widens a primitive, never narrowed or"
            + " converted)";

    /** The conversions the converter was asked for, as {@code value -> target}. */
    private final List<String> asked = new ArrayList<>();

    /** A converter that records what it's asked, and converts as {@link DoubleNumbers#convert} does. */
    private OutputConverter doubles() {
        return (value, target) -> {
            asked.add(value + " -> " + target.getTypeName());
            return DoubleNumbers.convert(value, target);
        };
    }

    /** A converter that records what it's asked, and returns what {@code conversion} gives for every target. */
    private OutputConverter always(Object conversion) {
        return (value, target) -> {
            asked.add(value + " -> " + target.getTypeName());
            return conversion;
        };
    }

    private static String set(Object output, Object value, OutputConverter converter) throws Exception {
        OutputWriter.beansAndMaps().set(output, "r", value, converter);
        return (String) output.getClass().getDeclaredField("setter").get(output);
    }

    @Test
    @DisplayName("a whole Double reaches an int, a long or an Integer setter once the converter converts it")
    void wholeDoubleConverted() throws Exception {
        assertAll(
                () -> assertEquals("int 3", set(new IntR(), 3.0, doubles())),
                () -> assertEquals("long 3", set(new LongR(), 3.0, doubles())),
                () -> assertEquals("Integer 3", set(new BoxedR(), 3.0, doubles())));
    }

    @Test
    @DisplayName("the converter is asked for each setter's type, the most specific first, until a setter takes the"
            + " conversion")
    void askedInTheSettersOrder() throws Exception {
        assertEquals("Integer 3", set(new Several(), 3.0, doubles()));
        assertEquals(List.of("3.0 -> java.lang.Integer"), asked);
    }

    @Test
    @DisplayName("a refusal for one setter doesn't stop the next one being tried: a Double too large for an int"
            + " reaches the long setter")
    void refusalTriesTheNextSetter() throws Exception {
        assertEquals("long 3000000000", set(new Several(), 3.0E9, doubles()));
        assertEquals(List.of("3.0E9 -> java.lang.Integer", "3.0E9 -> java.lang.String", "3.0E9 -> int",
                "3.0E9 -> long"), asked);
    }

    @Test
    @DisplayName("beside setR(long), setR(int) is asked for first: 3.0 reaches it, and 3,000,000,000.0, refused for it,"
            + " reaches setR(long)")
    void intBeforeLong() throws Exception {
        assertEquals("int 3", set(new IntOrLong(), 3.0, doubles()));
        assertEquals("long 3000000000", set(new IntOrLong(), 3.0E9, doubles()));
        assertEquals(List.of("3.0 -> int", "3.0E9 -> int", "3.0E9 -> long"), asked);
    }

    @Test
    @DisplayName("when no setter takes the value, the first refusal fails the write")
    void firstRefusalFailsTheWrite() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new Several(), 1.5, doubles()));

        assertEquals("1.5 can't be converted to java.lang.Integer: not a whole number", thrown.getMessage());
        assertEquals(List.of("1.5 -> java.lang.Integer", "1.5 -> java.lang.String", "1.5 -> int", "1.5 -> long"),
                asked);
    }

    @Test
    @DisplayName("a generic setter overridden for an Integer takes a converted Double through the override, asked for"
            + " the type the class gives the variable")
    void genericSetterConverted() throws Exception {
        IntegerNumberOrTextBox output = new IntegerNumberOrTextBox();

        OutputWriter.beansAndMaps().set(output, "content", 3.0, doubles());

        assertEquals("Integer 3", output.setter);
        assertEquals(List.of("3.0 -> java.lang.Integer"), asked);
    }

    @Test
    @DisplayName("a converter with no opinion, which returns the value unchanged, leaves the failure as it was")
    void noOpinionFailsAsBefore() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntR(), 3.0, (value, target) -> value));

        assertEquals(IntR.class.getName() + " has no public method setR that accepts a java.lang.Double (setR(int)"
                + " exists" + NOTE, thrown.getMessage());
    }

    @Test
    @DisplayName("a conversion the setter doesn't take leaves the failure as it was")
    void unacceptedConversionFailsAsBefore() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntR(), 3.0, always("3")));

        assertEquals(IntR.class.getName() + " has no public method setR that accepts a java.lang.Double (setR(int)"
                + " exists" + NOTE, thrown.getMessage());
        assertEquals(List.of("3.0 -> int"), asked);
    }

    @Test
    @DisplayName("a conversion is widened as a value is: an Integer for a long setter")
    void conversionWidened() throws Exception {
        assertEquals("long 3", set(new LongR(), 3.0, always(3)));
    }

    @Test
    @DisplayName("a conversion to null is set where the setter takes a reference")
    void nullConversionSet() throws Exception {
        assertEquals("Integer null", set(new BoxedR(), 3.0, always(null)));
    }

    @Test
    @DisplayName("anything but an IllegalArgumentException the converter throws fails the write at once")
    void otherExceptionsFailTheWriteAtOnce() {
        IllegalStateException broke = new IllegalStateException("the runtime is gone");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> set(new Several(), 3.0, (value, target) -> {
                    throw broke;
                }));

        assertSame(broke, thrown);
    }

    @Test
    @DisplayName("a value a setter takes as it is, or widened, is never converted")
    void acceptedValueNotConverted() throws Exception {
        assertEquals("long 5", set(new LongR(), 5, doubles()));
        assertEquals("Integer 5", set(new Several(), 5, doubles()));
        assertEquals(List.of(), asked);
    }

    @Test
    @DisplayName("null is set as it is, and never converted")
    void nullNotConverted() throws Exception {
        assertEquals("Integer null", set(new BoxedR(), null, doubles()));
        assertEquals(List.of(), asked);
    }

    @Test
    @DisplayName("a Map output takes the value as it is, and nothing is converted")
    void mapOutputNotConverted() throws Exception {
        Map<String, Object> output = new HashMap<>();

        OutputWriter.beansAndMaps().set(output, "r", 3.0, doubles());

        assertEquals(Map.of("r", 3.0), output);
        assertEquals(List.of(), asked);
    }

    @Test
    @DisplayName("the three-argument set converts nothing")
    void threeArgumentSetConvertsNothing() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(new IntR(), "r", 3.0));

        assertEquals(IntR.class.getName() + " has no public method setR that accepts a java.lang.Double (setR(int)"
                + " exists" + NOTE, thrown.getMessage());
    }

    @Test
    @DisplayName("the four-argument set rejects a null converter")
    void nullConverterRejected() {
        assertEquals("converter must not be null", assertThrows(NullPointerException.class,
                () -> OutputWriter.beansAndMaps().set(new IntR(), "r", 3.0, null)).getMessage());
    }
}
