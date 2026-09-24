package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A setter declared with a type variable of a class, such as {@code setContent(T)}, takes what the output class makes
 * of the variable where another setter of its name takes a different parameter, so the default {@link OutputWriter}
 * calls the setter Java would, and refuses a value Java wouldn't compile. Without such an overload it still takes its
 * erased parameter (#581).
 */
@DisplayName("the default OutputWriter takes a setter declared with a type variable as the output class gives it")
class OutputWriterGenericSetterTest {

    /** A setter declared with a type variable. */
    public static class Box<T> {
        String setter;

        public void setContent(T content) {
            setter = "Box.setContent(T)";
        }
    }

    /** That setter for a Long, beside one for a long. */
    public static final class LongBox extends Box<Long> {
        public void setContent(long content) {
            setter = "LongBox.setContent(long)";
        }
    }

    /** Passes its own type variable on to the generic setter. */
    public static class Middle<U> extends Box<U> {
    }

    /** The generic setter, two levels up, for a Long, beside one for a long. */
    public static final class LongMiddle extends Middle<Long> {
        public void setContent(long content) {
            setter = "LongMiddle.setContent(long)";
        }
    }

    /** The generic setter inherited raw, beside one for a long. */
    @SuppressWarnings("rawtypes")
    public static final class RawLongBox extends Box {
        public void setContent(long content) {
            setter = "RawLongBox.setContent(long)";
        }
    }

    /** A generic setter method, beside one for a long. */
    public static final class MethodBox {
        String setter;

        public <U> void setContent(U content) {
            setter = "MethodBox.setContent(U)";
        }

        public void setContent(long content) {
            setter = "MethodBox.setContent(long)";
        }
    }

    /** A setter declared with a type variable bounded by a number. */
    public static class NumberBox<T extends Number> {
        String setter;

        public void setContent(T content) {
            setter = "NumberBox.setContent(T)";
        }
    }

    /** That setter for a Long, beside one for a long. */
    public static final class LongNumberBox extends NumberBox<Long> {
        public void setContent(long content) {
            setter = "LongNumberBox.setContent(long)";
        }
    }

    /** The generic setter for a character sequence, beside one for text. */
    public static final class SequenceBox extends Box<CharSequence> {
        public void setContent(String content) {
            setter = "SequenceBox.setContent(String)";
        }
    }

    /** The generic setter for text, beside one for a character sequence, which is wider. */
    public static final class TextBox extends Box<String> {
        public void setContent(CharSequence content) {
            setter = "TextBox.setContent(CharSequence)";
        }
    }

    /** The generic setter for a Long, beside one for an Integer. */
    public static final class IntegerBesideLongBox extends Box<Long> {
        public void setContent(Integer content) {
            setter = "IntegerBesideLongBox.setContent(Integer)";
        }
    }

    /** The generic setter for a list of text, beside one for a long. */
    public static final class ListBox extends Box<List<String>> {
        public void setContent(long content) {
            setter = "ListBox.setContent(long)";
        }
    }

    /** The generic setter overridden for an Integer, beside one for a long. */
    public static final class IntegerOverrideBox extends Box<Integer> {
        @Override
        public void setContent(Integer content) {
            setter = "IntegerOverrideBox.setContent(Integer)";
        }

        public void setContent(long content) {
            setter = "IntegerOverrideBox.setContent(long)";
        }
    }

    /** The generic setter for text, with no other setter of its name. */
    public static final class TextHolder extends Box<String> {
    }

    /** A setter declared with a type variable, beside one for a long, in the same generic class. */
    public static class PairedBox<T> {
        String setter;

        public void setContent(T content) {
            setter = "PairedBox.setContent(T)";
        }

        public void setContent(long content) {
            setter = "PairedBox.setContent(long)";
        }
    }

    /** That class, with the type variable given a Long. */
    public static final class LongPairedBox extends PairedBox<Long> {
    }

    /** Not public: its setter is callable through a public subclass only by the bridge javac adds there. */
    static class HiddenBox<T> {
        String setter;

        public void setContent(T content) {
            setter = "HiddenBox.setContent(T)";
        }
    }

    /** That setter for a Long, through a bridge, beside one for a long. */
    public static final class VisibleLongBox extends HiddenBox<Long> {
        public void setContent(long content) {
            setter = "VisibleLongBox.setContent(long)";
        }
    }

    /** That setter for a Long, through a bridge, beside one for an Integer, a narrower reference. */
    public static final class VisibleIntegerBox extends HiddenBox<Long> {
        public void setContent(Integer content) {
            setter = "VisibleIntegerBox.setContent(Integer)";
        }
    }

    /** A setter declared with a type variable in an interface. */
    public interface DefaultBox<T> {
        default void setContent(T content) {
            record("DefaultBox.setContent(T)");
        }

        void record(String setter);
    }

    /** The interface's setter for a Long, beside one for a long. */
    public static final class LongDefaultBox implements DefaultBox<Long> {
        String setter;

        @Override
        public void record(String setter) {
            this.setter = setter;
        }

        public void setContent(long content) {
            setter = "LongDefaultBox.setContent(long)";
        }
    }

    /** Gives the interface's type variable a Long. */
    public interface LongDefault extends DefaultBox<Long> {
    }

    /** The interface's setter for a Long, through an interface that extends it, beside one for a long. */
    public static final class LongSubDefaultBox implements LongDefault {
        String setter;

        @Override
        public void record(String setter) {
            this.setter = setter;
        }

        public void setContent(long content) {
            setter = "LongSubDefaultBox.setContent(long)";
        }
    }

    /** A setter declared with an array of a type variable. */
    public static class ArrayBox<T> {
        String setter;

        public void setItems(T[] items) {
            setter = "ArrayBox.setItems(T[])";
        }
    }

    /** That setter for an array of text, beside one for a list. */
    public static final class TextArrayBox extends ArrayBox<String> {
        public void setItems(List<String> items) {
            setter = "TextArrayBox.setItems(List)";
        }
    }

    /** A setter declared with a type variable bounded by a number, overriding one declared with a type variable. */
    public static class OverridingNumberBox<T extends Number> extends Box<T> {
        @Override
        public void setContent(T content) {
            setter = "OverridingNumberBox.setContent(T)";
        }
    }

    /** That setter for an Integer, with no other setter of its name, but the bridge from the class it overrides. */
    public static final class IntegerNumberBox extends OverridingNumberBox<Integer> {
    }

    /** That setter for an Integer, beside one for a long. */
    public static final class IntegerNumberBesideLongBox extends OverridingNumberBox<Integer> {
        public void setContent(long content) {
            setter = "IntegerNumberBesideLongBox.setContent(long)";
        }
    }

    /** Not public: a setter declared with a type variable bounded by a number, overriding one declared with another. */
    static class HiddenNumberBox<T extends Number> extends Box<T> {
        @Override
        public void setContent(T content) {
            setter = "HiddenNumberBox.setContent(T)";
        }
    }

    /** That setter for an Integer, through a bridge, beside one for a long. */
    public static final class VisibleIntegerBesideLongBox extends HiddenNumberBox<Integer> {
        public void setContent(long content) {
            setter = "VisibleIntegerBesideLongBox.setContent(long)";
        }
    }

    /** Gives the generic setter a Long, for its subclasses to inherit raw. */
    public static class LongFixingBox<U> extends Box<Long> {
    }

    /** Inherits the generic setter raw, though the raw class gives it a Long, beside one for a long. */
    @SuppressWarnings("rawtypes")
    public static final class RawLongFixingBox extends LongFixingBox {
        public void setContent(long content) {
            setter = "RawLongFixingBox.setContent(long)";
        }
    }

    /** A class whose inner class has a setter declared with the outer class's type variable, beside one for an int. */
    public static class Outer<T> {
        /** The inner class. */
        public class Inner {
            String setter;

            public void setContent(T content) {
                setter = "Inner.setContent(T)";
            }

            public void setContent(int content) {
                setter = "Inner.setContent(int)";
            }
        }
    }

    /** The inner class, with the outer class's type variable given text. */
    public static final class TextInner extends Outer<String>.Inner {
        public TextInner() {
            new Outer<String>().super();
        }
    }

    /** A class whose inner class extends it for a Long, and passes its own type variable to an interface's setter. */
    public static class Enclosing<U> {
        /** The inner class, with a setter for a long beside the interface's. */
        public class Passing extends Enclosing<Long> implements DefaultBox<U> {
            String setter;

            @Override
            public void record(String setter) {
                this.setter = setter;
            }

            public void setContent(long content) {
                setter = "Passing.setContent(long)";
            }
        }
    }

    /** The inner class, with the enclosing class's type variable given text. */
    public static final class TextPassing extends Enclosing<String>.Passing {
        public TextPassing() {
            new Enclosing<String>().super();
        }
    }

    /** A setter for an array of lists of text, beside one for a long. */
    public static final class ListArrayBox {
        String setter;

        public void setContent(List<String>[] content) {
            setter = "ListArrayBox.setContent(List[])";
        }

        public void setContent(long content) {
            setter = "ListArrayBox.setContent(long)";
        }
    }

    /** A setter on an interface for any object. */
    public interface AnyContent<N> {
        void setContent(N content);
    }

    /** Not public: a setter declared with a type variable bounded by a number. */
    static class HiddenNumberSetter<T extends Number> {
        String setter;

        public void setContent(T content) {
            setter = "HiddenNumberSetter.setContent(T)";
        }
    }

    /** Both setters for an Integer: the class's through a bridge, and the interface's through another. */
    public static final class IntegerForBoth extends HiddenNumberSetter<Integer> implements AnyContent<Integer> {
    }

    /** Gives the interface's type variable a Long. */
    public interface LongDefaultToo extends DefaultBox<Long> {
    }

    /** The interface's setter for a Long, through two interfaces that extend it, beside one for a long. */
    public static final class LongDiamondBox implements LongDefault, LongDefaultToo {
        String setter;

        @Override
        public void record(String setter) {
            this.setter = setter;
        }

        public void setContent(long content) {
            setter = "LongDiamondBox.setContent(long)";
        }
    }

    @Test
    @DisplayName("a Short or an Integer goes to setContent(long), not setContent(T) with T a Long, as Java calls it")
    void widenedBesideASetterForATypeVariable() throws Exception {
        assertEquals("LongBox.setContent(long)", set(new LongBox(), (short) 7));
        assertEquals("LongBox.setContent(long)", set(new LongBox(), 7));
    }

    @Test
    @DisplayName("a Long or null goes to setContent(T) with T a Long, beside setContent(long)")
    void variablesTypeBesideAPrimitiveSetter() throws Exception {
        assertEquals("Box.setContent(T)", set(new LongBox(), 7L));
        assertEquals("Box.setContent(T)", set(new LongBox(), null));
    }

    @Test
    @DisplayName("text isn't passed to setContent(T) with T a Long, beside setContent(long)")
    void textRefusedBesideAPrimitiveSetter() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new LongBox(), "text"));

        assertEquals(LongBox.class.getName() + " has no public method setContent that accepts a java.lang.String",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a Short goes to setContent(long) where the type variable is passed on through a generic class")
    void variableGivenTwoLevelsUp() throws Exception {
        assertEquals("LongMiddle.setContent(long)", set(new LongMiddle(), (short) 7));
    }

    @Test
    @DisplayName("a Short goes to setContent(long), not setContent(T) with T a Long, where T is bounded by a number")
    void boundedVariable() throws Exception {
        assertEquals("LongNumberBox.setContent(long)", set(new LongNumberBox(), (short) 7));
    }

    @Test
    @DisplayName("a Short isn't passed to setContent(T) with T a Long, beside setContent(Integer), and the failure"
            + " names both setters by the types they take")
    void neitherWrapperTakesAShort() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerBesideLongBox(), (short) 7));

        assertEquals(IntegerBesideLongBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.Short (setContent(java.lang.Integer), setContent(java.lang.Long) exist, but a value is"
                + " only widened as Java widens a primitive, never narrowed or converted)", thrown.getMessage());
    }

    @Test
    @DisplayName("an Integer and a Long each go to their own setter, setContent(Integer) or setContent(T) with T a"
            + " Long")
    void eachWrapperToItsOwnSetter() throws Exception {
        assertEquals("IntegerBesideLongBox.setContent(Integer)", set(new IntegerBesideLongBox(), 7));
        assertEquals("Box.setContent(T)", set(new IntegerBesideLongBox(), 7L));
    }

    @Test
    @DisplayName("a Short goes to setContent(long) beside setContent(T), both in the generic class, for a subclass"
            + " that gives T a Long")
    void overloadInTheGenericClass() throws Exception {
        assertEquals("PairedBox.setContent(long)", set(new LongPairedBox(), (short) 7));
    }

    @Test
    @DisplayName("a Short goes to setContent(long) for an anonymous subclass that gives T a Long")
    void anonymousSubclassGivesTheVariable() throws Exception {
        assertEquals("PairedBox.setContent(long)", set(new PairedBox<Long>() { }, (short) 7));
    }

    @Test
    @DisplayName("a Short goes to setContent(T) for an instance of the generic class itself, which gives T nothing: a"
            + " known limit")
    void instanceOfTheGenericClass() throws Exception {
        // Java would call setContent(long) on a PairedBox<Long>, but the type argument isn't kept at run time, so T
        // takes its bound, Object, which accepts the Short.
        assertEquals("PairedBox.setContent(T)", set(new PairedBox<Long>(), (short) 7));
    }

    @Test
    @DisplayName("a Short goes to setContent(long), not through the bridge to setContent(T) with T a Long in a class"
            + " that isn't public")
    void visibilityBridgeBesideAPrimitiveSetter() throws Exception {
        assertEquals("VisibleLongBox.setContent(long)", set(new VisibleLongBox(), (short) 7));
        assertEquals("HiddenBox.setContent(T)", set(new VisibleLongBox(), 7L));
    }

    @Test
    @DisplayName("a Long goes through the bridge to setContent(T) with T a Long in a class that isn't public, beside"
            + " setContent(Integer), as Java calls it")
    void visibilityBridgeBesideANarrowerReference() throws Exception {
        assertEquals("HiddenBox.setContent(T)", set(new VisibleIntegerBox(), 7L));
        assertEquals("VisibleIntegerBox.setContent(Integer)", set(new VisibleIntegerBox(), 7));
    }

    @Test
    @DisplayName("text isn't passed through the bridge to setContent(T) with T a Long in a class that isn't public")
    void visibilityBridgeRefusesAnotherType() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new VisibleIntegerBox(), "text"));

        assertEquals(VisibleIntegerBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
    }

    @Test
    @DisplayName("a Short goes to setContent(long), not an interface's default setContent(T) with T a Long")
    void interfaceDefault() throws Exception {
        assertEquals("LongDefaultBox.setContent(long)", set(new LongDefaultBox(), (short) 7));
        assertEquals("LongSubDefaultBox.setContent(long)", set(new LongSubDefaultBox(), (short) 7));
        assertEquals("DefaultBox.setContent(T)", set(new LongSubDefaultBox(), 7L));
    }

    @Test
    @DisplayName("an array of another type isn't passed to setItems(T[]) with T a String, beside setItems(List)")
    void arrayOfAVariable() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new TextArrayBox(), "items", new Integer[] {1}));

        assertEquals(TextArrayBox.class.getName() + " has no public method setItems that accepts a"
                + " [Ljava.lang.Integer;", thrown.getMessage());
        assertEquals("ArrayBox.setItems(T[])", set(new TextArrayBox(), "items", new String[] {"a"}));
    }

    @Test
    @DisplayName("a set isn't passed to setContent(T) with T a list of text, beside setContent(long)")
    void variableGivenAParameterizedType() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new ListBox(), Set.of()));

        assertTrue(thrown.getMessage().startsWith(ListBox.class.getName() + " has no public method setContent that"
                + " accepts a java.util."), thrown.getMessage());
        assertEquals("Box.setContent(T)", set(new ListBox(), new ArrayList<String>()));
    }

    @Test
    @DisplayName("an Integer isn't passed to setContent(T) with T a character sequence, beside setContent(String)")
    void variableGivenAnInterface() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new SequenceBox(), 7));

        assertEquals(SequenceBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.Integer", thrown.getMessage());
        assertEquals("Box.setContent(T)", set(new SequenceBox(), new StringBuilder()));
        assertEquals("SequenceBox.setContent(String)", set(new SequenceBox(), "s"));
    }

    @Test
    @DisplayName("text goes to setContent(CharSequence) before setContent(T) with T a String, as setters are ordered by"
            + " their erased parameters: a known limit")
    void widerSetterBesideTheVariable() throws Exception {
        // Java would call setContent(T), which takes a String, the more specific of the two.
        assertEquals("TextBox.setContent(CharSequence)", set(new TextBox(), "s"));
        assertEquals("TextBox.setContent(CharSequence)", set(new TextBox(), new StringBuilder()));
    }

    @Test
    @DisplayName("a Short goes to setContent(T) inherited raw, which takes any object, beside setContent(long)")
    void rawSupertype() throws Exception {
        assertEquals("Box.setContent(T)", set(new RawLongBox(), (short) 7));
    }

    @Test
    @DisplayName("a Short goes to a generic setter method, which takes any object, beside setContent(long)")
    void methodsOwnVariable() throws Exception {
        assertEquals("MethodBox.setContent(U)", set(new MethodBox(), (short) 7));
    }

    @Test
    @DisplayName("an override of setContent(T) for an Integer and setContent(long) beside it each take what they did")
    void overrideBesideAPrimitiveSetter() throws Exception {
        assertEquals("IntegerOverrideBox.setContent(long)", set(new IntegerOverrideBox(), (short) 7));
        assertEquals("IntegerOverrideBox.setContent(Integer)", set(new IntegerOverrideBox(), 7));
    }

    @Test
    @DisplayName("setContent(T) with T a String, with no other setter of its name, takes any object, as before (#550)")
    void noOverload() throws Exception {
        assertEquals("Box.setContent(T)", set(new TextHolder(), 7));
    }

    @Test
    @DisplayName("setContent(T) with T an Integer, beside only the bridge for the setter it overrides, takes any"
            + " number, as before (#550)")
    void onlyABridgeBeside() throws Exception {
        // The bridge that setContent(T) has for Box's setContent(T) isn't an overload Java sees, so the class has none,
        // and a failure doesn't name setContent(T) as a setter for an Integer.
        assertEquals("OverridingNumberBox.setContent(T)", set(new IntegerNumberBox(), 7L));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerNumberBox(), true));
        assertEquals(IntegerNumberBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.Boolean", thrown.getMessage());
    }

    @Test
    @DisplayName("a Short or a Long goes to setContent(long), not through the bridge to an override of setContent(T)"
            + " declared with T, an Integer")
    void bridgeTakesWhatTheOverrideTakesInTheClass() throws Exception {
        assertEquals("IntegerNumberBesideLongBox.setContent(long)", set(new IntegerNumberBesideLongBox(), (short) 7));
        assertEquals("IntegerNumberBesideLongBox.setContent(long)", set(new IntegerNumberBesideLongBox(), 7L));
        assertEquals("OverridingNumberBox.setContent(T)", set(new IntegerNumberBesideLongBox(), 7));
    }

    @Test
    @DisplayName("a Short or a Long goes to setContent(long), not through the bridge to an override of setContent(T),"
            + " declared with T, an Integer, in a class that isn't public")
    void bridgeTakesWhatTheHiddenOverrideTakesInTheClass() throws Exception {
        assertEquals("VisibleIntegerBesideLongBox.setContent(long)", set(new VisibleIntegerBesideLongBox(), (short) 7));
        assertEquals("VisibleIntegerBesideLongBox.setContent(long)", set(new VisibleIntegerBesideLongBox(), 7L));
        assertEquals("HiddenNumberBox.setContent(T)", set(new VisibleIntegerBesideLongBox(), 7));
    }

    @Test
    @DisplayName("a Short and text go to setContent(T) inherited through a raw class, as Java erases every supertype of"
            + " a raw type")
    void rawClassThatGivesTheVariable() throws Exception {
        assertEquals("Box.setContent(T)", set(new RawLongFixingBox(), (short) 7));
        assertEquals("Box.setContent(T)", set(new RawLongFixingBox(), "text"));
    }

    @Test
    @DisplayName("an Integer goes to setContent(T) of an inner class, declared with the outer class's T, which takes"
            + " any object, beside setContent(int): a known limit")
    void variableOfTheOuterClass() throws Exception {
        // Java would call setContent(int), as the outer class's T is text. A type variable of a class that the
        // setter's class is inside isn't worked out, so setContent(T) takes its erasure, Object, as before.
        assertEquals("Inner.setContent(T)", set(new TextInner(), 7));
        assertEquals("Inner.setContent(T)", set(new TextInner(), "s"));
    }

    @Test
    @DisplayName("text and a Long go to an interface's setContent(T), with T given the enclosing class's variable,"
            + " beside setContent(long) in an inner class that gives its outer class a Long: a known limit")
    void variablePassedTheOuterClassesVariable() throws Exception {
        // The enclosing instance's U is text, so Java calls setContent(T) for text and setContent(long) for a Long.
        // The U that T is given isn't worked out, even where a supertype gives a U a Long, so T takes its bound.
        assertEquals("DefaultBox.setContent(T)", set(new TextPassing(), "s"));
        assertEquals("DefaultBox.setContent(T)", set(new TextPassing(), 7L));
    }

    @Test
    @DisplayName("an array of lists goes to a setter for an array of lists of text, beside setContent(long)")
    void arrayOfAParameterizedType() throws Exception {
        assertEquals("ListArrayBox.setContent(List[])", set(new ListArrayBox(), new List<?>[0]));
    }

    @Test
    @DisplayName("text isn't passed through an interface's bridge to setContent(T) with T an Integer, in a class that"
            + " isn't public")
    void interfaceBridgeBesideAVisibilityBridge() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerForBoth(), "text"));

        assertEquals(IntegerForBoth.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
        assertEquals("HiddenNumberSetter.setContent(T)", set(new IntegerForBoth(), 7));
    }

    @Test
    @DisplayName("a Short goes to setContent(long), not an interface's default setContent(T) with T a Long, given"
            + " through two interfaces")
    void variableGivenThroughTwoInterfaces() throws Exception {
        assertEquals("LongDiamondBox.setContent(long)", set(new LongDiamondBox(), (short) 7));
        assertEquals("DefaultBox.setContent(T)", set(new LongDiamondBox(), 7L));
    }

    /** Writes the content, and returns the setter that ran. */
    private static String set(Object output, Object value) throws Exception {
        return set(output, "content", value);
    }

    /** Writes the property, and returns the setter that ran. */
    private static String set(Object output, String property, Object value) throws Exception {
        OutputWriter.beansAndMaps().set(output, property, value);
        return setterOf(output);
    }

    private static String setterOf(Object output) {
        return switch (output) {
            case Box<?> box -> box.setter;
            case MethodBox box -> box.setter;
            case NumberBox<?> box -> box.setter;
            case PairedBox<?> box -> box.setter;
            case HiddenBox<?> box -> box.setter;
            case LongDefaultBox box -> box.setter;
            case LongSubDefaultBox box -> box.setter;
            case ArrayBox<?> box -> box.setter;
            case TextInner box -> box.setter;
            case ListArrayBox box -> box.setter;
            case TextPassing box -> box.setter;
            case HiddenNumberSetter<?> box -> box.setter;
            case LongDiamondBox box -> box.setter;
            default -> throw new AssertionError(output.getClass());
        };
    }
}
