package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mvel2.optimizers.dynamic.DynamicOptimizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine makes MVEL's reflective optimizer the JVM's default, so calls that the accessors MVEL's JIT generates
 * get wrong work on every run. The JIT compiled an accessor once more than 50 runs had used it within 100 ms: a call
 * of an interface's static method then failed every later run with an {@link IncompatibleClassChangeError} (#1161),
 * and a call given a class name, a {@code 10B} or a {@code 10I} as an {@code Object} argument failed the run it was
 * compiled in with a {@link VerifyError} (#1122). The window is widened for these tests, so the JIT, were it on,
 * would compile each accessor after its 50th run however slow the runs are. A rule's failure has what its Java code
 * threw as its cause on every run too, as MVEL keeps calling the code through reflection.
 */
@DisplayName("the engine's MVEL rules don't fail once they have run more than 50 times quickly (#1161, #1122)")
class ReflectiveOptimizerTest {

    /** Many more than the 51 runs after which MVEL's JIT compiled an accessor. */
    private static final int RUNS = 200;
    /**
     * The runs in which the rule's code throws: the first ones, and ones after those the JIT would compile the
     * expression in. Not the runs about the JIT's threshold, 50 to 53: a run that fails there, as the JIT compiles the
     * expression, leaves the accessor reflective, so the JIT, were it on, would never call the code.
     */
    private static final Set<Integer> FAILING_RUNS = Set.of(0, 1, 3, 4, 100, 500, 999);

    private static long timeSpan;

    @BeforeAll
    static void widenJitWindow() {
        timeSpan = DynamicOptimizer.timeSpan;
        DynamicOptimizer.timeSpan = Long.MAX_VALUE;
    }

    @AfterAll
    static void restoreJitWindow() {
        DynamicOptimizer.timeSpan = timeSpan;
    }

    /**
     * Runs the rule {@link #RUNS} times, and returns each run that didn't give {@code expected}, with what it gave or
     * threw.
     */
    private static List<String> wrongRuns(Rule rule, Map<?, ?> expected, String... imports) {
        List<String> wrong = new ArrayList<>();
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).imports(imports).build()) {
            engine.load(List.of(rule));
            for (int run = 1; run <= RUNS; run++) {
                try {
                    Map<String, Object> output = engine.run(new FactMap<>());
                    if (!expected.equals(output)) {
                        wrong.add("run " + run + ": " + output);
                    }
                } catch (RuntimeException e) {
                    wrong.add("run " + run + ": " + e.getMessage());
                }
            }
        }
        return wrong;
    }

    static Stream<Arguments> interfaceStaticCalls() {
        return Stream.of(
                Arguments.of("java.util.List.of(1, 2, 3).size() == 3", new String[0]),
                Arguments.of("java.util.List.of(1).size() == 1", new String[0]),
                Arguments.of("java.util.Set.of(1, 2).size() == 2", new String[0]),
                Arguments.of("java.util.Map.of('a', 1).size() == 1", new String[0]),
                Arguments.of("java.util.stream.Stream.of(1, 2).count() == 2", new String[0]),
                Arguments.of("java.util.Comparator.naturalOrder() != null", new String[0]),
                Arguments.of("List.of(1, 2, 3).size() == 3", new String[] {"java.util.List"}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interfaceStaticCalls")
    @DisplayName("a condition calling an interface's static method holds on every run (#1161)")
    void interfaceStaticCall(String condition, String[] imports) {
        Rule rule = Rule.builder().ruleName("r").condition(condition).action("output.put('k', 1)").build();

        assertEquals(List.of(), wrongRuns(rule, Map.of("k", 1), imports));
    }

    static Stream<Arguments> objectArguments() {
        return Stream.of(
                Arguments.of("output.put('r', String)", Map.of("r", String.class), new String[0]),
                Arguments.of("output.put('r', java.util.UUID)", Map.of("r", UUID.class), new String[0]),
                Arguments.of("output.put('r', UUID)", Map.of("r", UUID.class), new String[] {"java.util.UUID"}),
                Arguments.of("import java.util.UUID; output.put('r', UUID)", Map.of("r", UUID.class),
                        new String[0]),
                Arguments.of("output.put(String, 1)", Map.of(String.class, 1), new String[0]),
                Arguments.of("output.put('r', 10B)", Map.of("r", new BigDecimal("10")), new String[0]),
                Arguments.of("output.put('r', 10I)", Map.of("r", BigInteger.TEN), new String[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("objectArguments")
    @DisplayName("an action passing a class name or a 10B or 10I literal as an Object argument works on every run"
            + " (#1122)")
    void objectArgument(String action, Map<?, ?> expected, String[] imports) {
        Rule rule = Rule.builder().ruleName("r").condition("true").action(action).build();

        assertEquals(List.of(), wrongRuns(rule, expected, imports));
    }

    static Stream<Arguments> codeCalls() {
        return Stream.of(
                Arguments.of("a condition", "code.fail() == 1", "output.put('k', 1)"),
                Arguments.of("an action", "true", "code.fail()"));
    }

    /**
     * A rule's failure has what its Java code threw as its cause on every run, and MVEL calls the code through
     * reflection on every run, the JIT's among them, as {@link CalledCodeCauseTest} checks with the JIT on (#580).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("codeCalls")
    @DisplayName("a failure's cause is what the rule's code threw, and MVEL calls the code through reflection, on "
            + "every run (#580)")
    void calledCodeCause(String part, String condition, String action) {
        try (RulesEngine<Map<String, Object>> engine = CalledCodeCauseTest.engine(condition, action)) {
            for (int run = 0; run < 1_000; run++) {
                if (!FAILING_RUNS.contains(run)) {
                    engine.run(CalledCodeCauseTest.facts(null));
                    continue;
                }
                String name = part + ", run " + run;
                IllegalStateException failure = new IllegalStateException(name);
                CalledCodeCauseTest.Code code = new CalledCodeCauseTest.Code(failure);

                RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                        () -> engine.run(CalledCodeCauseTest.factsWith(code)), name);

                assertSame(failure, thrown.getCause(), name);
                CalledCodeCauseTest.Stage stage = CalledCodeCauseTest.stage(code);
                assertTrue(stage == CalledCodeCauseTest.Stage.FIRST_RUNS
                        || stage == CalledCodeCauseTest.Stage.REFLECTIVE_ACCESSOR, () -> name + ": " + stage);
            }
        }
    }
}
