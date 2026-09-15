package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the engine promises for rules in any expression language. A language's test extends this class and supplies
 * the expressions each check needs, written in that language:
 *
 * <pre>{@code
 * class MyLanguageContractTest extends ExpressionLanguageContractTest {
 *     protected ExpressionLanguage language() { return new MyLanguage(); }
 *     protected String alwaysTrue() { return "true"; }
 *     protected String factEquals(String fact, int value) { return fact + " == " + value; }
 *     // ... one method for each expression the checks need
 * }
 * }</pre>
 *
 * <p>
 * Each check runs rules through an engine, so a language passes only if it works with the engine as users will run
 * it. On the module path, the package of the extending test must be open to {@code org.junit.platform.commons}.
 * </p>
 */
// A test class: each check makes several assertions, and their failure messages show the values compared.
@SuppressWarnings({"PMD.JUnitTestContainsTooManyAsserts", "PMD.JUnitAssertionsShouldIncludeMessage"})
public abstract class ExpressionLanguageContractTest {

    /** The output key the checks' actions put a fact's value under. */
    private static final String SEEN = "seen";

    /** Creates the test. JUnit creates an instance of the extending class for each check. */
    protected ExpressionLanguageContractTest() {
    }

    /**
     * Returns the language under test.
     *
     * @return A new instance of the language
     */
    protected abstract ExpressionLanguage language();

    /**
     * Returns a condition that is always true.
     *
     * @return The condition
     */
    protected abstract String alwaysTrue();

    /**
     * Returns a condition that is true when an integer fact equals a value.
     *
     * @param fact  The fact's name
     * @param value The value to compare it with
     * @return The condition
     */
    protected abstract String factEquals(String fact, int value);

    /**
     * Returns a condition whose result is a fact's value, whatever its type.
     *
     * @param fact The fact's name
     * @return The condition
     */
    protected abstract String factValue(String fact);

    /**
     * Returns a condition that assigns a value to a fact.
     *
     * @param fact  The fact's name
     * @param value The value to assign
     * @return The condition
     */
    protected abstract String assignment(String fact, int value);

    /**
     * Returns an action that puts a fact's value into the output map.
     *
     * @param key  The key to put the value under
     * @param fact The fact's name
     * @return The action
     */
    protected abstract String putFact(String key, String fact);

    /**
     * Returns an action that declares a variable.
     *
     * @param name  The variable's name
     * @param value The value it holds
     * @return The action
     */
    protected abstract String declareVariable(String name, int value);

    /**
     * Returns an action that assigns a new object to the output.
     *
     * @return The action
     */
    protected abstract String reassignOutput();

    /**
     * Returns a condition with a syntax error.
     *
     * @return The condition
     */
    protected abstract String syntaxError();

    /**
     * Returns a fact name that rules in this language can't refer to.
     *
     * @return The name, or {@code null} if every name is accepted
     */
    protected abstract @Nullable String unusableFactName();

    private Rule rule(String name, int priority, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action(action)
                .language(language().name()).build();
    }

    private RulesEngine<Map<String, Object>> engine() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);
        engine.registerLanguage(language());
        return engine;
    }

    private RulesEngine<Map<String, Object>> engine(Rule... rules) {
        RulesEngine<Map<String, Object>> engine = engine();
        engine.setRuleList(List.of(rules));
        return engine;
    }

    private static FactStore<Object> fact(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    @Test
    @DisplayName("a condition reads the facts, and its rule fires only when the condition is true")
    void conditionReadsFacts() {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x")));

        assertEquals(Map.of(SEEN, 1), engine.run(fact("x", 1)));
        assertNull(engine.run(fact("x", 2)));
    }

    @Test
    @DisplayName("a condition must evaluate to a boolean: null, a string or a number fails the rule")
    void conditionMustBeBoolean() {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factValue("x"), putFact(SEEN, "x")));

        assertEquals(Map.of(SEEN, true), engine.run(fact("x", true)));
        for (Object notBoolean : Arrays.asList(null, "true", 1)) {
            assertThrows(RuleExecutionException.class, () -> engine.run(fact("x", notBoolean)),
                    String.valueOf(notBoolean));
        }
    }

    @Test
    @DisplayName("a condition that assigns to a fact is rejected by setRuleList")
    void conditionAssignmentRejected() {
        RulesEngine<Map<String, Object>> engine = engine();
        List<Rule> rules = List.of(rule("r", 1, assignment("x", 2), putFact(SEEN, "x")));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertTrue(ex.getMessage().startsWith("Condition for rule 'r' "), ex.getMessage());
    }

    @Test
    @DisplayName("an action can't replace the output object")
    void outputNotReplaceable() {
        assertThrows(UnrulyException.class, () -> engine(rule("r", 1, alwaysTrue(), reassignOutput())).run(fact("x", 1)));
    }

    @Test
    @DisplayName("a variable an action declares doesn't change the facts later actions see")
    void actionVariablesStayLocal() {
        RulesEngine<Map<String, Object>> engine = engine(
                rule("declares", 2, alwaysTrue(), declareVariable("x", 2)),
                rule("reads", 1, alwaysTrue(), putFact(SEEN, "x")));

        assertEquals(Map.of(SEEN, 1), engine.run(fact("x", 1)));
        assertEquals(Map.of(SEEN, 3), engine.run(fact("x", 3)));
    }

    @Test
    @DisplayName("a syntax error is reported by setRuleList")
    void syntaxErrorAtLoad() {
        RulesEngine<Map<String, Object>> engine = engine();
        List<Rule> rules = List.of(rule("r", 1, syntaxError(), putFact(SEEN, "x")));

        assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));
    }

    @Test
    @DisplayName("a fact name the language can't refer to is rejected by run()")
    void unusableFactNameRejected() {
        String name = unusableFactName();
        assumeTrue(name != null, "the language accepts every fact name");
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, alwaysTrue(), putFact(SEEN, "x")));

        assertThrows(IllegalArgumentException.class, () -> engine.run(fact(name, 1)));
    }

    @Test
    @DisplayName("concurrent runs of one rule list each see their own facts")
    // Shut down in the finally block, which also interrupts workers that a broken language leaves running.
    @SuppressWarnings("PMD.CloseResource")
    void concurrentRuns() throws Exception {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact(SEEN, "y")));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<List<Map<String, Object>>>>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int worker = t;
                results.add(workers.submit(() -> {
                    start.await();
                    List<Map<String, Object>> expected = new ArrayList<>();
                    List<Map<String, Object>> actual = new ArrayList<>();
                    for (int i = 0; i < 200; i++) {
                        int x = (worker + i) % 2;
                        int y = worker * 1_000 + i;
                        FactStore<Object> facts = new FactMap<>();
                        facts.setValue("x", x);
                        facts.setValue("y", y);
                        expected.add(x == 1 ? Map.of(SEEN, y) : null);
                        actual.add(engine.run(facts));
                    }
                    return List.of(expected, actual);
                }));
            }
            start.countDown();

            for (Future<List<List<Map<String, Object>>>> result : results) {
                List<List<Map<String, Object>>> runs = result.get(30, TimeUnit.SECONDS);
                assertEquals(runs.get(0), runs.get(1));
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
