package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestSupport;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestSupport.await;
import static io.github.brantunger.unruly.TestSupport.throwIfSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1052: a run's closing values are closed even when its scope fails for stack or memory as it refuses a value or
 * ends: a value whose init ended the run is closed before its refusal is built, a run and the test kit call
 * {@link RunScope#end()} once more when it fails as it waits for another thread's init, an {@code end()} that fails as
 * it sets the interrupt again leaves the values to the next call, and the test kit closes every value whatever keeping
 * one's failure throws. Each failure is made with a {@link Faults} step where the stack or the heap could run out.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("#1052: a run's closing values are closed when its scope fails for stack or memory as it refuses or ends")
class RunScopeEndFailureTest {

    private static final List<Rule> RULES = List.of(Rule.builder().ruleName("r").condition("c").action("a").build());
    private static final String ENDED = "runScopedClosing was called for a key (java.lang.String) after the run ended,"
            + " when its value would never be closed";

    // The values closed, in order.
    private final List<String> closed = new CopyOnWriteArrayList<>();

    @AfterEach
    void clearFaults() {
        Faults.clear();
    }

    /** A value a language keeps for a run, which records its close() and then throws what the test set, if anything. */
    private final class Value implements AutoCloseable {
        private final String name;
        private final Throwable failure;

        Value(String name) {
            this(name, null);
        }

        Value(String name, Throwable failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public void close() {
            closed.add(name + " closed");
            TestSupport.throwIfSet(failure);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    @DisplayName("#1052 item 1: a value whose init ended the run is closed when building its refusal fails, and what"
            + " that threw is thrown")
    void refusedValueIsClosedWhenBuildingTheRefusalFails() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        StackOverflowError building = new StackOverflowError("building the refusal");
        Faults.inject(Faults.Step.RUN_VALUE_REFUSING, 1, building);

        assertSame(building, assertThrows(StackOverflowError.class, () -> context.runScopedClosing("key", () -> {
            endRun(context);
            return new Value("key");
        })));
        assertEquals(List.of("key closed"), closed);
        assertEquals(List.of(), EngineEvaluationContext.runScopeOf(context).end());
    }

    @Test
    @DisplayName("#1052 item 1: a fatal Error from the close() of a value whose init ended the run is thrown when"
            + " building the refusal fails")
    void fatalCloseFailureWinsOverAFailureBuildingTheRefusal() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        OutOfMemoryError fatal = new OutOfMemoryError("close failed");
        Faults.inject(Faults.Step.RUN_VALUE_REFUSING, 1, new StackOverflowError("building the refusal"));

        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> context.runScopedClosing("key", () -> {
            endRun(context);
            return new Value("key", fatal);
        })));
        assertEquals(List.of("key closed"), closed);
    }

    @Test
    @DisplayName("#1052 item 1: what building the refusal threw is thrown over a close() failure that isn't fatal,"
            + " a StackOverflowError included")
    void failureBuildingTheRefusalWinsOverACloseFailureThatIsNotFatal() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        StackOverflowError building = new StackOverflowError("building the refusal");
        Faults.inject(Faults.Step.RUN_VALUE_REFUSING, 1, building);

        assertSame(building, assertThrows(StackOverflowError.class, () -> context.runScopedClosing("key", () -> {
            endRun(context);
            return new Value("key", new StackOverflowError("close failed"));
        })));
        assertEquals(List.of("key closed"), closed);
    }

    @Test
    @DisplayName("#1052 item 2: when ending a run fails with StackOverflowError as it waits for another thread's"
            + " closing init, the run ends it again, closes every value, and throws what the first call threw")
    void runEndsItsScopeAgainWhenEndingFailsForStack() throws Exception {
        StackOverflowError waiting = new StackOverflowError("waiting");

        assertSame(waiting, runEndingWhileAnotherThreadHoldsTheTurn(waiting, null));
        assertEquals(List.of("held closed", "kept closed"), closed);
    }

    @Test
    @DisplayName("#1052 item 2: when ending a run fails with OutOfMemoryError as it waits for another thread's closing"
            + " init, the run ends it again, closes every value, and throws the error")
    void runEndsItsScopeAgainWhenEndingFailsForMemory() throws Exception {
        OutOfMemoryError waiting = new OutOfMemoryError("waiting");

        assertSame(waiting, runEndingWhileAnotherThreadHoldsTheTurn(waiting, null));
        assertEquals(List.of("held closed", "kept closed"), closed);
    }

    @Test
    @DisplayName("#1052 item 2: a run ends its scope again only once: when that fails too, the run throws what the"
            + " first call threw, carrying what the second did")
    void runEndsItsScopeAgainOnlyOnce() throws Exception {
        StackOverflowError first = new StackOverflowError("waiting");
        StackOverflowError second = new StackOverflowError("waiting again");

        assertSame(first, runEndingWhileAnotherThreadHoldsTheTurn(first, second));
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
        // A third call would have waited for the init and closed the values.
        assertEquals(List.of(), closed);
    }

    /**
     * Runs an engine whose action keeps a closing value, then starts a thread whose closing init holds the scope's
     * turn until the run's thread waits for it again or has ended, with the step where {@code end()} waits failing on
     * the run's thread with {@code first}, and then with {@code second} if it isn't {@code null}. Returns what the run
     * threw.
     */
    private Throwable runEndingWhileAnotherThreadHoldsTheTurn(Error first, Error second) throws Exception {
        CountDownLatch inInit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> holder = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new StubExpressionLanguage().action((context, session) -> {
                    context.runScopedClosing("kept", () -> new Value("kept"));
                    holder.set(daemon(() -> context.runScopedClosing("held", () -> {
                        inInit.countDown();
                        TestSupport.await(release);
                        return new Value("held");
                    }), failed));
                    // Spun rather than awaited, so the run's thread waits, parked with a timeout, only for the turn.
                    while (inInit.getCount() > 0) {
                        Thread.onSpinWait();
                    }
                    return ActionResult.done();
                })).build()) {
            engine.load(RULES);
            Thread run = new Thread(() -> {
                try {
                    engine.run(new FactMap<>());
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            run.setDaemon(true);
            Faults.inject(run, Faults.Step.RUN_SCOPE_END_WAITING, 1, first);
            if (second != null) {
                Faults.injectThen(Faults.Step.RUN_SCOPE_END_WAITING, second);
            }
            run.start();
            try {
                await(() -> run.getState() == Thread.State.TIMED_WAITING || run.getState() == Thread.State.TERMINATED,
                        10, "the run waits for the turn again, or has ended");
            } finally {
                release.countDown();
                run.join(TimeUnit.SECONDS.toMillis(10));
                holder.get().join(TimeUnit.SECONDS.toMillis(10));
            }
            assertFalse(run.isAlive(), "the run never ended");
        }
        throwIfSet(failed.get());
        return thrown.get();
    }

    @Test
    @DisplayName("#1052 item 2: when the test kit's end of a run fails as it waits for another thread's closing init,"
            + " it ends the run again, closes every value, and throws what the first call threw")
    void testKitEndsTheRunAgainWhenEndingFails() throws Exception {
        StackOverflowError waiting = new StackOverflowError("waiting");

        assertSame(waiting, testKitEndingWhileAnotherThreadHoldsTheTurn(waiting, null));
        assertEquals(List.of("held closed", "kept closed"), closed);
    }

    @Test
    @DisplayName("#1052 item 2: the test kit ends a run again only once: when that fails too, it throws what the first"
            + " call threw, carrying what the second did")
    void testKitEndsTheRunAgainOnlyOnce() throws Exception {
        StackOverflowError first = new StackOverflowError("waiting");
        StackOverflowError second = new StackOverflowError("waiting again");

        assertSame(first, testKitEndingWhileAnotherThreadHoldsTheTurn(first, second));
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
        assertEquals(List.of(), closed);
    }

    /**
     * Ends the run of a test kit context that keeps a closing value, on a thread of its own, while another thread's
     * closing init holds the scope's turn, as {@link #runEndingWhileAnotherThreadHoldsTheTurn} does. Returns what
     * ending the run threw.
     */
    private Throwable testKitEndingWhileAnotherThreadHoldsTheTurn(Error first, Error second) throws Exception {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        context.runScopedClosing("kept", () -> new Value("kept"));
        CountDownLatch inInit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread holder = daemon(() -> context.runScopedClosing("held", () -> {
            inInit.countDown();
            TestSupport.await(release);
            return new Value("held");
        }), failed);
        TestSupport.await(inInit);
        Thread ender = new Thread(() -> {
            try {
                EngineEvaluationContext.endRun(context);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        ender.setDaemon(true);
        Faults.inject(ender, Faults.Step.RUN_SCOPE_END_WAITING, 1, first);
        if (second != null) {
            Faults.injectThen(Faults.Step.RUN_SCOPE_END_WAITING, second);
        }
        ender.start();
        try {
            await(() -> ender.getState() == Thread.State.TIMED_WAITING || ender.getState() == Thread.State.TERMINATED,
                    10, "the end of the run waits for the turn again, or has returned");
        } finally {
            release.countDown();
            ender.join(TimeUnit.SECONDS.toMillis(10));
            holder.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(ender.isAlive(), "the end of the run never returned");
        throwIfSet(failed.get());
        return thrown.get();
    }

    @Test
    @DisplayName("#1052: when the test kit fails to keep what a value's close() threw on what it throws, it still"
            + " closes every value, and what it throws carries that failure")
    void testKitClosesEveryValueWhenKeepingAFailureFails() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        IOException aFailure = new IOException("a failed to close");
        IOException cFailure = new IOException("c failed to close");
        context.runScopedClosing("a", () -> new Value("a", aFailure));
        context.runScopedClosing("b", () -> new Value("b", new IOException("b failed to close")));
        context.runScopedClosing("c", () -> new Value("c", cFailure));
        StackOverflowError keeping = new StackOverflowError("keeping b's failure");
        Faults.inject(Faults.Step.TEST_RUN_FAILURE_KEPT, 1, keeping);

        assertSame(cFailure, assertThrows(IOException.class, () -> EngineEvaluationContext.endRun(context)));
        assertEquals(List.of("c closed", "b closed", "a closed"), closed);
        assertArrayEquals(new Throwable[] {aFailure, keeping}, cFailure.getSuppressed());
    }

    @Test
    @DisplayName("#1052: when the test kit fails to keep two close() failures, it closes every value, and what it"
            + " throws carries the first failure to keep them")
    void testKitKeepsTheFirstFailureToKeepAFailure() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        IOException cFailure = new IOException("c failed to close");
        context.runScopedClosing("a", () -> new Value("a", new IOException("a failed to close")));
        context.runScopedClosing("b", () -> new Value("b", new IOException("b failed to close")));
        context.runScopedClosing("c", () -> new Value("c", cFailure));
        StackOverflowError keepingB = new StackOverflowError("keeping b's failure");
        Faults.inject(Faults.Step.TEST_RUN_FAILURE_KEPT, 1, keepingB);
        Faults.injectThen(Faults.Step.TEST_RUN_FAILURE_KEPT, new StackOverflowError("keeping a's failure"));

        assertSame(cFailure, assertThrows(IOException.class, () -> EngineEvaluationContext.endRun(context)));
        assertEquals(List.of("c closed", "b closed", "a closed"), closed);
        assertArrayEquals(new Throwable[] {keepingB}, cFailure.getSuppressed());
    }

    @Test
    @DisplayName("#1052: when end() fails as it sets the interrupt again after its wait, the next call still hands back"
            + " the values")
    void endFailingToSetTheInterruptAgainLeavesTheValuesToTheNextCall() throws Exception {
        RunScope scope = new RunScope();
        CountDownLatch inInit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Value> held = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<List<AutoCloseable>> handedBack = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread holder = daemon(() -> held.set(scope.getClosing("held", () -> {
            inInit.countDown();
            TestSupport.await(release);
            return new Value("held");
        })), failed);
        TestSupport.await(inInit);
        Thread ender = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                scope.end();
            } catch (Throwable e) {
                thrown.set(e);
            }
            handedBack.set(scope.end());
        });
        ender.setDaemon(true);
        StackOverflowError interrupting = new StackOverflowError("setting the interrupt again");
        Faults.inject(ender, Faults.Step.RUN_SCOPE_END_INTERRUPTING, 1, interrupting);
        ender.start();
        try {
            await(() -> ender.getState() == Thread.State.TIMED_WAITING || ender.getState() == Thread.State.TERMINATED,
                    10, "end() waits for the init");
        } finally {
            release.countDown();
            ender.join(TimeUnit.SECONDS.toMillis(10));
            holder.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertFalse(ender.isAlive(), "end() never returned");
        throwIfSet(failed.get());
        assertSame(interrupting, thrown.get());
        assertEquals(List.of(held.get()), handedBack.get());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> scope.getClosing("later", () -> new Value("later")));
        assertEquals(ENDED, refused.getMessage());
    }

    @Test
    @DisplayName("#1052: when the test kit fails to keep what failed before on what it throws, once every value is"
            + " closed, it still throws the first close() failure as it is")
    void testKitThrowsTheFirstFailureWhenKeepingTheRestFails() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        IOException failure = new IOException("a failed to close");
        context.runScopedClosing("a", () -> new Value("a", failure));
        Faults.inject(Faults.Step.TEST_RUN_FAILURE_KEPT, 1, new StackOverflowError("keeping the rest"));

        assertSame(failure, assertThrows(IOException.class, () -> EngineEvaluationContext.endRun(context)));
        assertEquals(List.of("a closed"), closed);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    @DisplayName("#1052: when keeping what failed before on what it throws fails with a fatal Error, once every value"
            + " is closed, the test kit throws that error in place of the first close() failure")
    void testKitThrowsAFatalErrorFromKeepingTheRest() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        context.runScopedClosing("a", () -> new Value("a", new IOException("a failed to close")));
        context.runScopedClosing("b", () -> new Value("b"));
        OutOfMemoryError keeping = new OutOfMemoryError("keeping the rest");
        Faults.inject(Faults.Step.TEST_RUN_FAILURE_KEPT, 1, keeping);

        assertSame(keeping, assertThrows(OutOfMemoryError.class, () -> EngineEvaluationContext.endRun(context)));
        assertEquals(List.of("b closed", "a closed"), closed);
        assertEquals(0, keeping.getSuppressed().length);
    }

    @Test
    @DisplayName("#1052: when keeping what failed before on what it throws fails with an Error that isn't fatal, once"
            + " every value is closed, the test kit still throws the first close() failure as it is")
    void testKitThrowsTheFirstFailureWhenKeepingTheRestFailsWithAnErrorThatIsNotFatal() {
        EvaluationContext context = new EngineEvaluationContext(Map.of(), Deadline.NONE);
        IOException failure = new IOException("a failed to close");
        context.runScopedClosing("a", () -> new Value("a", failure));
        Faults.inject(Faults.Step.TEST_RUN_FAILURE_KEPT, 1, new AssertionError("keeping the rest"));

        assertSame(failure, assertThrows(IOException.class, () -> EngineEvaluationContext.endRun(context)));
        assertEquals(List.of("a closed"), closed);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    @DisplayName("#1052: two threads ending a run at once hand its values back once, to one of them")
    void twoThreadsEndingTheRunHandTheValuesBackOnce() throws Exception {
        RunScope scope = new RunScope();
        Value kept = scope.getClosing("kept", () -> new Value("kept"));
        AtomicReference<List<AutoCloseable>> firstHandedBack = new AtomicReference<>();
        AtomicReference<List<AutoCloseable>> secondHandedBack = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<Thread> second = new AtomicReference<>();
        // The first stops as it takes the values, until the second has taken them too, or waits to.
        Thread first = daemon(() -> {
            Faults.watch(Faults.Step.RUN_SCOPE_VALUES_TAKING, () -> {
                Thread other = daemon(() -> secondHandedBack.set(scope.end()), failed);
                second.set(other);
                try {
                    await(() -> other.getState() == Thread.State.BLOCKED
                            || other.getState() == Thread.State.TERMINATED, 10, "the second end() waits or returns");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            });
            firstHandedBack.set(scope.end());
        }, failed);
        first.join(TimeUnit.SECONDS.toMillis(10));
        second.get().join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(first.isAlive(), "the first end() never returned");
        assertFalse(second.get().isAlive(), "the second end() never returned");
        throwIfSet(failed.get());
        assertEquals(List.of(kept), firstHandedBack.get());
        assertEquals(List.of(), secondHandedBack.get());
    }

    /** Ends the run of {@code context} as the test kit does, from an init, which can't throw a checked exception. */
    private static void endRun(EvaluationContext context) {
        try {
            EngineEvaluationContext.endRun(context);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Starts {@code body} on a daemon thread, keeping the first thing it throws in {@code failed}. */
    private static Thread daemon(Runnable body, AtomicReference<Throwable> failed) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failed.compareAndSet(null, e);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
