package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An {@link Error} from Java code a rule calls, such as a method, a getter or a lambda held in a fact, reaches the
 * engine wrapped in the expression language's own exception. A run past its deadline, or one whose thread was
 * interrupted, took that for the expression giving up and reported a stop naming no rule, logged at WARN, keeping
 * the exception only as a suppressed one. It is that rule's failure instead, reported exactly as it is when the run
 * has no deadline. An exception with no error in its chain still stops such a run, and a fatal error still reaches
 * the caller unchanged. A run started from inside an action counts the same way: when it fails this way, the run
 * around it reports its own rule's failure without logging it again, because the nested run has already logged the
 * failure that caused it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("an exception wrapping an Error, past the deadline or on an interrupt, is the rule's failure")
class ErrorPastDeadlineTest {

    private static final Duration SHORT = Duration.ofMillis(200);

    /**
     * A language whose expressions throw what their text names, either straight away, or once the run is cancelled:
     * {@code cancelled} waits for the deadline to pass, so no test depends on how long anything takes, and
     * {@code interrupt} interrupts the run's own thread, as an executor shutting down would. An action whose text is
     * {@code nested} throws nothing itself: it calls the {@link Runnable} held in the fact of that name, which starts
     * a run of its own.
     */
    private static final class BreakingLanguage implements ExpressionLanguage {

        @Override
        public String name() {
            return "breaking";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    if ("yes".equals(expression.text())) {
                        return (evaluation, session) -> true;
                    }
                    return (evaluation, session) -> {
                        throwAs(expression.text(), evaluation::isCancelled);
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    if ("nested".equals(expression.text())) {
                        return (action, session) -> {
                            ((Runnable) action.facts().get("nested")).run();
                            return ActionResult.done();
                        };
                    }
                    return (action, session) -> {
                        throwAs(expression.text(), action::isCancelled);
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }

        /**
         * Throws what {@code text} names, once whatever it says must happen first has.
         *
         * @param text      When to throw, and what: such as {@code "cancelled:error"}
         * @param cancelled Tells whether the run has been cancelled
         */
        private static void throwAs(String text, BooleanSupplier cancelled) {
            String[] parts = text.split(":", 2);
            switch (parts[0]) {
                case "cancelled" -> {
                    while (!cancelled.getAsBoolean()) {
                        Thread.onSpinWait();
                    }
                }
                case "interrupt" -> Thread.currentThread().interrupt();
                default -> {
                    // "now": the expression throws while the run may still go on.
                }
            }
            // The language never throws the error itself: Java code the rule called did, and a language wraps that.
            throw switch (parts.length > 1 ? parts[1] : "") {
                case "error" -> new IllegalStateException(new AssertionError("boom"));
                case "fatal" -> new IllegalStateException(new OutOfMemoryError("boom"));
                default -> new IllegalStateException("the rule gave up, nothing broke");
            };
        }
    }

    private static RulesEngine<Map<String, Object>> engine(Rule rule, Duration timeout, RuleListener listener) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder
                .<Map<String, Object>>firstMatch(HashMap::new).language(new BreakingLanguage()).listener(listener);
        RulesEngine<Map<String, Object>> engine = (timeout == null ? builder : builder.runTimeout(timeout)).build();
        engine.load(List.of(rule));
        return engine;
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /** What a run reported: the exception it threw, what {@code onError} was told, and what it logged. */
    private record Reported(RuleExecutionException thrown, RuleExecutionException onError, String logs) {
    }

    /**
     * Runs an engine over one rule, which must throw, and collects what it reported.
     *
     * @param rule    The rule whose condition or action throws
     * @param timeout How long the run may take, or {@code null} for a run with no deadline
     * @return What the run reported
     */
    private static Reported report(Rule rule, Duration timeout) {
        AtomicReference<RuleExecutionException> onError = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(rule, timeout, new RuleListener() {
            @Override
            public void onError(Rule failed, RuleExecutionException error) {
                onError.set(error);
            }
        });
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(
                assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()))));

