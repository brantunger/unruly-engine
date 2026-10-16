package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.OutputConverter;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.DoubleNumbers;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine gives its writer the rule's language's conversion of a value an action returned, so a language whose
 * numbers are doubles sets an {@code int} property with a whole {@link Double}, and refuses a fraction (#1127). A
 * language without a conversion, a custom writer, and a value a setter takes as it is behave as before.
 */
@DisplayName("the engine has the rule's language convert a value the action returned for the output's setter")
class OutputConversionTest {

    /** The default writer's failure for a Double that no setter takes, after the output class's name. */
    private static final String NO_SETTER_FOR_A_DOUBLE = " has no public method setCount that accepts a"
            + " java.lang.Double (setCount(int) exists, but a value is only widened as Java widens a primitive, never"
            + " narrowed or converted)";

    /** An order whose count is an int. */
    public static final class IntOrder {
        private int count;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    /** An order whose count is a long. */
    public static final class LongOrder {
        private long count;

        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }
    }

    /** An order whose count is an Integer, and starts as 7, so a null set shows. */
    public static final class BoxedOrder {
        private Integer count = 7;

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }
    }

    /** An order with a setter for an int, a long and an Integer, which records the one called. */
    public static final class Order {
        private String setter;

        public String getSetter() {
            return setter;
        }

        public void setCount(int count) {
            setter = "int " + count;
        }

        public void setCount(long count) {
            setter = "long " + count;
        }

        public void setCount(Integer count) {
            setter = "Integer " + count;
        }
    }

    /** The calls a language's hook received, as {@code value -> target}. */
    private final List<String> hookCalls = new ArrayList<>();

    /**
     * A language whose conditions are always true, and whose action {@code count=VALUE} returns the value as a
     * property: {@code null}, an integer for a whole literal, and a double for any other, such as {@code 1.5},
     * {@code 3.0E9} or {@code NaN}. Its compiler converts the value with {@code hook}, recording each call, or has no
     * conversion when {@code hook} is {@code null}.
     */
    private ExpressionLanguage language(BiFunction<Object, Class<?>, Object> hook) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "doubles";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return hook == null ? new DoublesCompiler() : new DoublesCompiler() {
                    @Override
                    public Object convertOutput(Object value, Class<?> target) {
                        hookCalls.add(value + " -> " + target.getTypeName());
                        return hook.apply(value, target);
                    }
                };
            }
        };
    }

    /** The compiler of {@link #language}, without a conversion. */
    private static class DoublesCompiler implements ExpressionCompiler {
        @Override
        public CompiledCondition compileCondition(Expression source) {
            return (evaluation, session) -> true;
        }

        @Override
        public CompiledAction compileAction(Expression source) {
            String literal = source.text().substring("count=".length());
            Object value;
            if ("null".equals(literal)) {
                value = null;
            } else if (literal.matches("-?\\d+")) {
                value = Integer.valueOf(literal);
            } else {
                value = Double.valueOf(literal);
            }
            Map<String, Object> properties = new HashMap<>();
            properties.put("count", value);
            ActionResult result = ActionResult.set(properties);
            return (action, session) -> result;
        }

        @Override
        public Session newSession() {
            return Session.none();
        }
    }

    private static Rule rule(String literal) {
        return Rule.builder().ruleName("count").condition("true").action("count=" + literal).language("doubles")
                .build();
    }

    private <O> RulesEngineBuilder<O> builder(Supplier<O> output, BiFunction<Object, Class<?>, Object> hook) {
        return RulesEngineBuilder.firstMatch(output).language(language(hook));
    }

    /** Runs the one rule {@code count=literal} on an engine the builder builds, and returns the output. */
    private static <O> O run(RulesEngineBuilder<O> builder, String literal) {
        try (RulesEngine<O> engine = builder.build()) {
            engine.load(List.of(rule(literal)));
            return engine.run(new FactMap<>());
        }
    }

    private static <O> RuleExecutionException failure(RulesEngineBuilder<O> builder, String literal) {
        return assertThrows(RuleExecutionException.class, () -> run(builder, literal));
    }

    @Test
    @DisplayName("a whole Double sets setCount(int), setCount(long) and setCount(Integer), converted by the language")
    void wholeDoubleConverted() {
        assertAll(
                () -> assertEquals(3, run(builder(IntOrder::new, DoubleNumbers::convert), "3.0").getCount()),
                () -> assertEquals(3L, run(builder(LongOrder::new, DoubleNumbers::convert), "3.0").getCount()),
                () -> assertEquals(3, run(builder(BoxedOrder::new, DoubleNumbers::convert), "3.0").getCount()));
    }

    @Test
    @DisplayName("of overloaded setters, the first the language converts for is called: the Integer one, as the most"
            + " specific, for a whole Double")
    void mostSpecificSetterConvertedFirst() {
        assertEquals("Integer 3", run(builder(Order::new, DoubleNumbers::convert), "3.0").getSetter());
        assertEquals(List.of("3.0 -> java.lang.Integer"), hookCalls);
    }

    @Test
    @DisplayName("a Double the language refuses for the int setter, as too large, reaches the long setter")
    void refusalTriesTheNextSetter() {
        assertEquals("long 3000000000", run(builder(Order::new, DoubleNumbers::convert), "3.0E9").getSetter());
        assertEquals(List.of("3.0E9 -> java.lang.Integer", "3.0E9 -> int", "3.0E9 -> long"), hookCalls);
    }

    @Test
    @DisplayName("a fraction, NaN, an infinity, a number too large for an int and one beyond the language's exact range"
            + " for a long fail the rule with the language's refusal, naming the rule and the property")
    void refusalsFailTheRule() {
        RuleExecutionException fraction = failure(builder(IntOrder::new, DoubleNumbers::convert), "1.5");
        RuleExecutionException nan = failure(builder(IntOrder::new, DoubleNumbers::convert), "NaN");
        RuleExecutionException infinity = failure(builder(IntOrder::new, DoubleNumbers::convert), "Infinity");
        RuleExecutionException overflow = failure(builder(IntOrder::new, DoubleNumbers::convert), "3.0E9");
        RuleExecutionException inexact = failure(builder(LongOrder::new, DoubleNumbers::convert),
                Double.toString(DoubleNumbers.EXACT + 2));

        assertAll(
                () -> assertEquals("Failed to set 'count' on the output for rule 'count': 1.5 can't be converted to"
                        + " int: not a whole number", fraction.getMessage()),
                () -> assertEquals("count", fraction.getRuleName()),
                () -> assertInstanceOf(IllegalArgumentException.class, fraction.getCause()),
                () -> assertEquals("Failed to set 'count' on the output for rule 'count': NaN can't be converted to"
                        + " int: not a whole number", nan.getMessage()),
                () -> assertEquals("Failed to set 'count' on the output for rule 'count': Infinity can't be converted"
                        + " to int: not a whole number", infinity.getMessage()),
                () -> assertEquals("Failed to set 'count' on the output for rule 'count': 3.0E9 can't be converted"
                        + " to int: out of range", overflow.getMessage()),
                () -> assertEquals("Failed to set 'count' on the output for rule 'count': 9.007199254740994E15 can't"
                        + " be converted to long: out of range", inexact.getMessage()));
    }

    @Test
    @DisplayName("with overloaded setters that all refuse, the first refusal fails the rule")
    void firstRefusalFailsTheRule() {
        RuleExecutionException failure = failure(builder(Order::new, DoubleNumbers::convert), "1.5");

        assertEquals("Failed to set 'count' on the output for rule 'count': 1.5 can't be converted to"
                + " java.lang.Integer: not a whole number", failure.getMessage());
        assertEquals(List.of("1.5 -> java.lang.Integer", "1.5 -> int", "1.5 -> long"), hookCalls);
    }

    @Test
    @DisplayName("a language without a conversion fails as before, with the writer's message")
    void noConversionFailsAsBefore() {
        RuleExecutionException failure = failure(builder(IntOrder::new, null), "3.0");

        assertEquals("Failed to set 'count' on the output for rule 'count': " + IntOrder.class.getName()
                + NO_SETTER_FOR_A_DOUBLE, failure.getMessage());
    }

    @Test
    @DisplayName("a conversion the setter doesn't take, text for an int, fails as before, with the writer's message")
    void unacceptedConversionFailsAsBefore() {
        RuleExecutionException failure = failure(builder(IntOrder::new, (value, target) -> value.toString()), "3.0");

        assertEquals("Failed to set 'count' on the output for rule 'count': " + IntOrder.class.getName()
                + NO_SETTER_FOR_A_DOUBLE, failure.getMessage());
        assertEquals(List.of("3.0 -> int"), hookCalls);
    }

    @Test
    @DisplayName("a value a setter takes as it is, or widened, is never converted")
    void acceptedValueNotConverted() {
        assertEquals(5L, run(builder(LongOrder::new, DoubleNumbers::convert), "5").getCount());
        assertEquals(List.of(), hookCalls);
    }

    @Test
    @DisplayName("null is set as it is, and never converted")
    void nullNotConverted() {
        assertNull(run(builder(BoxedOrder::new, DoubleNumbers::convert), "null").getCount());
        assertEquals(List.of(), hookCalls);
    }

    @Test
    @DisplayName("a Map output takes the Double as it is, and nothing is converted")
    void mapOutputNotConverted() {
        Map<String, Object> output = run(builder(HashMap<String, Object>::new, DoubleNumbers::convert), "3.0");

        assertEquals(Map.of("count", 3.0), output);
        assertEquals(List.of(), hookCalls);
    }

    @Test
    @DisplayName("a writer written as a lambda is given the value as the action returned it")
    void lambdaWriterSeesTheOriginalValue() {
        List<Object> written = new ArrayList<>();
        RulesEngineBuilder<IntOrder> builder = builder(IntOrder::new, DoubleNumbers::convert)
                .outputWriter((output, property, value) -> written.add(value));

        run(builder, "3.0");

        assertEquals(List.of(3.0), written);
        assertEquals(List.of(), hookCalls);
    }

    @Test
    @DisplayName("a writer that overrides the four-argument set is given the language's converter, which returns null"
            + " for null without asking the language")
    void overridingWriterGetsTheConverter() {
        List<Object> converted = new ArrayList<>();
        OutputWriter<IntOrder> writer = new OutputWriter<>() {
            @Override
            public void set(IntOrder output, String property, Object value) {
                throw new AssertionError("the engine called the three-argument set");
            }

            @Override
            public void set(IntOrder output, String property, Object value, OutputConverter converter) {
                converted.add(converter.convert(value, int.class));
                converted.add(converter.convert(null, int.class));
                converted.add(converter.convert(value, String.class));
            }
        };
        RulesEngineBuilder<IntOrder> builder = builder(IntOrder::new, DoubleNumbers::convert).outputWriter(writer);

        run(builder, "3.0");

        assertEquals(Arrays.asList(3, null, 3.0), converted);
        assertEquals(List.of("3.0 -> int", "3.0 -> java.lang.String"), hookCalls);
    }
}
