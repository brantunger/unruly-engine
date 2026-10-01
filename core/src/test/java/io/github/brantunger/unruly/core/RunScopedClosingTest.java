package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #850: {@link EvaluationContext#runScopedClosing} keeps a value for one run, as {@code runScoped} does, and the run
 * closes it when it ends, however it ends: after its last listener call, before it gives back its copy of the rules, in
 * the reverse of the order the values were made. What a {@code close()} throws is logged at WARN, and only a fatal
 * {@link Error} fails the run. A key is either closing or not, and no closing value can be made once the run's have
 * been closed. {@code runScoped} values are still never closed.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#850: a language's run-scoped closing values are closed when the run ends")
class RunScopedClosingTest {

    private static final List<Rule> RULES = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
    private static final String WARN_PREFIX = "A value a language kept for the run failed to close: ";

    // What happened, in order: listener calls, values closed and sessions closed.
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();

    /** A value a language keeps for a run, which records its close() and then throws what the test set, if anything. */
    private class Value implements AutoCloseable {
        private final String name;
        private final Throwable failure;
        private Thread closedOn;

        Value(String name) {
            this(name, null);
        }

        Value(String name, Throwable failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public void close() throws IOException {
            closedOn = Thread.currentThread();
            events.add(name + " closed" + (Thread.currentThread().isInterrupted() ? " interrupted" : ""));
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /** Records each run's last listener call. */
    private final RuleListener listener = new RuleListener() {
        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            events.add("afterRun");
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            events.add("onRunError");
        }
    };

    @AfterEach
    void clearFaults() {
        Faults.clear();
        Thread.interrupted();
    }

    /**
     * An engine whose one rule runs {@code action}, whose sessions record their close(), and which makes no copy at
     * load, so a copy's session is closed only when a run gives back a copy of rules that were reloaded meanwhile.
     */
    private RulesEngine<Map<String, Object>> engine(Consumer<ActionContext> action, Duration timeout) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                        HashMap::new)
                .language(new StubExpressionLanguage().newSession(() -> new Session() {
                    @Override
                    public void close() {
                        events.add("session closed");
                    }
                }).action((context, session) -> {
                    action.accept(context);
                    return ActionResult.done();
                })).listener(listener).copiesAtLoad(0);
        if (timeout != null) {
            builder.runTimeout(timeout);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(RULES);
        self.set(engine);
        return engine;
    }

    private RulesEngine<Map<String, Object>> engine(Consumer<ActionContext> action) {
        return engine(action, null);
    }

