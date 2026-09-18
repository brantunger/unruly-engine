package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
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
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

import static io.github.brantunger.unruly.core.EngineLoggingTest.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An engine, or one {@code run} call, can be given a timeout. The deadline it sets is checked where an interrupt is,
 * before each condition and each action and when each returns, and it is passed to the language, so a language that can stop inside
 * an expression can honour it there too. MVEL can't, so a rule written in MVEL always runs to its end.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a run stops between rules, or when an expression returns, once it has passed its deadline")
class RunTimeoutTest {

    /** Long enough that a run stops on the rule after the slow one, and short enough to keep the tests quick. */
    private static final Duration SHORT = Duration.ofMillis(300);
    private static final Duration LONG = Duration.ofMinutes(1);
    private static final int SLOW_MILLIS = 900;

    /** A fact that takes its time, so a run passes a deadline inside a rule rather than between two fast ones. */
    public static final class Slow {

        /**
         * Waits, then answers.
         *
         * @param millis How long to wait
         * @param result What to return
         * @return {@code result}
         */
        public boolean pause(int millis, boolean result) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result;
        }
    }

    /** A language that records the deadline the engine gives each condition and action. */
    private static final class Recorder implements ExpressionLanguage {

        private final List<Instant> conditionDeadlines = new ArrayList<>();
        private final List<Instant> actionDeadlines = new ArrayList<>();
        private final List<Boolean> conditionCancelled = new ArrayList<>();

        @Override
        public String name() {
            return "recorder";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> {
                        conditionDeadlines.add(evaluation.deadline());
                        conditionCancelled.add(evaluation.isCancelled());
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> {
                        actionDeadlines.add(action.deadline());
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    /** Records whether a run got as far as each rule's action. */
    private static final class Fired implements RuleListener {

        private final List<String> rules = new ArrayList<>();
        private boolean runFailed;

        @Override
        public void afterExecute(Rule rule, Object output) {
            rules.add(rule.getRuleName());
        }

        @Override
        public void onRunError(RunContext run, RuntimeException exception) {
            runFailed = true;
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /** Rules whose first action waits longer than {@link #SHORT}, so the run passes its deadline inside rule a. */
    private static final List<Rule> SLOW_ACTION_THEN_B = List.of(
            rule("a", "true", "output.put('a', slow.pause(" + SLOW_MILLIS + ", true))"),
            rule("b", "true", "output.put('b', true)"));

    private static FactStore<Object> facts() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("slow", new Slow());
        return facts;
    }

    private static RulesEngine<Map<String, Object>> allMatches(UnaryOperator<RulesEngineBuilder<Map<String, Object>>>
                                                                       configuration, List<Rule> rules) {
        RulesEngine<Map<String, Object>> engine =
                configuration.apply(RulesEngineBuilder.allMatches(HashMap::new)).build();
        engine.load(rules);
        return engine;
    }

    @Test
    @DisplayName("a run past the engine's timeout stops as soon as the slow expression returns, blaming no rule")
    void pastTheEnginesTimeout() {
        Fired fired = new Fired();
        RulesEngine<Map<String, Object>> engine =
                allMatches(builder -> builder.runTimeout(SHORT).listener(fired), SLOW_ACTION_THEN_B);

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertTrue(thrown.getMessage().startsWith("run() passed its deadline of "), thrown.getMessage());
        assertTrue(thrown.getMessage().endsWith(" during rule 'a'"), thrown.getMessage());
        assertInstanceOf(TimeoutException.class, thrown.getCause());
        assertNull(thrown.getRuleName(), "a deadline isn't that rule's failure");
        assertEquals(List.of(), fired.rules, "the slow action returned past the deadline, so it isn't counted as fired");
        assertTrue(fired.runFailed, "the run itself is reported to listeners");
        assertFalse(Thread.currentThread().isInterrupted(), "a deadline doesn't interrupt the thread");
    }

    @Test
    @DisplayName("the deadline is the timeout itself, so a rule that takes less than twice it still stops the run")
    void theDeadlineIsTheTimeoutItself() {
        Fired fired = new Fired();
        // The slow action returns past this timeout and short of twice it, so a run that carried on would show a
        // deadline further off than the timeout it was given.
        RulesEngine<Map<String, Object>> engine = allMatches(
                builder -> builder.runTimeout(Duration.ofMillis(SLOW_MILLIS * 2 / 3)).listener(fired),
                SLOW_ACTION_THEN_B);

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertTrue(thrown.getMessage().startsWith("run() passed its deadline of "), thrown.getMessage());
        assertFalse(fired.rules.contains("b"), "rule b ran " + SLOW_MILLIS + " ms into a run whose timeout was "
                + SLOW_MILLIS * 2 / 3 + " ms");
    }

    @Test
    @DisplayName("a first-match run past its deadline stops as soon as the slow condition returns")
    void pastTheDeadlineInAFirstMatchRun() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .runTimeout(SHORT).build();
        engine.load(List.of(rule("a", "slow.pause(" + SLOW_MILLIS + ", false)", "output.put('a', true)"),
                rule("b", "true", "output.put('b', true)")));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertTrue(thrown.getMessage().endsWith(" during rule 'a'"), thrown.getMessage());
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a run inside its timeout is unaffected")
    void insideTheTimeout() {
        RulesEngine<Map<String, Object>> engine = allMatches(builder -> builder.runTimeout(LONG),
                List.of(rule("a", "true", "output.put('a', true)"), rule("b", "true", "output.put('b', true)")));

        assertEquals(Map.of("a", true, "b", true), engine.run(facts()));
    }

    @Test
    @DisplayName("a timeout given to one run replaces the engine's, shorter or longer")
    void aPerCallTimeoutReplacesTheEngines() {
        RulesEngine<Map<String, Object>> patient = allMatches(builder -> builder.runTimeout(LONG),
                SLOW_ACTION_THEN_B);
        RulesEngine<Map<String, Object>> hasty = allMatches(builder -> builder.runTimeout(SHORT),
                SLOW_ACTION_THEN_B);

        RuleExecutionException thrown =
                assertThrows(RuleExecutionException.class, () -> patient.runWithResult(facts(),
                        RunOptions.withTimeoutOf(SHORT)));
        assertTrue(thrown.getMessage().endsWith(" during rule 'a'"), thrown.getMessage());

        assertEquals(Map.of("a", true, "b", true),
                hasty.runWithResult(facts(), RunOptions.withTimeoutOf(LONG)).output(),
                "the engine's short timeout doesn't apply to a run given its own");
    }

    @Test
    @DisplayName("a timeout must be a positive duration, on the builder and on a run")
    void aTimeoutMustBePositive() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);
        RulesEngine<Map<String, Object>> engine = allMatches(UnaryOperator.identity(), List.of());

        assertEquals("timeout must not be null", assertThrows(NullPointerException.class,
                () -> builder.runTimeout(null)).getMessage());
        assertEquals("timeout must be positive, but was PT0S", assertThrows(IllegalArgumentException.class,
                () -> builder.runTimeout(Duration.ZERO)).getMessage());
        assertEquals("timeout must be positive, but was PT-1S", assertThrows(IllegalArgumentException.class,
                () -> builder.runTimeout(Duration.ofSeconds(-1))).getMessage());
        assertEquals("options must not be null", assertThrows(NullPointerException.class,
                () -> engine.runWithResult(new FactMap<>(), null)).getMessage());
        assertEquals("timeout must be positive, but was PT0S", assertThrows(IllegalArgumentException.class,
                () -> RunOptions.withTimeoutOf(Duration.ZERO)).getMessage());
    }

    @Test
    @DisplayName("stopping a run that passed its deadline is logged at WARN, like an interrupted one")
    void loggedAtWarn() {
        RulesEngine<Map<String, Object>> engine = allMatches(builder -> builder.runTimeout(SHORT),
                SLOW_ACTION_THEN_B);

        String logs = logsOf(() -> assertThrows(RuleExecutionException.class, () -> engine.run(facts())));

        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "run() passed its deadline of "), logs);
        assertFalse(logs.contains("ERROR"), logs);
    }

    @Test
    @DisplayName("a language is given the run's deadline, so it can stop inside an expression")
    void theLanguageIsGivenTheDeadline() {
        Recorder recorder = new Recorder();
        Instant before = Instant.now();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(recorder).runTimeout(LONG).build();
        engine.load(List.of(rule("a", "anything", "anything")));

        engine.run(new FactMap<>());

        Instant deadline = recorder.conditionDeadlines.get(0);
        assertNotNull(deadline, "the condition was given the deadline");
        assertFalse(deadline.isBefore(before.plus(LONG)), "the deadline is at least a timeout away from the start");
        assertEquals(List.of(deadline), recorder.actionDeadlines, "the action was given the same deadline");
        assertEquals(List.of(false), recorder.conditionCancelled, "the run wasn't cancelled");
    }

    @Test
    @DisplayName("a language is given no deadline when the run has none")
    void theLanguageIsGivenNoDeadlineWithoutATimeout() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(recorder).build();
        engine.load(List.of(rule("a", "anything", "anything")));

        engine.run(new FactMap<>());

        assertEquals(1, recorder.conditionDeadlines.size());
        assertNull(recorder.conditionDeadlines.get(0), "a run without a timeout has no deadline");
        assertNull(recorder.actionDeadlines.get(0), "a run without a timeout has no deadline");
        assertEquals(List.of(false), recorder.conditionCancelled);
    }
}
