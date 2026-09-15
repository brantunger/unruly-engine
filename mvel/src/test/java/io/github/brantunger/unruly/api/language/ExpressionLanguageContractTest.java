package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.exception.UnrulyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the engine promises for rules in any expression language. A language's test extends this class and supplies
 * the expressions each check needs, written in that language.
 */
public abstract class ExpressionLanguageContractTest {

    /**
     * Returns the language under test.
     *
     * @return A new instance of the language
     */
    protected abstract ExpressionLanguage language();

    /** Returns a condition that is always true. */
    protected abstract String alwaysTrue();

    /** Returns a condition that is true when the integer fact {@code fact} equals {@code value}. */
    protected abstract String factEquals(String fact, int value);

    /** Returns a condition whose result is the value of {@code fact}, whatever its type. */
    protected abstract String factValue(String fact);

    /** Returns a condition that assigns {@code value} to {@code fact}. */
    protected abstract String assignment(String fact, int value);

    /** Returns an action that puts the value of {@code fact} into the output map under {@code key}. */
    protected abstract String putFact(String key, String fact);

    /** Returns an action that declares a variable {@code name} holding {@code value}. */
    protected abstract String declareVariable(String name, int value);

    /** Returns an action that assigns a new object to the output. */
    protected abstract String reassignOutput();

    /** Returns a condition with a syntax error. */
    protected abstract String syntaxError();

    /** Returns a fact name rules in this language can't refer to, or {@code null} if every name is accepted. */
    protected abstract String unusableFactName();

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
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact("seen", "x")));

        assertEquals(Map.of("seen", 1), engine.run(fact("x", 1)));
        assertNull(engine.run(fact("x", 2)));
    }

    @Test
    @DisplayName("a condition must evaluate to a boolean: null, a string or a number fails the rule")
    void conditionMustBeBoolean() {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factValue("x"), putFact("seen", "x")));

        assertEquals(Map.of("seen", true), engine.run(fact("x", true)));
        for (Object notBoolean : Arrays.asList(null, "true", 1)) {
            assertThrows(RuleExecutionException.class, () -> engine.run(fact("x", notBoolean)),
                    String.valueOf(notBoolean));
        }
    }

    @Test
    @DisplayName("a condition that assigns to a fact is rejected by setRuleList")
    void conditionAssignmentRejected() {
        RulesEngine<Map<String, Object>> engine = engine();
        List<Rule> rules = List.of(rule("r", 1, assignment("x", 2), putFact("seen", "x")));

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
                rule("reads", 1, alwaysTrue(), putFact("seen", "x")));

        assertEquals(Map.of("seen", 1), engine.run(fact("x", 1)));
        assertEquals(Map.of("seen", 3), engine.run(fact("x", 3)));
    }

    @Test
    @DisplayName("a syntax error is reported by setRuleList")
    void syntaxErrorAtLoad() {
        RulesEngine<Map<String, Object>> engine = engine();
        List<Rule> rules = List.of(rule("r", 1, syntaxError(), putFact("seen", "x")));

        assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));
    }

    @Test
    @DisplayName("a fact name the language can't refer to is rejected by run()")
    void unusableFactNameRejected() {
        String name = unusableFactName();
        assumeTrue(name != null, "the language accepts every fact name");
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, alwaysTrue(), putFact("seen", "x")));

        assertThrows(IllegalArgumentException.class, () -> engine.run(fact(name, 1)));
    }

    @Test
    @DisplayName("concurrent runs of one rule list each see their own facts")
    void concurrentRuns() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact("seen", "y")));
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int worker = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        int x = (worker + i) % 2;
                        int y = worker * 1_000 + i;
                        FactStore<Object> facts = new FactMap<>();
                        facts.setValue("x", x);
                        facts.setValue("y", y);
                        Map<String, Object> expected = x == 1 ? Map.of("seen", y) : null;
                        Map<String, Object> actual = engine.run(facts);
                        if (!Objects.equals(expected, actual)) {
                            failures.add(expected + " != " + actual);
                        }
                    }
                } catch (InterruptedException | RuntimeException e) {
                    failures.add(String.valueOf(e));
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : workers) {
            thread.join(30_000);
        }

        assertEquals(List.of(), failures);
    }
}