    @Test
    @DisplayName("a run's values are closed on its thread after afterRun, and before its copy's session is closed")
    void closedAfterAfterRunBeforeTheCopyIsGivenBack() {
        AtomicReference<Value> value = new AtomicReference<>();
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            value.set(context.runScopedClosing("key", () -> new Value("value")));
            // Retires the rules the run holds a copy of, so giving the copy back closes its session.
            self.get().load(RULES);
        })) {
            engine.run(new FactMap<>());
        }

        assertEquals(List.of("afterRun", "value closed", "session closed"), events);
        assertSame(Thread.currentThread(), value.get().closedOn);
    }

    @Test
    @DisplayName("a failed run's values are closed after onRunError, and before its copy's session is closed")
    void closedAfterOnRunErrorBeforeTheCopyIsGivenBack() {
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("key", () -> new Value("value"));
            self.get().load(RULES);
            throw new IllegalStateException("the rule failed");
        })) {
            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        }

        assertEquals(List.of("onRunError", "value closed", "session closed"), events);
    }

    @Test
    @DisplayName("values are closed in the reverse of the order they were made, a value its init asked for after it")
    void closedInReverseOrder() {
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            // b's init asks for c, so c is made first, and b, made from it, is closed before it.
            context.runScopedClosing("b", () -> {
                context.runScopedClosing("c", () -> new Value("c"));
                return new Value("b");
            });
            context.runScopedClosing("a", () -> new Value("not made"));
        })) {
            engine.run(new FactMap<>());

            assertEquals(List.of("afterRun", "b closed", "c closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("a value is closed when the run passes its deadline")
    void closedWhenTheRunTimesOut() {
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("key", () -> new Value("value"));
            while (!context.isCancelled()) {
                Thread.onSpinWait();
            }
        }, Duration.ofMillis(50))) {
            RuleExecutionException stop = assertThrows(RuleExecutionException.class,
                    () -> engine.run(new FactMap<>()));

            assertTrue(ReportedFailure.isStop(stop), stop::toString);

            assertEquals(List.of("onRunError", "value closed"), events);
        }
    }

    @Test
    @DisplayName("a value is closed when the run is interrupted, with the thread's interrupt status set")
    void closedWhenTheRunIsInterrupted() {
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("key", () -> new Value("value"));
            Thread.currentThread().interrupt();
        })) {
            RuleExecutionException stop = assertThrows(RuleExecutionException.class,
                    () -> engine.run(new FactMap<>()));

            assertTrue(ReportedFailure.isStop(stop), stop::toString);
            assertTrue(Thread.interrupted(), "the run's interrupt status was not kept");

            assertEquals(List.of("onRunError", "value closed interrupted"), events);
        }
    }

    @Test
    @DisplayName("a value is closed when a rule fails with a fatal Error, which run() still throws")
    void closedWhenTheRunFailsWithAFatalError() {
        OutOfMemoryError fatal = new OutOfMemoryError("the rule ran out");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("key", () -> new Value("value"));
            throw fatal;
        })) {
            assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));

            assertEquals(List.of("onRunError", "value closed"), events);
        }
    }

    @Test
    @DisplayName("a close() that throws is logged at WARN, the rest are closed, and the run's result stands")
    void closeFailureIsLoggedAndTheRunStands() {
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", new IllegalStateException("b failed to close")));
            context.runScopedClosing("c", () -> new Value("c", new IOException("c failed to close")));
        })) {
            AtomicReference<RunResult<Map<String, Object>>> result = new AtomicReference<>();
            Outcome<Throwable> outcome = capture(() -> result.set(engine.runWithResult(new FactMap<>())));

            assertNull(outcome.thrown(), outcome.logs());
            assertEquals(List.of("r"), result.get().firedRules().stream().map(Rule::getRuleName).toList());
            assertEquals(List.of(WARN_PREFIX + "c failed to close", WARN_PREFIX + "b failed to close"),
                    outcome.lines("WARN"));

            assertEquals(List.of("afterRun", "c closed", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("a fatal Error from a close() is logged, the rest are closed, and run() throws it after afterRun")
    void fatalCloseFailureIsRethrown() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing ran out");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", fatal));
        })) {
            Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

            assertSame(fatal, outcome.thrown());
            assertEquals(List.of(WARN_PREFIX + "closing ran out"), outcome.lines("WARN"));

            assertEquals(List.of("afterRun", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("a fatal Error from a close() replaces a failure of the run that isn't fatal, and carries it")
    void fatalCloseFailureReplacesTheRunsFailure() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing ran out");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("key", () -> new Value("value", fatal));
            throw new IllegalStateException("the rule failed");
        })) {
            Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

            assertSame(fatal, outcome.thrown());
            assertEquals(1, fatal.getSuppressed().length, () -> Arrays.toString(fatal.getSuppressed()));
            RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class,
                    fatal.getSuppressed()[0]);
            assertEquals("the rule failed", failure.getCause().getMessage());

            assertEquals(List.of("onRunError", "value closed"), events);
        }
    }

    @Test
    @DisplayName("a nested run closes its own values when it ends, and the outer run's when that ends")
    void nestedRunClosesItsOwnValues() {
        AtomicReference<List<String>> afterNestedRun = new AtomicReference<>();
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            if (context.facts().containsKey("nested")) {
                context.runScopedClosing("key", () -> new Value("inner"));
                return;
            }
            context.runScopedClosing("key", () -> new Value("outer"));
            FactMap<Object> nested = new FactMap<>();
            nested.setValue("nested", true);
            self.get().run(nested);
            afterNestedRun.set(List.copyOf(events));
        })) {
            engine.run(new FactMap<>());

            assertEquals(List.of("afterRun", "inner closed"), afterNestedRun.get());
            assertEquals(List.of("afterRun", "inner closed", "afterRun", "outer closed"), events);
        }
    }

    @Test
    @DisplayName("a closing value asked for once the run's values are being closed, or have been, fails")
    void closingValueAfterTheRunEndedFails() {
        AtomicReference<ActionContext> kept = new AtomicReference<>();
        AtomicReference<Object> plainInClose = new AtomicReference<>();
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            kept.set(context);
            context.runScopedClosing("key", () -> new Value("value") {
                @Override
                public void close() throws IOException {
                    super.close();
                    plainInClose.set(context.runScoped("plain", () -> "made in close()"));
                    context.runScopedClosing("late", () -> new Value("late"));
                }
            });
        })) {
            Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

            assertNull(outcome.thrown(), outcome.logs());
            assertEquals(List.of(WARN_PREFIX + "runScopedClosing was called for a key (java.lang.String) after the run"
                    + " ended, when its value would never be closed"), outcome.lines("WARN"));
            assertEquals(List.of("afterRun", "value closed"), events);
        }

        assertEquals("made in close()", plainInClose.get());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> kept.get().runScopedClosing("key", () -> new Value("again")));
        assertEquals("runScopedClosing was called for a key (java.lang.String) after the run ended, when its value"
                + " would never be closed", ex.getMessage());
        assertEquals("made after the run", kept.get().runScoped("after", () -> "made after the run"));
    }

    @Test
    @DisplayName("a key is either closing or not: asking for it the other way fails, and keeps the value")
    void keyKindClash() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        Object plain = context.runScoped("plain", Object::new);
        Value closing = context.runScopedClosing("closing", () -> new Value("closing"));

        IllegalStateException asClosing = assertThrows(IllegalStateException.class,
                () -> context.runScopedClosing("plain", () -> new Value("not made")));
        IllegalStateException asPlain = assertThrows(IllegalStateException.class,
                () -> context.runScoped("closing", Object::new));

        assertEquals("runScopedClosing was called for a key (java.lang.String) that runScoped keeps a value under",
                asClosing.getMessage());
        assertEquals("runScoped was called for a key (java.lang.String) that runScopedClosing keeps a value under",
                asPlain.getMessage());
        assertSame(plain, context.runScoped("plain", Object::new));
        assertSame(closing, context.runScopedClosing("closing", () -> new Value("not made")));
        assertEquals(List.of(closing), EngineEvaluationContext.runScopeOf(context).end());
    }

    @Test
    @DisplayName("an init that asks for its own closing key fails, rather than recursing or making the value twice")
    void closingInitAskingForItsOwnKeyFails() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> context.runScopedClosing("key", () -> context.runScopedClosing("key", () -> new Value("inner"))));

        assertEquals("runScopedClosing was called for a key (java.lang.String) while that key's init is running",
                ex.getMessage());
        assertEquals(List.of(), EngineEvaluationContext.runScopeOf(context).end());
    }

    @Test
    @DisplayName("a value kept with runScoped is still let go without being closed")
    void runScopedValuesAreNotClosed() {
        try (RulesEngine<Map<String, Object>> engine = engine(
                context -> context.runScoped("key", () -> new Value("plain")))) {
            engine.run(new FactMap<>());

            assertEquals(List.of("afterRun"), events);
        }
    }

    @Test
    @DisplayName("ending a scope hands its closing values back once, and a scope that kept none hands back nothing")
    void endHandsBackOnce() {
        RunScope scope = new RunScope();
        assertEquals(List.of(), scope.end());
        assertFalse(scope.allocated());

        RunScope used = new RunScope();
        Value value = used.getClosing("key", () -> new Value("value"));
        assertEquals(List.of(value), used.end());
        assertEquals(List.of(), used.end());
        assertEquals("plain", used.get("plain", () -> "plain"));
    }

    @Test
    @DisplayName("#850: what fails as a run's values are taken to be closed still lets every value close and the copy"
            + " go back, and run() throws it after afterRun, as a failure to give back the copy is thrown")
    void closingStepFailureStillClosesTheValuesAndGivesBackTheCopy() {
        StackOverflowError injected = new StackOverflowError("injected");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b"));
            self.get().load(RULES);
        })) {
            Faults.inject(Faults.Step.RUN_VALUES_CLOSING, 1, injected);

            assertSame(injected, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            // The values were taken before the step failed, so none can be made, and they're closed all the same.
            assertEquals(List.of("afterRun", "b closed", "a closed", "session closed"), events);

            events.clear();
            engine.run(new FactMap<>());
            assertEquals(List.of("afterRun", "b closed", "a closed", "session closed"), events);
        }
    }

    @Test
    @DisplayName("#850: of a failed run's failure and a failure ending it, neither fatal, the ending's is thrown,"
            + " carrying the run's, and the values are closed and the copy given back")
    void closingStepFailureAfterAFailedRun() {
        StackOverflowError injected = new StackOverflowError("injected");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("key", () -> new Value("value"));
            self.get().load(RULES);
            throw new IllegalStateException("the rule failed");
        })) {
            Faults.inject(Faults.Step.RUN_VALUES_CLOSING, 1, injected);

            assertSame(injected, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(1, injected.getSuppressed().length, () -> Arrays.toString(injected.getSuppressed()));
            assertInstanceOf(RuleExecutionException.class, injected.getSuppressed()[0]);
            assertEquals(List.of("onRunError", "value closed", "session closed"), events);
        }
    }

    @Test
    @DisplayName("#850: a run that failed with a fatal Error throws it, not a failure ending the run, which it carries")
    void fatalRunFailureWinsOverAFailureEndingTheRun() {
        OutOfMemoryError fatal = new OutOfMemoryError("the rule ran out");
        StackOverflowError injected = new StackOverflowError("injected");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("key", () -> new Value("value"));
            throw fatal;
        })) {
            Faults.inject(Faults.Step.RUN_VALUES_CLOSING, 1, injected);

            assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertTrue(Arrays.asList(fatal.getSuppressed()).contains(injected),
                    () -> Arrays.toString(fatal.getSuppressed()));
            assertEquals(List.of("onRunError", "value closed"), events);
        }
    }

    @Test
    @DisplayName("#850: a fatal Error inside what fails as a run ends is thrown itself")
    void fatalErrorInsideAnEndingFailureIsThrownItself() {
        OutOfMemoryError fatal = new OutOfMemoryError("ran out");
        StackOverflowError injected = new StackOverflowError("injected");
        injected.initCause(fatal);
        try (RulesEngine<Map<String, Object>> engine = engine(
                context -> context.runScopedClosing("key", () -> new Value("value")))) {
            Faults.inject(Faults.Step.RUN_VALUES_CLOSING, 1, injected);

            assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(List.of("afterRun", "value closed"), events);
        }
    }

    @Test
    @DisplayName("#850: when logging what a value's close() threw fails, the other values are still closed, and run()"
            + " throws what logging threw, carrying what close() threw")
    void loggingACloseFailureThatFailsStillClosesTheRest() {
        IllegalStateException cFailure = new IllegalStateException("c failed to close");
        StackOverflowError logging = new StackOverflowError("logging");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", new IllegalStateException("b failed to close")));
            context.runScopedClosing("c", () -> new Value("c", cFailure));
        })) {
            // c is closed first, so logging its failure is what fails.
            Faults.inject(Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, logging);

            Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

            assertSame(logging, outcome.thrown(), outcome.logs());
            assertArrayEquals(new Throwable[] {cFailure}, logging.getSuppressed());
            assertEquals(List.of(WARN_PREFIX + "b failed to close"), outcome.lines("WARN"));
            assertEquals(List.of("afterRun", "c closed", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: when logging a fatal Error from a value's close() fails, the rest are closed and run() throws"
            + " the fatal Error, carrying what logging threw")
    void loggingAFatalCloseFailureThatFailsStillThrowsTheFatalError() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing ran out");
        StackOverflowError logging = new StackOverflowError("logging");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", fatal));
        })) {
            Faults.inject(Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, logging);

            assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertArrayEquals(new Throwable[] {logging}, fatal.getSuppressed());
            assertEquals(List.of("afterRun", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: when keeping what logging a value's failure threw fails too, the other values are still closed"
            + " and logged, and run() throws what keeping it threw")
    void handlingACloseFailureThatFailsStillClosesTheRest() {
        StackOverflowError handling = new StackOverflowError("handling");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", new IllegalStateException("b failed to close")));
            context.runScopedClosing("c", () -> new Value("c", new IllegalStateException("c failed to close")));
        })) {
            // c is closed first: logging its failure fails, and so does keeping what that threw.
            Faults.inject(Thread.currentThread(), Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, 2, handling);

            Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

            assertSame(handling, outcome.thrown(), outcome.logs());
            assertEquals(List.of(WARN_PREFIX + "b failed to close"), outcome.lines("WARN"));
            assertEquals(List.of("afterRun", "c closed", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: when handling two values' failures fails, every value is still closed, and run() throws what"
            + " the first handling threw")
    void handlingTwoCloseFailuresThatFailStillClosesEveryValue() {
        StackOverflowError handling = new StackOverflowError("handling");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", new IllegalStateException("b failed to close")));
            context.runScopedClosing("c", () -> new Value("c", new IllegalStateException("c failed to close")));
        })) {
            // Logging, and keeping what that threw, fails for both c and b.
            Faults.inject(Thread.currentThread(), Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, 4, handling);

            Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

            assertSame(handling, outcome.thrown(), outcome.logs());
            assertEquals(List.of(), outcome.lines("WARN"));
            assertEquals(List.of("afterRun", "c closed", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: the first fatal Error met as the values close wins, whether logging it failed or not, and"
            + " carries the later one")
    void firstFatalCloseFailureWins() {
        OutOfMemoryError first = new OutOfMemoryError("c ran out");
        OutOfMemoryError later = new OutOfMemoryError("b ran out");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", later));
            context.runScopedClosing("c", () -> new Value("c", first));
        })) {
            // Logging c's failure, the first, fails; b's is logged.
            Faults.inject(Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, new StackOverflowError("logging"));

            assertSame(first, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertTrue(Arrays.asList(first.getSuppressed()).contains(later),
                    () -> Arrays.toString(first.getSuppressed()));
            assertEquals(List.of("afterRun", "c closed", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: when logging a close() failure an interrupt caused fails, the thread's interrupt status is"
            + " still set again")
    void interruptKeptWhenLoggingACloseFailureFails() {
        StackOverflowError logging = new StackOverflowError("logging");
        try (RulesEngine<Map<String, Object>> engine = engine(
                context -> context.runScopedClosing("key", () -> new Value("value",
                        new IllegalStateException("interrupted while closing", new InterruptedException()))))) {
            Faults.inject(Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, logging);

            assertSame(logging, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertTrue(Thread.interrupted(), "the interrupt that made close() fail was lost");
        }
    }

    @Test
    @DisplayName("#850: what fails once every value is closed, combining what they threw, is thrown, and the copy is"
            + " still given back")
    void combiningTheValuesFailuresFails() {
        StackOverflowError injected = new StackOverflowError("injected");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            self.get().load(RULES);
        })) {
            Faults.inject(Faults.Step.RUN_VALUES_CLOSED, 1, injected);

            assertSame(injected, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(List.of("afterRun", "a closed", "session closed"), events);
        }
    }

    @Test
    @DisplayName("#850: what fails combining what ending the run threw is thrown, after every value is closed and the"
            + " copy given back")
    void combiningTheEndingFails() {
        StackOverflowError injected = new StackOverflowError("injected");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            self.get().load(RULES);
        })) {
            Faults.inject(Faults.Step.RUN_ENDING_COMBINED, 1, injected);

            assertSame(injected, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(List.of("afterRun", "a closed", "session closed"), events);
        }
    }

    @Test
    @DisplayName("#850: a fatal Error from a value's close() reaches run() even when logging it and keeping what that"
            + " threw both fail")
    void fatalCloseFailureKeptWhenHandlingItFails() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing ran out");
        StackOverflowError handling = new StackOverflowError("handling");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a"));
            context.runScopedClosing("b", () -> new Value("b", fatal));
        })) {
            Faults.inject(Thread.currentThread(), Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, 2, handling);

            assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(List.of("afterRun", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: when handling several values' failures fails, a fatal Error one of them threw is the one kept")
    void fatalCloseFailureKeptAmongSeveralWhoseHandlingFails() {
        OutOfMemoryError fatal = new OutOfMemoryError("a ran out");
        StackOverflowError handling = new StackOverflowError("handling");
        try (RulesEngine<Map<String, Object>> engine = engine(context -> {
            context.runScopedClosing("a", () -> new Value("a", fatal));
            context.runScopedClosing("b", () -> new Value("b", new StackOverflowError("b overflowed")));
            context.runScopedClosing("c", () -> new Value("c", new IllegalStateException("c failed to close")));
            context.runScopedClosing("d", () -> new Value("d", new IllegalStateException("d failed to close")));
        })) {
            // Logging, and keeping what that threw, fails for every value.
            Faults.inject(Thread.currentThread(), Faults.Step.RUN_VALUE_FAILURE_LOGGED, 1, 8, handling);

            assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(List.of("afterRun", "d closed", "c closed", "b closed", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: when combining what ending the run threw fails, a fatal Error a value threw is thrown in its"
            + " place")
    void combiningTheEndingFailsKeepsAFatalError() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing ran out");
        StackOverflowError injected = new StackOverflowError("injected");
        try (RulesEngine<Map<String, Object>> engine = engine(
                context -> context.runScopedClosing("a", () -> new Value("a", fatal)))) {
            Faults.inject(Faults.Step.RUN_ENDING_COMBINED, 1, injected);

            assertSame(fatal, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertEquals(List.of("afterRun", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: an Error taking a run's values to close is thrown once they're closed, unless a fatal Error"
            + " closing one comes after it")
    void errorTakingTheValuesIsThrown() {
        OutOfMemoryError taking = new OutOfMemoryError("taking ran out");
        OutOfMemoryError closing = new OutOfMemoryError("closing ran out");
        AssertionError plain = new AssertionError("taking failed");
        try (RulesEngine<Map<String, Object>> engine = engine(
                context -> context.runScopedClosing("a", () -> new Value("a", closing)))) {
            Faults.inject(Faults.Step.RUN_VALUES_CLOSING, 1, taking);
            assertSame(taking, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));

            Faults.inject(Faults.Step.RUN_VALUES_CLOSING, 1, plain);
            assertSame(closing, EngineLogs.thrownBy(() -> engine.run(new FactMap<>())));
            assertTrue(Arrays.asList(closing.getSuppressed()).contains(plain),
                    () -> Arrays.toString(closing.getSuppressed()));
            assertEquals(List.of("afterRun", "a closed", "afterRun", "a closed"), events);
        }
    }

    @Test
    @DisplayName("#850: a closing value asked for on another thread as the run ends is either handed back to be closed"
            + " or refused, never left open")
    void closingValuesAskedForOnAnotherThreadAreClosedOrRefused() throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            RunScope scope = new RunScope();
            List<AutoCloseable> made = new CopyOnWriteArrayList<>();
            AtomicReference<Throwable> stopped = new AtomicReference<>();
            CountDownLatch some = new CountDownLatch(50);
            Thread other = new Thread(() -> {
                try {
                    for (int i = 0; ; i++) {
                        int n = i;
                        made.add(scope.getClosing(n, () -> new Value("v" + n)));
                        some.countDown();
                    }
                } catch (Throwable e) {
                    stopped.set(e);
                    // So the test doesn't wait for values that will never be made.
                    while (some.getCount() > 0) {
                        some.countDown();
                    }
                }
            });
            other.setDaemon(true);
            other.start();
            assertTrue(some.await(10, TimeUnit.SECONDS), "the other thread made too few values");

            List<AutoCloseable> handedBack = scope.end();
            other.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(other.isAlive(), "the other thread was never refused");
            assertInstanceOf(IllegalStateException.class, stopped.get(), () -> String.valueOf(stopped.get()));
            assertEquals(made, handedBack, "a value made on the other thread was neither handed back nor refused");
        }
    }
}
