package io.github.brantunger.unruly.api;

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
    @DisplayName("through the engine, each property an action returns goes to its most specific setter")
    void throughTheEngine() {
        ActionResult result = ActionResult.set(Map.of("amount", new BigDecimal("1.5"), "label", "x"));
        ExpressionLanguage language = new ExpressionLanguage() {
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
        RulesEngine<Receipt> engine = RulesEngineBuilder.<Receipt>allMatches(Receipt::new).language(language).build();
        engine.load(List.of(Rule.builder().ruleName("r").priority(1).language("fixed").condition("c").action("a")
                .build()));

        Receipt receipt = engine.run(new FactMap<>());

        assertEquals("BigDecimal", receipt.amountSetter);
        assertEquals("String", receipt.labelSetter);
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
