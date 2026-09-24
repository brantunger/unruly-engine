package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Of an output's overloaded setters that accept a value, the default {@link OutputWriter} calls the most specific,
 * as Java would, and not the one whose parameter type's name sorts first (#503).
 * A bridge method the compiler adds for an override takes only the types the override does (#525), also where the
 * class inherits the override (#549). A bridge that makes a setter of a class that isn't public callable takes what
 * that setter does (#555).
 */
@DisplayName("the default OutputWriter calls the most specific of the overloaded setters that accept a value")
class OutputWriterOverloadTest {

    /** Setters for a number and for a decimal. */
    public static final class Amounts {
        String setter;

        public void setAmount(Number amount) {
            setter = "Number";
        }

        public void setAmount(BigDecimal amount) {
            setter = "BigDecimal";
        }
    }

    /** Setters for any object and for text. */
    public static final class Labels {
        String setter;

        public void setLabel(Object label) {
            setter = "Object";
        }

        public void setLabel(String label) {
            setter = "String";
        }
    }

    /** Setters for a collection and for a list. */
    public static final class Items {
        String setter;

        public void setItems(Collection<?> items) {
            setter = "Collection";
        }

        public void setItems(List<?> items) {
            setter = "List";
        }
    }

    /** Setters for any object, a character sequence and text. */
    public static final class Texts {
        String setter;

        public void setText(Object text) {
            setter = "Object";
        }

        public void setText(CharSequence text) {
            setter = "CharSequence";
        }

        public void setText(String text) {
            setter = "String";
        }
    }

    /** Setters for an int, an Integer and a long. */
    public static final class Counts {
        String setter;

        public void setCount(int count) {
            setter = "int";
        }

        public void setCount(Integer count) {
            setter = "Integer";
        }

        public void setCount(long count) {
            setter = "long";
        }
    }

    /** Setters for any object and for an int. */
    public static final class Sizes {
        String setter;

        public void setSize(Object size) {
            setter = "Object";
        }

        public void setSize(int size) {
            setter = "int";
        }
    }

    /** Setters for any object, a number and an Integer. */
    public static final class Scores {
        String setter;

        public void setScore(Object score) {
            setter = "Object";
        }

        public void setScore(Number score) {
            setter = "Number";
        }

        public void setScore(Integer score) {
            setter = "Integer";
        }
    }

    /** Setters for text and an Integer. */
    public static final class Values {
        String setter;

        public void setValue(String value) {
            setter = "String";
        }

        public void setValue(Integer value) {
            setter = "Integer";
        }
    }

    /** Setters for an int and a long. */
    public static final class Limits {
        String setter;

        public void setLimit(int limit) {
            setter = "int";
        }

        public void setLimit(long limit) {
            setter = "long";
        }
    }

    /** A type. */
    public interface Aaa {
    }

    /** A type unrelated to {@link Aaa}. */
    public interface Bbb {
    }

    /** A type more specific than {@link Aaa}. */
    public interface Ccc extends Aaa {
    }

    /** Setters for three interfaces, one extending another. */
    public static final class Shapes {
        String setter;

        public void setShape(Aaa shape) {
            setter = "Aaa";
        }

        public void setShape(Bbb shape) {
            setter = "Bbb";
        }

        public void setShape(Ccc shape) {
            setter = "Ccc";
        }
    }

    /** An object of two unrelated types. */
    public static final class AaaAndBbb implements Aaa, Bbb {
    }

    /** An object of the most specific type. */
    public static final class JustCcc implements Ccc {
    }

    /** A fluent setter. */
    public static class Parent {
        String name;

        public Parent setName(String name) {
            this.name = name;
            return this;
        }
    }

    /** A fluent setter overridden with a covariant return, so the class also has a bridge with the same parameter. */
    public static final class Child extends Parent {
        @Override
        public Child setName(String name) {
            super.setName(name);
            return this;
        }
    }

    /** A generic setter. */
    public static class Box<T> {
        public void setContent(T content) {
        }
    }

    /** A generic setter overridden for an Integer, so the class also has a bridge that takes any object. */
    public static final class IntegerBox extends Box<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer";
        }
    }

    /** A generic setter overridden for an Integer, beside an overload for text. */
    public static final class IntegerOrTextBox extends Box<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer";
        }

        public void setContent(String content) {
            setter = "String";
        }
    }

    /** A generic setter narrowed to a number. */
    public static class NumberBox<T extends Number> extends Box<T> {
        @Override
        public void setContent(T content) {
        }
    }

    /** A number setter overridden for an Integer, so the class has bridges for a number and for any object. */
    public static final class IntegerNumberBox extends NumberBox<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer";
        }
    }

    /** A number setter overridden for an Integer, beside an overload for text, which isn't a number. */
    public static final class IntegerNumberOrTextBox extends NumberBox<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer";
        }

        public void setContent(String content) {
            setter = "String";
        }
    }

    /** A generic setter overridden for an Integer, which it keeps as text through a private overload. */
    public static final class IntegerAsTextBox extends Box<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setContent(String.valueOf(content));
        }

        private void setContent(String content) {
            setter = "Integer as " + content;
        }
    }

    /** A generic setter overridden for an Integer, beside a static method of the same name for text. */
    public static final class IntegerBesideStaticBox extends Box<Integer> {
        static String parsed;
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer";
        }

        public static void setContent(String content) {
            parsed = content;
        }
    }

    /** A generic setter on an interface. */
    public interface Holder<T> {
        void setContent(T content);
    }

    /** A generic setter for a number on an interface. */
    public interface NumberHolder<T extends Number> {
        void setContent(T content);
    }

    /** Both interfaces' setters overridden for an Integer, so the class has bridges for a number and for any object. */
    public static final class IntegerForBothHolder implements Holder<Integer>, NumberHolder<Integer> {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer";
        }
    }

    /** A setter for an Integer. */
    public static class IntegerSetter {
        String setter;

        public void setContent(Integer content) {
            setter = "Integer";
        }
    }

    /** A class that inherits the generic setter's implementation, so it has a bridge but no override of its own. */
    public static final class InheritedIntegerHolder extends IntegerSetter implements Holder<Integer> {
    }

    /** A setter for an Integer, beside a protected, a static and a private method of the same name for other types. */
    public static class IntegerBesideOthers {
        static Long parsed;
        String setter;

        public void setContent(Integer content) {
            setContent(content.doubleValue());
        }

        protected void setContent(String content) {
            setter = "String";
        }

        public static void setContent(Long content) {
            parsed = content;
        }

        private void setContent(Double content) {
            setter = "Integer as " + content;
        }
    }

    /** A class that inherits the generic setter's implementation and the methods beside it. */
    public static final class InheritedBesideOthersHolder extends IntegerBesideOthers implements Holder<Integer> {
    }

    /** The generic setter overridden in an interface, so the interface has the bridge. */
    public interface IntegerHolder extends Holder<Integer> {
        @Override
        default void setContent(Integer content) {
            record("Integer");
        }

        void record(String setter);
    }

    /** A class that takes the generic setter's override, and its bridge, from an interface. */
    public static final class DefaultIntegerHolder implements IntegerHolder {
        String setter;

        @Override
        public void record(String setter) {
            this.setter = setter;
        }
    }

    /** A class that implements the generic interface but leaves its setter to a subclass. */
    public abstract static class PartialHolder implements Holder<Integer> {
    }

    /** The generic setter overridden in a subclass of a class that inherits it from the interface. */
    public static final class LateIntegerHolder extends PartialHolder {
        String setter;

        @Override
        public void setContent(Integer content) {
            setter = "Integer";
        }
    }

    /** A setter for text. */
    public static class TextNote {
        String setter;

        public void setNote(String note) {
            setter = "TextNote.setNote(String)";
        }
    }

    /** Not public: its public setter is callable through a public subclass only by the bridge javac adds there. */
    static class AnyNote extends TextNote {
        public void setNote(Object note) {
            setter = "AnyNote.setNote(Object)";
        }
    }

    /** A public class that inherits a setter from a class that isn't public, beside a narrower one it inherits. */
    public static final class VisibleNote extends AnyNote {
    }

    /** Not public: its public setter is callable through a public subclass only by the bridge javac adds there. */
    static class HiddenSetter {
        String setter;

        public void setX(Object x) {
            setter = "Hidden.Object";
        }
    }

    /** A public class with a narrower overload of the setter it inherits from a class that isn't public. */
    public static final class VisibleSetter extends HiddenSetter {
        public void setX(String x) {
            setter = "Visible.String";
        }
    }

    /** Not public, with a setter declared with a type variable. */
    abstract static class TypedNote<T> {
        String setter;

        public void setNote(T note) {
            setter = "TypedNote.setNote(T)";
        }
    }

    /** A public class that inherits that setter for text, through a bridge that takes any object. */
    public static final class TextTypedNote extends TypedNote<String> {
    }

    /** A public class that inherits that setter raw, through a bridge that takes any object. */
    @SuppressWarnings("rawtypes")
    public static final class RawTypedNote extends TypedNote {
    }

    /** Not public, with a setter declared with a type variable bounded by a character sequence. */
    abstract static class CharsNote<T extends CharSequence> {
        String setter;

        public void setNote(T note) {
            setter = "CharsNote.setNote(T)";
        }
    }

    /** A public class that inherits that setter for text, through a bridge that takes a character sequence. */
    public static final class TextCharsNote extends CharsNote<String> {
    }

    /** Not public, with a generic setter method. */
    static class AnyTypeNote {
        String setter;

        public <U> void setNote(U note) {
            setter = "AnyTypeNote.setNote(U)";
        }
    }

    /** A public class that inherits that setter, through a bridge that takes any object. */
    public static final class VisibleAnyTypeNote extends AnyTypeNote {
    }

    /** A setter for an array of a type variable. */
    public abstract static class TypedArray<T> {
        String setter;

        public abstract void setItems(T[] items);
    }

    /** That setter overridden for an array of text, so the class also has a bridge that takes any array of objects. */
    public static final class TextArray extends TypedArray<String> {
        @Override
        public void setItems(String[] items) {
            setter = "String[]";
        }
    }

    /** A setter for an array list. */
    public static class ArrayListNote {
        String setter;

        public void setList(ArrayList<?> list) {
            setter = "ArrayListNote.setList(ArrayList)";
        }
    }

    /** Not public, with a setter for a list of text, which a public subclass inherits through a bridge. */
    static class ListNote extends ArrayListNote {
        public void setList(List<String> list) {
            setter = "ListNote.setList(List)";
        }
    }

    /** A public class that inherits the setter for a list, beside the narrower one for an array list. */
    public static final class VisibleListNote extends ListNote {
    }

    /** A generic setter's override, beside a supplier's method, which has a bridge with no parameter. */
    public static final class SuppliedHolder implements Holder<String>, Supplier<String> {
        String setter;

        @Override
        public void setContent(String content) {
            setter = "String";
        }

        @Override
        public String get() {
            return "";
        }
    }

    /** A generic setter's override, beside a copy method with a narrower return type, which has a bridge. */
    public static final class CloneableHolder implements Holder<String>, Cloneable {
        String setter;

        @Override
        public void setContent(String content) {
            setter = "String";
        }

        @Override
        public CloneableHolder clone() {
            try {
                return (CloneableHolder) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** A method with no parameter. */
    public static class SelfBase {
        public Object getSelf() {
            return this;
        }
    }

    /** A generic setter's override, beside an override of that method with a narrower return type. */
    public static final class SelfHolder extends SelfBase implements Holder<String> {
        String setter;

        @Override
        public void setContent(String content) {
            setter = "String";
        }

        @Override
        public SelfHolder getSelf() {
            return this;
        }
    }

    /** A method with no parameter, of a setter's name. */
    public static class NamesakeBase {
        public Object setContent() {
            return null;
        }
    }

    /** A generic setter's override, beside a namesake with no parameter and a narrower return type, and its bridge. */
    public static final class NamesakeHolder extends NamesakeBase implements Holder<String> {
        String setter;

        @Override
        public void setContent(String content) {
            setter = "String";
        }

        @Override
        public String setContent() {
            return setter;
        }
    }

    /** A setter on an interface for any object. */
    public interface Labelled {
        void setLabel(Object label);
    }

    /** The interface's setter, beside one of the class's own for text. */
    public static final class LabelledLabels implements Labelled {
        String setter;

        @Override
        public void setLabel(Object label) {
            setter = "Object";
        }

        public void setLabel(String label) {
            setter = "String";
        }
    }

    /** What a failure for a number adds where the only setter that takes a primitive or a wrapper takes an Integer. */
    private static final String INTEGER_SETTER_EXISTS = " (setContent(java.lang.Integer) exists, but a value is only"
            + " widened as Java widens a primitive, never narrowed or converted)";

    private static String set(Object output, String property, Object value) throws Exception {
        OutputWriter.beansAndMaps().set(output, property, value);
        return (String) output.getClass().getDeclaredField("setter").get(output);
    }

    @Test
    @DisplayName("a BigDecimal goes to the BigDecimal setter, not the Number one")
    void subclassBeforeSuperclass() throws Exception {
        assertEquals("BigDecimal", set(new Amounts(), "amount", new BigDecimal("1.5")));
    }

    @Test
    @DisplayName("text goes to the String setter, not the Object one")
    void stringBeforeObject() throws Exception {
        assertEquals("String", set(new Labels(), "label", "x"));
    }

    @Test
    @DisplayName("an ArrayList goes to the List setter, not the Collection one")
    void subinterfaceBeforeInterface() throws Exception {
        assertEquals("List", set(new Items(), "items", new ArrayList<>()));
    }

    @Test
    @DisplayName("text goes to the String setter, not the CharSequence or Object one")
    void mostSpecificOfThree() throws Exception {
        assertEquals("String", set(new Texts(), "text", "x"));
    }

    @Test
    @DisplayName("an Integer goes to the Integer setter, not the int or long one")
    void wrapperBeforePrimitive() throws Exception {
        assertEquals("Integer", set(new Counts(), "count", 7));
    }

    @Test
    @DisplayName("an Integer goes to the Object setter before the int one, as Java calls it without unboxing")
    void referenceBeforePrimitive() throws Exception {
        assertEquals("Object", set(new Sizes(), "size", 7));
    }

    @Test
    @DisplayName("null goes to the String setter, not the Object one")
    void nullToTheMostSpecific() throws Exception {
        assertEquals("String", set(new Labels(), "label", null));
    }

    @Test
    @DisplayName("an Integer goes to the Integer setter, not the Number or Object one")
    void integerBeforeNumberAndObject() throws Exception {
        assertEquals("Integer", set(new Scores(), "score", 7));
    }

    @Test
    @DisplayName("null, which both of two unrelated setters accept, goes to the one fixed for the class")
    void nullBetweenUnrelatedSetters() throws Exception {
        assertEquals("Integer", set(new Values(), "value", null));
    }

    @Test
    @DisplayName("an Integer goes to the int setter, not the long one it also widens to")
    void wrappersOwnPrimitiveBeforeAWiderOne() throws Exception {
        assertEquals("int", set(new Limits(), "limit", 7));
    }

    @Test
    @DisplayName("a value of two unrelated types goes to the setter fixed for the class, never to a less specific one")
    void unrelatedInterfaces() throws Exception {
        // Ccc must come before Aaa, so no fixed order gives the first by name for every value; the order is Bbb, Ccc,
        // Aaa. Pinning it catches a regression to an order that depends on getMethods() or on a hash.
        assertEquals("Bbb", set(new Shapes(), "shape", new AaaAndBbb()));
        assertEquals("Ccc", set(new Shapes(), "shape", new JustCcc()));
    }

    @Test
    @DisplayName("a setter that overrides with a narrower return type, and its bridge, which share a parameter type,"
            + " still set the property")
    void sameParameterType() throws Exception {
        Child child = new Child();

        OutputWriter.beansAndMaps().set(child, "name", "x");

        assertEquals("x", child.name);
    }

    @Test
    @DisplayName("a value of another type isn't passed to an override through its bridge, which takes any object")
    void bridgeTakesOnlyTheOverridesType() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerBox(), "content", "text"));

        assertEquals(IntegerBox.class.getName() + " has no public method setContent that accepts a java.lang.String",
                thrown.getMessage());
        assertEquals("Integer", set(new IntegerBox(), "content", 5));
    }

    @Test
    @DisplayName("a value of another type isn't passed to an override through either of its two bridges")
    void twoBridges() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerNumberBox(), "content", 5L));

        assertEquals(IntegerNumberBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.Long" + INTEGER_SETTER_EXISTS, thrown.getMessage());
        assertEquals("Integer", set(new IntegerNumberBox(), "content", 1));
    }

    @Test
    @DisplayName("a value of another type isn't passed to an override of two interfaces' setters through either of its"
            + " bridges")
    void twoInterfacesBridges() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerForBothHolder(), "content", 5L));

        assertEquals(IntegerForBothHolder.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.Long" + INTEGER_SETTER_EXISTS, thrown.getMessage());
        assertEquals("Integer", set(new IntegerForBothHolder(), "content", 1));
    }

    @Test
    @DisplayName("an override and an overload beside it each take their own type")
    void overrideBesideAnOverload() throws Exception {
        assertEquals("String", set(new IntegerOrTextBox(), "content", "s"));
        assertEquals("Integer", set(new IntegerOrTextBox(), "content", 1));
    }

    @Test
    @DisplayName("a value that neither an override nor an overload beside it takes isn't passed through the bridge")
    void overrideBesideAnOverloadRefusesAThirdType() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerOrTextBox(), "content", 5L));

        assertEquals(IntegerOrTextBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.Long" + INTEGER_SETTER_EXISTS, thrown.getMessage());
    }

    @Test
    @DisplayName("an override and an overload beside it each take their own type, and neither of the override's two"
            + " bridges takes a third")
    void twoBridgesBesideAnOverload() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerNumberOrTextBox(), "content", 5L));

        assertEquals(IntegerNumberOrTextBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.Long" + INTEGER_SETTER_EXISTS, thrown.getMessage());
        assertEquals("String", set(new IntegerNumberOrTextBox(), "content", "s"));
        assertEquals("Integer", set(new IntegerNumberOrTextBox(), "content", 1));
    }

    @Test
    @DisplayName("a value that only a private overload beside an override takes isn't passed through the bridge")
    void privateOverloadBesideAnOverride() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerAsTextBox(), "content", "text"));

        assertEquals(IntegerAsTextBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
        assertEquals("Integer as 5", set(new IntegerAsTextBox(), "content", 5));
    }

    @Test
    @DisplayName("a value that only a static method beside an override takes isn't passed through the bridge")
    void staticMethodBesideAnOverride() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> set(new IntegerBesideStaticBox(), "content", "text"));

        assertEquals(IntegerBesideStaticBox.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
        assertNull(IntegerBesideStaticBox.parsed);
        assertEquals("Integer", set(new IntegerBesideStaticBox(), "content", 5));
    }

    @Test
    @DisplayName("a value of another type isn't passed through the bridge for an implementation the class inherits")
    void bridgeForAnInheritedImplementation() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(new InheritedIntegerHolder(), "content", "text"));

        assertEquals(InheritedIntegerHolder.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
        InheritedIntegerHolder holder = new InheritedIntegerHolder();
        OutputWriter.beansAndMaps().set(holder, "content", 5);
        assertEquals("Integer", holder.setter);
    }

    @Test
    @DisplayName("a value that only a protected, a static or a private method beside an inherited implementation"
            + " takes isn't passed through the bridge")
    void bridgeForAnInheritedImplementationBesideOtherMethods() throws Exception {
        for (Object value : List.of("text", 5L, 1.5)) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> OutputWriter.beansAndMaps().set(new InheritedBesideOthersHolder(), "content", value));

            // The static setContent(Long) isn't a setter, so the note for a number names only setContent(Integer).
            assertEquals(InheritedBesideOthersHolder.class.getName() + " has no public method setContent that"
                    + " accepts a " + value.getClass().getName() + (value instanceof Number ? INTEGER_SETTER_EXISTS
                    : ""), thrown.getMessage());
        }
        assertNull(IntegerBesideOthers.parsed);
        InheritedBesideOthersHolder holder = new InheritedBesideOthersHolder();
        OutputWriter.beansAndMaps().set(holder, "content", 5);
        assertEquals("Integer as 5.0", holder.setter);
    }

    @Test
    @DisplayName("a value of another type isn't passed through a bridge that an interface has for its override")
    void bridgeInAnInterface() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(new DefaultIntegerHolder(), "content", "text"));

        assertEquals(DefaultIntegerHolder.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
        assertEquals("Integer", set(new DefaultIntegerHolder(), "content", 5));
    }

    @Test
    @DisplayName("a value of another type isn't passed through the bridge of an override whose superclass inherits the"
            + " generic setter from an interface")
    void bridgeBelowAClassThatInheritsTheGenericSetter() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(new LateIntegerHolder(), "content", "text"));

        assertEquals(LateIntegerHolder.class.getName() + " has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
        assertEquals("Integer", set(new LateIntegerHolder(), "content", 5));
    }

    @Test
    @DisplayName("a value only a setter inherited from a class that isn't public takes goes through its bridge, beside"
            + " a narrower setter inherited from further up")
    void inheritedThroughAVisibilityBridgeBesideANarrowerSetter() throws Exception {
        VisibleNote note = new VisibleNote();

        OutputWriter.beansAndMaps().set(note, "note", 42);

        assertEquals("AnyNote.setNote(Object)", note.setter);
        OutputWriter.beansAndMaps().set(note, "note", "s");
        assertEquals("TextNote.setNote(String)", note.setter);
    }

    @Test
    @DisplayName("a setter declared with a type variable in a class that isn't public is written through the bridge"
            + " of a public subclass")
    void visibilityBridgeForATypeVariable() throws Exception {
        TextTypedNote typed = new TextTypedNote();
        OutputWriter.beansAndMaps().set(typed, "note", "hi");
        assertEquals("TypedNote.setNote(T)", typed.setter);

        RawTypedNote raw = new RawTypedNote();
        OutputWriter.beansAndMaps().set(raw, "note", "hi");
        assertEquals("TypedNote.setNote(T)", raw.setter);

        TextCharsNote chars = new TextCharsNote();
        OutputWriter.beansAndMaps().set(chars, "note", "hi");
        assertEquals("CharsNote.setNote(T)", chars.setter);

        VisibleAnyTypeNote any = new VisibleAnyTypeNote();
        OutputWriter.beansAndMaps().set(any, "note", "hi");
        assertEquals("AnyTypeNote.setNote(U)", any.setter);
    }

    @Test
    @DisplayName("an array of another type isn't passed to an override for an array through its bridge")
    void bridgeForAnArrayOfATypeVariable() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(new TextArray(), "items", new Integer[] {1}));

        assertEquals(TextArray.class.getName() + " has no public method setItems that accepts a [Ljava.lang.Integer;",
                thrown.getMessage());
        TextArray items = new TextArray();
        OutputWriter.beansAndMaps().set(items, "items", new String[] {"a"});
        assertEquals("String[]", items.setter);
    }

    @Test
    @DisplayName("a list goes through the bridge to a setter for a list of text in a class that isn't public, beside a"
            + " narrower one for an array list")
    void visibilityBridgeForAParameterizedType() throws Exception {
        VisibleListNote note = new VisibleListNote();

        OutputWriter.beansAndMaps().set(note, "list", new LinkedList<String>());

        assertEquals("ListNote.setList(List)", note.setter);
        OutputWriter.beansAndMaps().set(note, "list", new ArrayList<String>());
        assertEquals("ArrayListNote.setList(ArrayList)", note.setter);
    }

    @Test
    @DisplayName("a generic setter's override is written beside a bridge with no parameter")
    void bridgeWithNoParameterBesideAGenericSetter() throws Exception {
        assertEquals("String", set(new SuppliedHolder(), "content", "s"));
        assertEquals("String", set(new CloneableHolder(), "content", "s"));
        assertEquals("String", set(new SelfHolder(), "content", "s"));
        assertEquals("String", set(new NamesakeHolder(), "content", "s"));
    }

    @Test
    @DisplayName("plain Java calls the inherited Object setter for an Integer, beside the class's own String setter")
    void javaCallsTheInheritedSetter() {
        VisibleSetter output = new VisibleSetter();

        output.setX(42);

        assertEquals("Hidden.Object", output.setter);
    }

    @Test
    @DisplayName("text goes to the class's own String setter, not the Object one it inherits from a class that isn't"
            + " public")
    void textToTheOwnSetterBesideAVisibilityBridge() throws Exception {
        VisibleSetter output = new VisibleSetter();

        OutputWriter.beansAndMaps().set(output, "x", "s");

        assertEquals("Visible.String", output.setter);
    }

    @Test
    @DisplayName("an Integer goes to the Object setter inherited from a class that isn't public, as Java calls it")
    void integerThroughAVisibilityBridge() throws Exception {
        VisibleSetter output = new VisibleSetter();

        OutputWriter.beansAndMaps().set(output, "x", 42);

        assertEquals("Hidden.Object", output.setter);
    }

    @Test
    @DisplayName("text goes to the class's String setter, not the Object one its interface declares")
    void stringBeforeAnInterfacesObject() throws Exception {
        assertEquals("String", set(new LabelledLabels(), "label", "x"));
        assertEquals("Object", set(new LabelledLabels(), "label", 1));
    }

    @Test
    @DisplayName("through the engine, each property an action returns goes to its most specific setter")
    void throughTheEngine() {
        ActionResult result = ActionResult.set(Map.of("amount", new BigDecimal("1.5"), "label", "x"));
        RulesEngine<Receipt> engine = RulesEngineBuilder.<Receipt>allMatches(Receipt::new).language(returning(result))
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").priority(1).language("fixed").condition("c").action("a")
                .build()));

        Receipt receipt = engine.run(new FactMap<>());

        assertEquals("BigDecimal", receipt.amountSetter);
        assertEquals("String", receipt.labelSetter);
    }

    @Test
    @DisplayName("through the engine, a value of another type for an override fails the rule, as no setter accepts"
            + " it")
    void bridgeThroughTheEngine() {
        ActionResult result = ActionResult.set(Map.of("content", "text"));
        RulesEngine<IntegerBox> engine = RulesEngineBuilder.<IntegerBox>allMatches(IntegerBox::new)
                .language(returning(result)).build();
        engine.load(List.of(Rule.builder().ruleName("r").priority(1).language("fixed").condition("c").action("a")
                .build()));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        String reason = IntegerBox.class.getName() + " has no public method setContent that accepts a java.lang.String";
        assertEquals("Failed to set 'content' on the output for rule 'r': " + reason, thrown.getMessage());
        assertEquals("r", thrown.getRuleName());
        assertEquals(ExpressionKind.ACTION, thrown.getExpressionKind());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertEquals(reason, thrown.getCause().getMessage());
    }

    /** A language named {@code fixed} whose conditions are all true and whose actions all return the result. */
    private static ExpressionLanguage returning(ActionResult result) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (action, session) -> result;
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    /** An output with two overloaded setters, as an engine's rules set them. */
    public static final class Receipt {
        String amountSetter;
        String labelSetter;

        public void setAmount(Number amount) {
            amountSetter = "Number";
        }

        public void setAmount(BigDecimal amount) {
            amountSetter = "BigDecimal";
        }

        public void setLabel(Object label) {
            labelSetter = "Object";
        }

        public void setLabel(String label) {
            labelSetter = "String";
        }
    }
}
