package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * slf4j-simple writes to whatever {@link System#err} is at the time of each call, so the engine's log lines can be
 * captured here.
 */
@DisplayName("the engine logs each failure before throwing it")
class EngineLoggingTest {

    static final String ENGINE_LOGGER = "io.github.brantunger.unruly.core.AbstractRulesEngine - ";

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static FactStore<Object> fact(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    static String logsOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Asserts that {@code action} throws {@code type} and that the exception's message was logged at ERROR.
     *
     * @return The exception
     */
    static <T extends Throwable> T assertLoggedAtError(Class<T> type, Executable action) {
        AtomicReference<T> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(type, action)));

        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + thrown.get().getMessage()), logs);
        return thrown.get();
    }

    /** Asserts that {@code action} throws {@code error} itself, after logging {@code message} at ERROR. */
    static void assertLoggedThenRethrown(Error error, String message, Executable action) {
        AtomicReference<Error> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(Error.class, action)));

        assertSame(error, thrown.get());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + message), logs);
    }

    static Stream<Arguments> rejectedRuleLists() {
        return Stream.of(
                Arguments.of("a null rule", Arrays.asList(rule("a", "true", "output.put('k', 1)"), null)),
                Arguments.of("a duplicate rule name",
                        List.of(rule("a", "true", "output.put('k', 1)"), rule("a", "true", "output.put('k', 2)"))),
                Arguments.of("a blank condition", List.of(rule("a", " ", "output.put('k', 1)"))),
                Arguments.of("a blank action", List.of(rule("a", "true", ""))),
                Arguments.of("an assignment in a condition", List.of(rule("a", "x = 1", "output.put('k', 1)"))),
                Arguments.of("import_static in a condition",
                        List.of(rule("a", "import_static java.lang.Math.max; max(x, 1) == 5", "output.put('k', 1)"))),
                Arguments.of("a syntax error", List.of(rule("a", "x >= ", "output.put('k', 1)"))),
                Arguments.of("a rule in an unregistered language",
                        List.of(Rule.builder().ruleName("a").language("cel").condition("true").action("1").build())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRuleLists")
    @DisplayName("a rule list setRuleList rejects is logged")
    void rejectedRuleListLogged(String description, List<Rule> rules) {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        assertLoggedAtError(RuleCompilationException.class, () -> engine.setRuleList(rules));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"output", "my-fact", "String"})
    @DisplayName("a fact run rejects is logged")
    void rejectedFactLogged(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("a", "true", "output.put('k', 1)")));

        assertLoggedAtError(IllegalArgumentException.class, () -> engine.run(fact(name, 1)));
    }

    @Test
    @DisplayName("a condition that fails is logged")
    void conditionFailureLogged() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("a", "x.missing > 1", "output.put('k', 1)")));

        assertLoggedAtError(RuleExecutionException.class, () -> engine.run(fact("x", 1)));
    }

    @Test
    @DisplayName("an action that fails is logged")
    void actionFailureLogged() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("a", "true", "output.put('k', x.missing)")));

        assertLoggedAtError(RuleExecutionException.class, () -> engine.run(fact("x", 1)));
    }

    @Test
    @DisplayName("an output supplier that throws or returns null is logged")
    void outputSupplierFailureLogged() {
        StatelessRulesEngine<Map<String, Object>> throwing = new StatelessRulesEngine<>(() -> {
            throw new IllegalStateException("boom");
        });
        throwing.setRuleList(List.of(rule("a", "true", "output.put('k', 1)")));
        StatelessRulesEngine<Map<String, Object>> returningNull = new StatelessRulesEngine<>(() -> null);
        returningNull.setRuleList(List.of(rule("a", "true", "output.put('k', 1)")));

        assertLoggedAtError(RuleExecutionException.class, () -> throwing.run(new FactMap<>()));
        assertLoggedAtError(RuleExecutionException.class, () -> returningNull.run(new FactMap<>()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeEvaluate", "afterEvaluate", "beforeExecute", "afterExecute", "onError"})
    @DisplayName("a listener that throws is logged at WARN, naming the callback, and the run continues")
    void listenerFailureLogged(String callback) {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.registerListener(new RuleListener() {
            private void called(String name) {
                if (name.equals(callback)) {
                    throw new IllegalStateException("listener boom");
                }
            }

            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                called("beforeEvaluate");
            }

            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                called("afterEvaluate");
            }

            @Override
            public void beforeExecute(Rule rule, Object output) {
                called("beforeExecute");
            }

            @Override
            public void afterExecute(Rule rule, Object output) {
                called("afterExecute");
            }

            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                called("onError");
            }
        });
        // onError is only called for a rule that fails.
        boolean failing = "onError".equals(callback);
        engine.setRuleList(List.of(rule("a", failing ? "x.missing > 1" : "true", "output.put('k', 1)")));
        AtomicReference<Object> outcome = new AtomicReference<>();

        String logs = logsOf(() -> outcome.set(failing
                ? assertThrows(RuleExecutionException.class, () -> engine.run(fact("x", 1)))
                : engine.run(fact("x", 1))));

        if (!failing) {
            assertEquals(Map.of("k", 1), outcome.get());
        }
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in " + callback), logs);
        assertTrue(logs.contains("listener boom"), logs);
    }
}
