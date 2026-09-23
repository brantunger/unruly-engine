package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestLogs;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run's deadline bounds the whole run: the wait for a compiled copy of the rules, and any run started from inside
 * it on the same thread. These use only the builder's {@code runTimeout}, so they compile against an engine that
 * predates {@code RunOptions}.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a run's deadline covers waiting for a copy and the runs it starts")
class RunDeadlineTest {

    private static final Duration SHORT = Duration.ofMillis(300);
    private static final long PAUSE_MILLIS = 900;

    /** A fact an action calls to hold the rules' only copy until the test lets it go. */
    public static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * Tells the test the copy is held, then waits to be let go.
         *
         * @return {@code true}
         * @throws InterruptedException if the thread is interrupted while waiting
         */
        public boolean hold() throws InterruptedException {
            entered.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS), "the test never released the gate");
            return true;
        }
    }

    /** A fact a condition calls to take longer than {@link #SHORT}. */
    public static final class Pause {

        /**
         * Sleeps, then answers {@code true}.
         *
         * @return {@code true}
         * @throws InterruptedException if the thread is interrupted while sleeping
         */
        public boolean longer() throws InterruptedException {
            Thread.sleep(PAUSE_MILLIS);
            return true;
        }
    }

    /** A fact an action calls to run another engine from inside a run, recording how that run ended. */
    public static final class Nested {

        private final RulesEngine<Map<String, Object>> inner;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Nested(RulesEngine<Map<String, Object>> inner) {
            this.inner = inner;
        }

        /**
         * Runs the inner engine, keeping what it threw rather than failing the outer run.
         *
         * @return {@code true}
         */
        public boolean run() {
            try {
                FactStore<Object> facts = new FactMap<>();
                facts.setValue("pause", new Pause());
                inner.run(facts);
            } catch (RuntimeException e) {
                failure.set(e);
            }
            return true;
        }
    }

    /** A fact an action calls to run another engine from inside a run, letting what it throws leave the action. */
    public static final class Propagating {

        private final RulesEngine<Map<String, Object>> inner;

        Propagating(RulesEngine<Map<String, Object>> inner) {
            this.inner = inner;
        }

        /**
         * Runs the inner engine.
         *
         * @return {@code true}
         */
        public boolean run() {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("pause", new Pause());
            inner.run(facts);
            return true;
        }
    }

    /** A fact a condition calls to interrupt its own thread and then give up. */
    public static final class Interrupter {

        /**
         * Interrupts the current thread, then throws, as an expression that notices it was cancelled may.
         *
         * @return never
         */
        public boolean giveUp() {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gave up");
        }
    }

    /** A fact a condition calls to fail after the run's deadline, the way a failing method can. */
    public static final class LateFailure {

        /**
         * Waits past {@link #SHORT}, then throws an {@link OutOfMemoryError} wrapped in another exception.
         *
         * @return never
         * @throws InterruptedException if the thread is interrupted while waiting
         */
        public boolean outOfMemory() throws InterruptedException {
            Thread.sleep(PAUSE_MILLIS);
            throw new IllegalStateException(new OutOfMemoryError("a test's"));
        }

        /**
         * Waits past {@link #SHORT}, then catches an interrupt, which clears the thread's interrupt status, and throws
         * it wrapped.
         *
         * @return never
         * @throws InterruptedException if the thread is interrupted while it first waits
         */
        public boolean swallowAnInterrupt() throws InterruptedException {
            Thread.sleep(PAUSE_MILLIS);
            Thread.currentThread().interrupt();
            try {
                Thread.sleep(1);
                return true;
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
            }
        }
    }

    /** Records the rule callbacks a run makes. */
    private static final class RuleCallbacks implements RuleListener {

        private final List<String> seen = new CopyOnWriteArrayList<>();
        private final List<RuleExecutionException> errors = new CopyOnWriteArrayList<>();

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            seen.add("beforeEvaluate " + rule.getRuleName());
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            seen.add("beforeExecute " + rule.getRuleName());
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            seen.add("onError " + rule.getRuleName());
            errors.add(error);
        }
    }

    /**
     * Whether a throwable or any of its causes has a message containing {@code text}; MVEL wraps what a method
     * threw.
     */
    private static boolean chainMentions(Throwable thrown, String text) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static RulesEngine<Map<String, Object>> engine(RulesEngineBuilder<Map<String, Object>> builder,
                                                           List<Rule> rules) {
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(rules);
        return engine;
    }

    /** Two rules, the first slower than {@link #SHORT}, so a run with that timeout stops when the first returns. */
    private static List<Rule> slowThenFast() {
        return List.of(rule("slow", "pause.longer()", "output.put('slow', true)"),
                rule("fast", "true", "output.put('fast', true)"));
    }

    @Test
    @DisplayName("a run waiting for the only copy of the rules stops waiting at its deadline")
    void waitingForACopyStopsAtTheDeadline() throws Exception {
        List<String> callbacks = new CopyOnWriteArrayList<>();
        RuleListener recorder = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                callbacks.add("beforeRun");
            }

            @Override
            public void afterRun(RunContext run, RunResult<?> result) {
                callbacks.add("afterRun");
            }

            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                callbacks.add("onRunError");
            }
        };
        RulesEngine<Map<String, Object>> engine = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                        HashMap::new).maxCopies(1).runTimeout(SHORT).listener(recorder),
                List.of(rule("hold", "true", "output.put('held', gate.hold())")));
        Gate gate = new Gate();
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("gate", gate);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> engine.run(facts));
            assertTrue(gate.entered.await(30, TimeUnit.SECONDS), "the holder never took the copy");
            callbacks.clear();

            long start = System.nanoTime();
            Future<Map<String, Object>> waiter = threads.submit(() -> engine.run(new FactMap<>()));
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> waiter.get(10 * SHORT.toMillis(), TimeUnit.MILLISECONDS),
                    "the waiting run didn't stop at its deadline while the copy was still held");
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, thrown.getCause());
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(failure.getMessage().contains("while waiting for a compiled copy of the rules: all 1 were in"
                    + " use"), failure.getMessage());
            assertNull(failure.getRuleName());
            assertTrue(waitedMillis < 10 * SHORT.toMillis(), "waited " + waitedMillis + " ms");
            assertEquals(List.of("beforeRun", "onRunError"), callbacks);

            gate.release.countDown();
            ExecutionException holderStop = assertThrows(ExecutionException.class,
                    () -> holder.get(30, TimeUnit.SECONDS), "the holder's action returned past its deadline too");
            assertInstanceOf(TimeoutException.class, holderStop.getCause().getCause());
        } finally {
            gate.release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("a run started from inside another stops at the outer run's deadline when it has none of its own")
    void aNestedRunInheritsTheOuterDeadline() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.allMatches(HashMap::new), slowThenFast());
        Nested nested = new Nested(inner);
        RulesEngine<Map<String, Object>> outer = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).runTimeout(SHORT), List.of(rule("outer", "true", "output.put('ran', nested.run())")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("nested", nested);

        RuleExecutionException outerStop = assertThrows(RuleExecutionException.class, () -> outer.run(facts));
        assertTrue(outerStop.getMessage().endsWith(" during rule 'outer'"), outerStop.getMessage());

        RuleExecutionException failure = assertInstanceOf(RuleExecutionException.class, nested.failure.get(),
                "the nested run ignored the outer run's deadline");
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(failure.getMessage().endsWith(" during rule 'slow'"), failure.getMessage());
    }

    @Test
    @DisplayName("a nested run keeps its own deadline when it comes before the outer run's")
    void aNestedRunKeepsAnEarlierDeadline() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).runTimeout(SHORT), slowThenFast());
        Nested nested = new Nested(inner);
        RulesEngine<Map<String, Object>> outer = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).runTimeout(Duration.ofMinutes(1)),
                List.of(rule("outer", "true", "output.put('ran', nested.run())")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("nested", nested);

        assertEquals(Map.of("ran", true), outer.run(facts));

        assertInstanceOf(TimeoutException.class, nested.failure.get().getCause());
    }

    @Test
    @DisplayName("a nested run stopped by the outer deadline stops the outer run too, rather than failing its rule")
    void aNestedStopStopsTheOuterRun() {
        RulesEngine<Map<String, Object>> inner = engine(RulesEngineBuilder.allMatches(HashMap::new), slowThenFast());
        RuleCallbacks callbacks = new RuleCallbacks();
        RulesEngine<Map<String, Object>> outer = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).runTimeout(SHORT).listener(callbacks),
                List.of(rule("outer", "true", "output.put('ran', nested.run())")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("nested", new Propagating(inner));

        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();
        String logs = TestLogs.logsOf(() -> thrown.set(
                assertThrows(RuleExecutionException.class, () -> outer.run(facts))));

        RuleExecutionException stop = thrown.get();
        assertNull(stop.getRuleName(), stop.getMessage());
        assertInstanceOf(TimeoutException.class, stop.getCause());
        assertTrue(stop.getMessage().startsWith("run() passed its deadline of "), stop.getMessage());
        assertTrue(stop.getMessage().endsWith(" during rule 'outer'"), stop.getMessage());
        assertEquals(1, stop.getSuppressed().length, "what the action threw is kept");
        assertTrue(chainMentions(stop.getSuppressed()[0], " during rule 'slow'"), "the inner stop isn't kept");
        assertEquals(List.of("beforeEvaluate outer", "beforeExecute outer", "onError outer"), callbacks.seen);
        assertSame(stop, callbacks.errors.get(0));
        assertFalse(logs.contains("ERROR"), logs);
    }

    @Test
    @DisplayName("a condition that throws after its thread was interrupted stops the run, rather than failing its rule")
    void aConditionThatGivesUpWhenInterrupted() {
        RuleCallbacks callbacks = new RuleCallbacks();
        RulesEngine<Map<String, Object>> engine = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).listener(callbacks), List.of(rule("gives-up", "stopper.giveUp()", "output.put('x', 1)")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("stopper", new Interrupter());

        try {
            RuleExecutionException stop = assertThrows(RuleExecutionException.class, () -> engine.run(facts));

            assertNull(stop.getRuleName());
            assertInstanceOf(InterruptedException.class, stop.getCause());
            assertEquals("run() was interrupted during rule 'gives-up'", stop.getMessage());
            assertTrue(chainMentions(stop.getSuppressed()[0], "gave up"), "what the condition threw isn't kept");
            assertEquals(List.of("beforeEvaluate gives-up", "onError gives-up"), callbacks.seen);
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt status stays set");
        }
    }

    @Test
    @DisplayName("a fatal Error inside what a condition threw is rethrown even when the run was past its deadline")
    void aFatalErrorWinsOverTheDeadline() {
        RulesEngine<Map<String, Object>> engine = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).runTimeout(SHORT), List.of(rule("late", "late.outOfMemory()", "output.put('x', 1)")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("late", new LateFailure());

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> engine.run(facts));

        assertEquals("a test's", thrown.getMessage());
    }

    @Test
    @DisplayName("an interrupt a condition caught and wrapped after the deadline stays set, and stops the run as one")
    void aSwallowedInterruptIsKept() {
        RulesEngine<Map<String, Object>> engine = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).runTimeout(SHORT),
                List.of(rule("swallows", "late.swallowAnInterrupt()", "output.put('x', 1)")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("late", new LateFailure());

        try {
            RuleExecutionException stop = assertThrows(RuleExecutionException.class, () -> engine.run(facts));

            assertInstanceOf(InterruptedException.class, stop.getCause());
            assertEquals("run() was interrupted during rule 'swallows'", stop.getMessage());
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt the condition caught was lost");
        }
    }

    @Test
    @DisplayName("a run's deadline ends with it: a later run on the same thread doesn't inherit it")
    void theDeadlineDoesntOutliveTheRun() {
        RulesEngine<Map<String, Object>> timed = engine(RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).runTimeout(SHORT), List.of(rule("quick", "true", "output.put('quick', true)")));
        RulesEngine<Map<String, Object>> untimed = engine(RulesEngineBuilder.allMatches(HashMap::new),
                slowThenFast());
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("pause", new Pause());

        assertEquals(Map.of("quick", true), timed.run(new FactMap<>()));

        assertEquals(Map.of("slow", true, "fast", true), untimed.run(facts));
    }
}
