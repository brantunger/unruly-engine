package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact declared with a primitive type accepts a boxed primitive that Java widens to that type (JLS 5.1.2), lossy
 * conversions included, and the engine converts it to the type's wrapper before the run starts, so listeners and
 * languages see only the declared type. A wrapper type converts nothing, as Java never converts one wrapper to
 * another (#571).
 */
@DisplayName("a fact declared with a primitive type widens a boxed primitive as Java would")
class DeclaredFactWideningTest {

    private static final String ONLY_WIDENED =
            " (a value is only widened as Java widens a primitive, never narrowed or converted)";

    /** Puts the fact's value into the output, as the language sees it. */
    private static final Rule RULE = Rule.builder().ruleName("r").condition("true").action("put v n").build();

    // One value of each wrapper; the Integer, the Long and the Character show the lossy and the char conversions.
    private static final Byte BYTE = (byte) 5;
    private static final Short SHORT = (short) 300;
    private static final Character CHAR = 'a';
    private static final Integer INT = 16_777_217;
    private static final Long LONG = Long.MAX_VALUE;
    private static final Float FLOAT = 1.5f;
    private static final Double DOUBLE = 2.5;
    private static final Boolean BOOLEAN = true;

    private static RulesEngine<Map<String, Object>> engine(
            UnaryOperator<RulesEngineBuilder<Map<String, Object>>> configuration) {
        RulesEngine<Map<String, Object>> engine =
                configuration.apply(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                        .language(new ToyExpressionLanguage())).build();
        engine.load(List.of(RULE));
        return engine;
    }

    private static Object seenByTheLanguage(Class<?> declared, Object value) {
        return engine(builder -> builder.fact("n", declared)).run(new FactMap<>(new Fact<>("n", value))).get("v");
    }

    /**
     * Every wrapper against every primitive declaration: what a run's value becomes, written out from JLS 5.1.2, or
     * {@code null} where the run is rejected.
     */
    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        // Declared byte: nothing widens to a byte.
        add(cases, byte.class, (byte) 5, null, null, null, null, null, null, null);
        // Declared short: only a byte widens to it; a char never does.
        add(cases, short.class, (short) 5, (short) 300, null, null, null, null, null, null);
        // Declared char: nothing widens to a char, not a byte or a short.
        add(cases, char.class, null, null, 'a', null, null, null, null, null);
        add(cases, int.class, 5, 300, 97, 16_777_217, null, null, null, null);
        add(cases, long.class, 5L, 300L, 97L, 16_777_217L, Long.MAX_VALUE, null, null, null);
        // 16,777,217 is 2^24 + 1, the first int a float can't hold: it rounds to 2^24.
        add(cases, float.class, 5f, 300f, 97f, 1.6777216E7f, 9.223372E18f, 1.5f, null, null);
        add(cases, double.class, 5.0, 300.0, 97.0, 1.6777217E7, 9.223372036854776E18, 1.5, 2.5, null);
        // Declared boolean: a boolean widens to nothing, and nothing widens to it.
        add(cases, boolean.class, null, null, null, null, null, null, null, true);
        return cases.stream();
    }

    private static void add(List<Arguments> cases, Class<?> declared, Object fromByte, Object fromShort,
                            Object fromChar, Object fromInt, Object fromLong, Object fromFloat, Object fromDouble,
                            Object fromBoolean) {
        cases.add(Arguments.of(declared, BYTE, fromByte));
        cases.add(Arguments.of(declared, SHORT, fromShort));
        cases.add(Arguments.of(declared, CHAR, fromChar));
        cases.add(Arguments.of(declared, INT, fromInt));
        cases.add(Arguments.of(declared, LONG, fromLong));
        cases.add(Arguments.of(declared, FLOAT, fromFloat));
        cases.add(Arguments.of(declared, DOUBLE, fromDouble));
        cases.add(Arguments.of(declared, BOOLEAN, fromBoolean));
    }

    @ParameterizedTest(name = "declared {0}, supplied {1}: {2}")
    @MethodSource("matrix")
    @DisplayName("each wrapper, against each primitive declaration, becomes what Java widens it to, or is rejected")
    void matrix(Class<?> declared, Object supplied, Object expected) {
        if (expected == null) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> seenByTheLanguage(declared, supplied));
            assertEquals("Fact 'n' was declared as " + declared.getName() + ", but the run supplied a "
                    + supplied.getClass().getName() + ONLY_WIDENED, thrown.getMessage());
            return;
        }
        Object seen = seenByTheLanguage(declared, supplied);
        assertEquals(expected.getClass(), seen.getClass());
        assertEquals(expected, seen);
    }

    @Test
    @DisplayName("a fact declared long accepts an Integer, and the language sees a Long")
    void longAcceptsAnInteger() {
        Object seen = seenByTheLanguage(long.class, 5);

        assertEquals(Long.class, seen.getClass());
        assertEquals(5L, seen);
    }

    @Test
    @DisplayName("a fact declared double accepts an Integer, and the language sees a Double")
    void doubleAcceptsAnInteger() {
        Object seen = seenByTheLanguage(double.class, 5);

        assertEquals(Double.class, seen.getClass());
        assertEquals(5.0, seen);
    }

    @Test
    @DisplayName("the value is widened before beforeRun, so a listener sees the Long there")
    void beforeRunSeesTheWidenedValue() {
        AtomicReference<Object> seen = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("n", long.class)
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        seen.set(run.facts().get("n"));
                    }
                }));

        engine.run(new FactMap<>(new Fact<>("n", 5)));

        assertEquals(Long.class, seen.get().getClass());
        assertEquals(5L, seen.get());
    }

    @Test
    @DisplayName("RunContext.facts() gives the widened value")
    void runContextFactsHaveTheWidenedValue() {
        AtomicReference<Map<String, Object>> facts = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("n", long.class)
                .listener(new RuleListener() {
                    @Override
                    public void afterRun(RunContext run, RunResult<?> result) {
                        facts.set(run.facts());
                    }
                }));

        engine.run(new FactMap<>(new Fact<>("n", 5)));

        assertEquals(Map.of("n", 5L), facts.get());
        assertEquals(Long.class, facts.get().get("n").getClass());
    }

    @Test
    @DisplayName("a value Java doesn't widen to the primitive type fails the run, naming the type as declared")
    void narrowingIsRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(long.class, 5.0));

        assertEquals("Fact 'n' was declared as long, but the run supplied a java.lang.Double (a value is only widened"
                + " as Java widens a primitive, never narrowed or converted)", thrown.getMessage());
    }

    @Test
    @DisplayName("a BigInteger or a BigDecimal is never converted to a primitive type")
    void bigNumbersAreNotConverted() {
        IllegalArgumentException big = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(long.class, BigInteger.ONE));
        IllegalArgumentException decimal = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(double.class, BigDecimal.ONE));

        assertEquals("Fact 'n' was declared as long, but the run supplied a java.math.BigInteger" + ONLY_WIDENED,
                big.getMessage());
        assertEquals("Fact 'n' was declared as double, but the run supplied a java.math.BigDecimal" + ONLY_WIDENED,
                decimal.getMessage());
    }

    @Test
    @DisplayName("a Boolean for a fact declared long fails with the clause, as a number or a character would")
    void booleanGetsTheClause() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(long.class, true));

        assertEquals("Fact 'n' was declared as long, but the run supplied a java.lang.Boolean" + ONLY_WIDENED,
                thrown.getMessage());
    }

    @Test
    @DisplayName("a BigDecimal for a fact declared long fails with the clause: it's a number, never converted")
    void bigDecimalForALongGetsTheClause() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(long.class, BigDecimal.TEN));

        assertEquals("Fact 'n' was declared as long, but the run supplied a java.math.BigDecimal" + ONLY_WIDENED,
                thrown.getMessage());
    }

    @Test
    @DisplayName("a String for a fact declared int fails without the clause, naming the type as declared")
    void stringGetsNoClause() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(int.class, "five"));

        assertEquals("Fact 'n' was declared as int, but the run supplied a java.lang.String", thrown.getMessage());
    }

    // ---- guards: what didn't change ----

    @Test
    @DisplayName("a fact declared Long still rejects an Integer, with the same message: Java converts no wrapper")
    void wrapperDeclarationConvertsNothing() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(Long.class, 5));

        assertEquals("Fact 'n' was declared as java.lang.Long, but the run supplied a java.lang.Integer",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a fact declared long given a Long runs with the same Long")
    void ownWrapperIsKept() {
        Long value = 5_000_000_000L;

        assertSame(value, seenByTheLanguage(long.class, value));
    }

    @Test
    @DisplayName("a null value for a primitive declaration passes and stays null")
    void nullIsUnchanged() {
        Map<String, Object> output = engine(builder -> builder.fact("n", long.class))
                .run(new FactMap<>(new Fact<>("n", null)));

        assertTrue(output.containsKey("v"));
        assertNull(output.get("v"));
    }

    @Test
    @DisplayName("the caller's fact store still holds the Integer after the run")
    void factStoreIsUnchanged() {
        FactStore<Object> store = new FactMap<>(new Fact<>("n", 5));

        engine(builder -> builder.fact("n", long.class)).run(store);

        assertEquals(Integer.class, store.getValue("n").getClass());
    }

    @Test
    @DisplayName("with requireDeclaredFacts, a widened fact counts as supplied and a missing one still fails")
    void requireDeclaredFactsIsUnaffected() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("n", long.class)
                .requireDeclaredFacts());

        assertEquals(5L, engine.run(new FactMap<>(new Fact<>("n", 5))).get("v"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>()));
        assertEquals("Fact 'n' was declared, but the run didn't supply it, and this engine was built with "
                + "requireDeclaredFacts()", thrown.getMessage());
    }

    @Test
    @DisplayName("an engine with no declared facts passes an Integer through as it is")
    void noDeclarationsConvertsNothing() {
        Integer value = 5;
        Map<String, Object> output = engine(UnaryOperator.identity()).run(new FactMap<>(new Fact<>("n", value)));

        assertSame(value, output.get("v"));
    }

    @Test
    @DisplayName("a fact declared with a primitive type that the run leaves out stays left out")
    void missingPrimitiveFactStaysMissing() {
        AtomicReference<Map<String, Object>> facts = new AtomicReference<>();
        Map<String, Object> output = engine(builder -> builder.fact("m", long.class)
                .listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        facts.set(run.facts());
                    }
                }))
                .run(new FactMap<>(new Fact<>("n", 5)));

        assertFalse(facts.get().containsKey("m"));
        assertEquals(Map.of("n", 5), facts.get());
        assertSame(5, output.get("v"));
    }

    @Test
    @DisplayName("a fact declared void rejects every value, naming void, without the clause: nothing widens to it")
    void voidIsNotWidened() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> seenByTheLanguage(void.class, 5));

        assertEquals("Fact 'n' was declared as void, but the run supplied a java.lang.Integer", thrown.getMessage());
    }
}
