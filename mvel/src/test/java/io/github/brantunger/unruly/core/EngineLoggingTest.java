package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLogs.assertLoggedAtError;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the engine logs each failure before throwing it")
class EngineLoggingTest {

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
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
                Arguments.of("a rule in a language the engine doesn't have",
                        List.of(Rule.builder().ruleName("a").language("cel").condition("true").action("1").build())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRuleLists")
    @DisplayName("a rule list load rejects is logged")
    void rejectedRuleListLogged(String description, List<Rule> rules) {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);

        assertLoggedAtError(RuleCompilationException.class, () -> engine.load(rules));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"output", "my-fact", "String"})
    @DisplayName("a fact run rejects is logged")
    void rejectedFactLogged(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(rule("a", "true", "output.put('k', 1)")));

        assertLoggedAtError(IllegalArgumentException.class, () -> engine.run(new FactMap<>(new Fact<>(name, 1))));
    }

    @Test
    @DisplayName("a condition that fails is logged")
    void conditionFailureLogged() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(rule("a", "x.missing > 1", "output.put('k', 1)")));

        assertLoggedAtError(RuleExecutionException.class, () -> engine.run(new FactMap<>(new Fact<>("x", 1))));
    }

    @Test
    @DisplayName("an action that fails is logged")
    void actionFailureLogged() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(rule("a", "true", "output.put('k', x.missing)")));

        assertLoggedAtError(RuleExecutionException.class, () -> engine.run(new FactMap<>(new Fact<>("x", 1))));
    }

    @Test
    @DisplayName("an output supplier that throws or returns null is logged")
    void outputSupplierFailureLogged() {
        StatelessRulesEngine<Map<String, Object>> throwing = TestEngines.firstMatch(() -> {
            throw new IllegalStateException("boom");
        });
        throwing.load(List.of(rule("a", "true", "output.put('k', 1)")));
        StatelessRulesEngine<Map<String, Object>> returningNull = TestEngines.firstMatch(() -> null);
        returningNull.load(List.of(rule("a", "true", "output.put('k', 1)")));

        assertLoggedAtError(RuleExecutionException.class, () -> throwing.run(new FactMap<>()));
        assertLoggedAtError(RuleExecutionException.class, () -> returningNull.run(new FactMap<>()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"beforeEvaluate", "afterEvaluate", "beforeExecute", "afterExecute", "onError"})
    @DisplayName("a listener that throws is logged at WARN, naming the callback, and the run continues")
    void listenerFailureLogged(String callback) {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.listener(new RuleListener() {
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
        }));
        // onError is only called for a rule that fails.
        boolean failing = "onError".equals(callback);
        engine.load(List.of(rule("a", failing ? "x.missing > 1" : "true", "output.put('k', 1)")));
        AtomicReference<Object> outcome = new AtomicReference<>();

        String logs = logsOf(() -> outcome.set(failing
                ? assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>(new Fact<>("x", 1))))
                : engine.run(new FactMap<>(new Fact<>("x", 1)))));

        if (!failing) {
            assertEquals(Map.of("k", 1), outcome.get());
        }
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "Listener threw exception in " + callback), logs);
        assertTrue(logs.contains("listener boom"), logs);
    }
}
