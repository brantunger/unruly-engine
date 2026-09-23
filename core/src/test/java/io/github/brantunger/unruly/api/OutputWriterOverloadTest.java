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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Of an output's overloaded setters that accept a value, the default {@link OutputWriter} calls the most specific,
 * as Java would, and not the one whose parameter type's name sorts first (#503).
 * A bridge method the compiler adds for an override takes only the types the override does (#525).
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
    @DisplayName("an Integer goes to the int setter, as no primitive setter but its own accepts it")
    void onlyTheWrappersPrimitive() throws Exception {
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
                + " java.lang.Long", thrown.getMessage());
        assertEquals("Integer", set(new IntegerNumberBox(), "content", 1));
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
                + " java.lang.Long", thrown.getMessage());
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
