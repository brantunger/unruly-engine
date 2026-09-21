package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An executor shutting down, or {@code Future.cancel(true)}, interrupts the thread a run is on. The engine checks the
 * interrupt status before each condition and each action, and again when each one returns, so a run stops instead of
 * evaluating every condition and firing every matching action. A rule's own expression can't be stopped part-way:
 * MVEL has no hook inside an expression.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a run stops between rules, or when an expression returns, once its thread is interrupted")
class InterruptedRunTest {

    /** A fact whose methods interrupt the thread the run is on, so a rule can interrupt itself. */
    public static final class Trigger {

        /**
         * Interrupts the current thread.
         *
         * @return {@code true}, so a condition that calls this matches
         */
        public boolean interruptNow() {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** Records the callbacks a run makes, so a test can see which rules the engine got to. */
    private static final class Callbacks implements RuleListener {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void beforeRun(RunContext run) {
            calls.add("beforeRun");
        }

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            calls.add("beforeEvaluate " + rule.getRuleName());
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            calls.add("beforeExecute " + rule.getRuleName());
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            calls.add("onError " + rule.getRuleName());
        }

        @Override
        public void onRunError(RunContext run, RuntimeException exception) {
            calls.add("onRunError");
        }

        @Override
        public void afterRun(RunContext run, io.github.brantunger.unruly.api.RunResult<?> result) {
            calls.add("afterRun");
        }
    }

    private static final Rule OK = rule("ok", "true", "output.put('ok', true)");

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static FactStore<Object> facts() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("trigger", new Trigger());
        return facts;
    }

    private static RulesEngine<Map<String, Object>> allMatches(UnaryOperator<RulesEngineBuilder<Map<String, Object>>>
                                                                       configuration, List<Rule> rules) {
        RulesEngine<Map<String, Object>> engine =
                configuration.apply(RulesEngineBuilder.allMatches(HashMap::new)).build();
        engine.load(rules);
        return engine;
    }

    /**
     * Clears the interrupt status this test's own thread was left with, so it can't reach the next test. The engine
     * leaves it set on purpose, which each test asserts before this runs.
     */
    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("a run whose thread is already interrupted fails before it evaluates the first condition")
    void interruptedBeforeTheFirstRule() {
        Callbacks callbacks = new Callbacks();
        RulesEngine<Map<String, Object>> engine = allMatches(builder -> builder.listener(callbacks), List.of(OK));
        Thread.currentThread().interrupt();

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertEquals("run() was interrupted before rule 'ok'", thrown.getMessage());
        assertInstanceOf(InterruptedException.class, thrown.getCause());
        assertNull(thrown.getRuleName(), "an interrupt isn't that rule's failure");
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status stays set");
        assertEquals(List.of("beforeRun", "onRunError"), callbacks.calls,
                "the rule was never started, so it gets no callback of its own");
    }

    @Test
    @DisplayName("a copy limit doesn't change how an already interrupted run fails")
    void interruptedBeforeTheFirstRuleWithACopyLimit() {
        RulesEngine<Map<String, Object>> engine = allMatches(builder -> builder.maxCopies(1), List.of(OK));
        // The engine's one copy is free: nothing waits, so nothing can say every copy was in use.
        Thread.currentThread().interrupt();

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertEquals("run() was interrupted before rule 'ok'", thrown.getMessage());
        assertInstanceOf(InterruptedException.class, thrown.getCause());
        assertTrue(Thread.interrupted(), "the interrupt status stays set");
        assertEquals(Map.of("ok", true), engine.run(facts()),
                "the copy was given back, so a later run on a thread that isn't interrupted gets it");
    }

    @ParameterizedTest(name = "maxCopies {0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("a run with no rules to evaluate does nothing, interrupted or not, with or without a limit")
    void interruptedRunOfAnEmptyRuleList(int maxCopies) {
        RulesEngine<Map<String, Object>> engine = allMatches(
                builder -> maxCopies == 0 ? builder : builder.maxCopies(maxCopies), List.of());
        Thread.currentThread().interrupt();

        assertNull(engine.run(facts()), "no rule was reached, so there was nothing to stop");
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status stays set");
    }

    @Test
    @DisplayName("a rule that interrupts the thread in its action stops the run as soon as the action returns")
    void interruptedByAnAction() {
        Callbacks callbacks = new Callbacks();
        RulesEngine<Map<String, Object>> engine = allMatches(builder -> builder.listener(callbacks),
                List.of(rule("a", "true", "output.put('a', trigger.interruptNow())"),
                        rule("b", "true", "output.put('b', true)")));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertEquals("run() was interrupted during rule 'a'", thrown.getMessage());
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status stays set");
        assertEquals(List.of("beforeRun", "beforeEvaluate a", "beforeEvaluate b", "beforeExecute a", "onError a",
                "onRunError"), callbacks.calls, "rule a's action is closed with onError, and b's never started");
    }

    @Test
    @DisplayName("a rule that interrupts the thread in its condition stops the run as soon as the condition returns")
    void interruptedByACondition() {
        Callbacks callbacks = new Callbacks();
        RulesEngine<Map<String, Object>> engine = allMatches(builder -> builder.listener(callbacks),
                List.of(rule("a", "trigger.interruptNow()", "output.put('a', true)"),
                        rule("b", "true", "output.put('b', true)")));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertEquals("run() was interrupted during rule 'a'", thrown.getMessage());
        assertEquals(List.of("beforeRun", "beforeEvaluate a", "onError a", "onRunError"), callbacks.calls,
                "rule b's condition was never evaluated");
    }

    @Test
    @DisplayName("a first-match run stops as soon as the matching condition returns, before its action")
    void interruptedBeforeTheActionOfAFirstMatchRun() {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
        engine.load(List.of(rule("a", "trigger.interruptNow()", "output.put('a', true)")));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertEquals("run() was interrupted during rule 'a'", thrown.getMessage());
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status stays set");
    }

    @Test
    @DisplayName("stopping a cancelled run is logged at WARN: the caller asked for it, and no rule failed")
    void loggedAtWarn() {
        RulesEngine<Map<String, Object>> engine = allMatches(UnaryOperator.identity(), List.of(OK));

        String logs = logsOf(() -> {
            Thread.currentThread().interrupt();
            assertThrows(RuleExecutionException.class, () -> engine.run(facts()));
        });

        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "run() was interrupted before rule 'ok'"), logs);
        assertFalse(logs.contains("ERROR"), logs);
    }

    @Test
    @DisplayName("a run on a thread that isn't interrupted is unaffected")
    void aRunThatIsNotInterrupted() {
        RulesEngine<Map<String, Object>> engine = allMatches(UnaryOperator.identity(),
                List.of(rule("a", "true", "output.put('a', true)"), rule("b", "true", "output.put('b', true)")));

        assertEquals(Map.of("a", true, "b", true), engine.run(facts()));
    }
}