        return new Reported(thrown.get(), onError.get(), logs);
    }

    /**
     * Asserts that a run reported the rule's failure, rather than a stop: the rule is named, the exception the
     * expression threw is the cause, nothing is left as a suppressed exception, and it is logged at ERROR and given
     * to {@code onError}.
     *
     * @param reported What the run reported
     * @param rule     The rule whose expression threw
     * @param kind     Whether its condition or its action threw
     */
    private static void assertRuleFailed(Reported reported, Rule rule, ExpressionKind kind) {
        RuleExecutionException failure = reported.thrown();
        assertEquals(rule.getRuleName(), failure.getRuleName(), "the failure doesn't name the rule");
        assertEquals(kind, failure.getExpressionKind());
        assertInstanceOf(IllegalStateException.class, failure.getCause(), "the cause isn't what the expression threw");
        assertInstanceOf(AssertionError.class, failure.getCause().getCause());
        assertEquals(0, failure.getSuppressed().length, "a failure keeps nothing as a suppressed exception");
        assertFalse(ReportedFailure.isStop(failure), "the run was reported as a stop");
        assertSame(failure, reported.onError(), "the rule's callback wasn't closed with the failure");
        assertTrue(reported.logs().contains("ERROR " + ENGINE_LOGGER + failure.getMessage()), reported.logs());
        assertFalse(reported.logs().contains("WARN"), reported.logs());
    }

    /**
     * Clears the interrupt status the interrupt test leaves this thread with, so it can't reach the next test. The
     * engine leaves it set on purpose, which that test asserts before this runs.
     */
    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("an action that throws an exception wrapping an Error past the deadline fails its rule")
    void actionPastTheDeadline() {
        Rule rule = rule("breaks", "yes", "cancelled:error");

        Reported reported = report(rule, SHORT);

        assertRuleFailed(reported, rule, ExpressionKind.ACTION);
        assertFalse(reported.logs().contains("passed its deadline"), reported.logs());
    }

    @Test
    @DisplayName("a condition that throws an exception wrapping an Error past the deadline fails its rule")
    void conditionPastTheDeadline() {
        Rule rule = rule("breaks", "cancelled:error", "unused");

        Reported reported = report(rule, SHORT);

        assertRuleFailed(reported, rule, ExpressionKind.CONDITION);
        assertFalse(reported.logs().contains("passed its deadline"), reported.logs());
    }

    @Test
    @DisplayName("an action that interrupts the run and throws an exception wrapping an Error fails its rule")
    void actionThatInterruptsTheRun() {
        Rule rule = rule("breaks", "yes", "interrupt:error");

        Reported reported = report(rule, null);

        assertRuleFailed(reported, rule, ExpressionKind.ACTION);
        assertFalse(reported.logs().contains("was interrupted"), reported.logs());
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status stays set");
    }

    @Test
    @DisplayName("the deadline changes nothing: the same rule and the same throw are reported the same way")
    void theSameAsARunWithNoDeadline() {
        RuleExecutionException pastTheDeadline = report(rule("breaks", "yes", "cancelled:error"), SHORT).thrown();

        RuleExecutionException noDeadline = report(rule("breaks", "yes", "now:error"), null).thrown();

        assertEquals(noDeadline.getMessage(), pastTheDeadline.getMessage());
        assertEquals(noDeadline.getRuleName(), pastTheDeadline.getRuleName());
        assertEquals(noDeadline.getExpressionKind(), pastTheDeadline.getExpressionKind());
        assertEquals(noDeadline.getCause().getClass(), pastTheDeadline.getCause().getClass());
    }

    @Test
    @DisplayName("an exception with no Error in its chain past the deadline still stops the run")
    void exceptionWithNoErrorPastTheDeadline() {
        Reported reported = report(rule("breaks", "yes", "cancelled:plain"), SHORT);

        RuleExecutionException stop = reported.thrown();
        assertTrue(ReportedFailure.isStop(stop), "the run wasn't reported as a stop");
        assertNull(stop.getRuleName(), "a stop names no rule");
        assertNull(stop.getExpressionKind());
        assertInstanceOf(TimeoutException.class, stop.getCause());
        assertEquals(1, stop.getSuppressed().length, "what the expression threw isn't kept");
        assertInstanceOf(IllegalStateException.class, stop.getSuppressed()[0]);
        assertTrue(reported.logs().contains("WARN " + ENGINE_LOGGER + "run() passed its deadline of "),
                reported.logs());
        assertFalse(reported.logs().contains("ERROR"), reported.logs());
    }

    @Test
    @DisplayName("an exception wrapping a fatal Error past the deadline still reaches the caller unchanged")
    void fatalErrorPastTheDeadline() {
        RulesEngine<Map<String, Object>> engine = engine(rule("breaks", "yes", "cancelled:fatal"), SHORT,
                new RuleListener() {
                });
        AtomicReference<Error> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()))));

        assertEquals("boom", thrown.get().getMessage());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER), logs);
    }

    @Test
    @DisplayName("a nested run that failed past the outer run's deadline is the outer rule's failure, logged once")
    void nestedRunFailurePastTheDeadline() {
        RulesEngine<Map<String, Object>> inner = engine(rule("inner", "yes", "cancelled:error"), null,
                new RuleListener() {
                });
        AtomicReference<RuleExecutionException> onError = new AtomicReference<>();
        AtomicInteger onErrors = new AtomicInteger();
        AtomicInteger onRunErrors = new AtomicInteger();
        RulesEngine<Map<String, Object>> outer = engine(rule("outer", "yes", "nested"), SHORT, new RuleListener() {
            @Override
            public void onError(Rule failed, RuleExecutionException error) {
                onError.set(error);
                onErrors.incrementAndGet();
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                onRunErrors.incrementAndGet();
            }
        });
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("nested", (Runnable) () -> inner.run(new FactMap<>()));
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(
                assertThrows(RuleExecutionException.class, () -> outer.run(facts))));

        RuleExecutionException failure = thrown.get();
        assertEquals("outer", failure.getRuleName(), "the outer failure doesn't name the outer rule");
        assertEquals(ExpressionKind.ACTION, failure.getExpressionKind());
        assertInstanceOf(RuleExecutionException.class, failure.getCause(), "the cause isn't the nested run's failure");
        assertEquals(1, logs.lines().filter(line -> line.contains("ERROR " + ENGINE_LOGGER)).count(), logs);
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + "Failed to execute action for rule 'inner'"), logs);
        assertFalse(logs.contains("WARN " + ENGINE_LOGGER + "run() "), logs);
        assertEquals(1, onErrors.get(), "the outer rule's callback wasn't closed exactly once");
        assertSame(failure, onError.get(), "the outer rule's callback wasn't closed with the outer failure");
        assertEquals(1, onRunErrors.get(), "the outer run didn't report its failure exactly once");
    }
}
