package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Without a limit these tests either fail an assertion or time out: runs never wait, so no thread reaches
 * {@code WAITING} and every overlapping run makes its own copy.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("an engine with a limit on compiled copies keeps at most that many, and a run waits for a free one")
class CompiledCopyLimitTest {

    /**
     * A language whose condition waits for {@link #gate} to open, and which counts the sessions it makes, each a copy of
     * its one-rule list, and the runs in progress.
     */
    private static final class GateLanguage implements ExpressionLanguage {

        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicInteger inProgress = new AtomicInteger();
        private final AtomicInteger mostInProgress = new AtomicInteger();
        // Every session made, in order, including those that failed.
        private final AtomicInteger sessionsMade = new AtomicInteger();
        private final List<GateSession> sessions = new CopyOnWriteArrayList<>();
        // How many of the next newSession() calls throw.
        private final AtomicInteger failingSessions = new AtomicInteger();

        @Override
        public String name() {
            return "gate";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return new Condition();
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (actionContext, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    sessionsMade.incrementAndGet();
                    if (failingSessions.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        throw new IllegalStateException("session failed");
                    }
                    GateSession session = new GateSession();
                    sessions.add(session);
                    return session;
                }
            };
        }

        private final class Condition implements CompiledCondition {
            @Override
            public Object evaluate(EvaluationContext context, Session session) {
                mostInProgress.accumulateAndGet(inProgress.incrementAndGet(), Math::max);
                try {
                    assertTrue(gate.await(20, TimeUnit.SECONDS), "the gate never opened");
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    inProgress.decrementAndGet();
                }
            }
        }
    }

    /** A session that records whether the engine closed it. */
    private static final class GateSession implements Session {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final Rule RULE = Rule.builder().ruleName("r").language("gate").condition("c").action("a").build();

    private static RulesEngine<Map<String, Object>> engine(GateLanguage language, int maxCopies,
                                                           RuleListener... listeners) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language)
                .maxCopies(maxCopies)
                .listeners(List.of(listeners))
                .build();
        engine.load(List.of(RULE));
        return engine;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting until " + what);
            Thread.sleep(5);
        }
    }

    /**
     * Whether every one of {@code threads} is parked. A run waiting for a copy and a run held by the gate both park
     * with a timeout, so the two can't be told apart by their state: with two runs in progress, the other four are
     * waiting for a copy.
     */
    private static boolean allParked(List<Thread> threads) {
        return threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING);
    }

    /**
     * A listener that runs the engine once more from inside each run, on the same thread, while the outer run holds its
     * copy. The engine is set once it's built.
     */
    private static RuleListener runNestedOnce(AtomicReference<RulesEngine<Map<String, Object>>> engine,
                                              List<Object> nestedOutputs) {
        AtomicBoolean nesting = new AtomicBoolean();
        return new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                if (nesting.compareAndSet(false, true)) {
                    try {
                        nestedOutputs.add(engine.get().run(new FactMap<>()));
                    } finally {
                        nesting.set(false);
                    }
                }
            }
        };
    }

    @Test
    @DisplayName("with a limit of 2, six overlapping runs use 2 copies, and the other four wait for them")
    void overlappingRunsWaitForACopy() throws InterruptedException {
        GateLanguage language = new GateLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 2);
        List<Object> outputs = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Thread thread = new Thread(() -> outputs.add(engine.run(new FactMap<>())));
            threads.add(thread);
            thread.start();
        }

        // The gate opens as soon as the four runs are waiting, well within the five seconds a waiting run gives the
        // copies to come back before it makes an extra one instead.
        // The gate opens as soon as the four runs are waiting, well within the five seconds a waiting run gives
        // the copies to come back before it makes an extra one instead.
        await(() -> language.inProgress.get() == 2 && allParked(threads), "2 runs are in progress and 4 wait");
        assertEquals(2, language.sessionsMade.get(), "sessions made while four runs wait");
        language.gate.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(6, outputs.size(), "every run finished");
        assertEquals(2, language.mostInProgress.get(), "most runs in progress at once");
        assertEquals(2, language.sessionsMade.get(), "the waiting runs reused the two sessions");
    }

    @Test
    @DisplayName("a run nested in another on the same thread doesn't wait for the only copy, and its extra copy isn't kept")
    void nestedRunGetsAnExtraCopy() {
        GateLanguage language = new GateLanguage();
        language.gate.countDown();
        AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();
        List<Object> nestedOutputs = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = engine(language, 1, runNestedOnce(self, nestedOutputs));
        self.set(engine);

        assertEquals(Map.of(), engine.run(new FactMap<>()));
        assertEquals(Map.of(), engine.run(new FactMap<>()));

        assertEquals(List.of(Map.of(), Map.of()), nestedOutputs);
        assertEquals(3, language.sessions.size(), "one kept session, plus an extra session for each nested run");
        assertFalse(language.sessions.get(0).closed.get(), "the kept copy's session stays open");
        assertTrue(language.sessions.get(1).closed.get(), "the first nested run's session is closed when it returns");
        assertTrue(language.sessions.get(2).closed.get(), "the second nested run's session is closed when it returns");
    }

    @Test
    @DisplayName("a nested run uses a free copy when there is one, and keeps it")
    void nestedRunUsesAFreeCopy() {
        GateLanguage language = new GateLanguage();
        language.gate.countDown();
        AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();
        List<Object> nestedOutputs = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = engine(language, 2, runNestedOnce(self, nestedOutputs));
        self.set(engine);

        engine.run(new FactMap<>());
        engine.run(new FactMap<>());

        assertEquals(2, nestedOutputs.size());
        assertEquals(2, language.sessionsMade.get(), "both copies are kept and reused");
        assertTrue(language.sessions.stream().noneMatch(session -> session.closed.get()), "no kept session is closed");
    }

    @Test
    @DisplayName("an interrupt while waiting fails the run, keeps the interrupt status and is logged, and takes no copy")
    void interruptWhileWaiting() throws InterruptedException {
        GateLanguage language = new GateLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language, 1);
        Thread holder = new Thread(() -> engine.run(new FactMap<>()));
        holder.start();
        await(() -> language.inProgress.get() == 1, "the first run holds the only copy");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptStatus = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                engine.run(new FactMap<>());
            } catch (RuntimeException e) {
                thrown.set(e);
                interruptStatus.set(Thread.currentThread().isInterrupted());
            }
        });

        String logs = logsOf(() -> {
            try {
                waiter.start();
                await(() -> waiter.getState() == Thread.State.TIMED_WAITING, "the second run waits for a copy");
                waiter.interrupt();
                waiter.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        language.gate.countDown();
        holder.join();

        RuleExecutionException ex = assertInstanceOf(RuleExecutionException.class, thrown.get());
        assertEquals("run() was interrupted while waiting for a compiled copy of the rules: all 1 were in use",
                ex.getMessage());
        assertInstanceOf(InterruptedException.class, ex.getCause());
        assertTrue(interruptStatus.get(), "the thread's interrupt status is set after run() throws");
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + ex.getMessage()), logs);
        assertEquals(Map.of(), engine.run(new FactMap<>()), "a later run gets the copy");
        assertEquals(1, language.sessionsMade.get());
    }

    @Test
    @DisplayName("a copy that fails to be made doesn't count toward the limit")
    void failedCopyFreesItsPlace() throws InterruptedException {
        GateLanguage language = new GateLanguage();
        language.gate.countDown();
        language.failingSessions.set(1);
        RulesEngine<Map<String, Object>> engine = engine(language, 1);

        String logs = logsOf(() -> assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>())));
        AtomicReference<Object> otherThreadOutput = new AtomicReference<>();
        Thread otherThread = new Thread(() -> otherThreadOutput.set(engine.run(new FactMap<>())));
        otherThread.setDaemon(true);
        otherThread.start();
        otherThread.join(TimeUnit.SECONDS.toMillis(10));
        for (int i = 0; i < 3; i++) {
            assertEquals(Map.of(), engine.run(new FactMap<>()), "run " + i + " on the thread whose copy failed");
        }

        assertTrue(logs.contains("The 'gate' expression language failed to create a session"), logs);
        assertTrue(logs.contains("session failed"), logs);
        assertEquals(Map.of(), otherThreadOutput.get(), "a run on another thread didn't get a copy");
        assertEquals(2, language.sessionsMade.get(), "the failed session, then one kept session that every later run reuses");
    }

    @Test
    @DisplayName("a run that throws gives its copy back, so the next run doesn't wait for it")
    void failedRunGivesBackItsCopy() {
        GateLanguage language = new GateLanguage();
        language.gate.countDown();
        RulesEngine<Map<String, Object>> engine = engine(language, 1);
        FactStore<Object> invalid = new FactMap<>();
        invalid.setValue("output", 1);

        String logs = logsOf(() -> assertThrows(IllegalArgumentException.class, () -> engine.run(invalid)));

        assertTrue(logs.contains("'output'"), logs);
        assertEquals(Map.of(), engine.run(new FactMap<>()));
        assertEquals(1, language.sessionsMade.get());
    }

    @Test
    @DisplayName("maxCopies rejects a limit below 1, and firstMatch and allMatches reject a null supplier")
    void limitBelowOneRejected() {
        Supplier<Map<String, Object>> output = HashMap::new;

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> RulesEngineBuilder.firstMatch(output).maxCopies(0));
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> RulesEngineBuilder.allMatches(output).maxCopies(-1));

        assertEquals("maxCopies must be at least 1, but was 0", zero.getMessage());
        assertEquals("maxCopies must be at least 1, but was -1", negative.getMessage());
        assertThrows(NullPointerException.class, () -> RulesEngineBuilder.firstMatch(null));
        assertThrows(NullPointerException.class, () -> RulesEngineBuilder.allMatches(null));
    }

    @Test
    @DisplayName("firstMatch and allMatches with maxCopies build engines of the right kind")
    void builderMethodsBuildTheRightEngine() {
        List<Rule> rules = List.of(
                Rule.builder().ruleName("high").priority(2).condition("true").action("output.put('high', true)").build(),
                Rule.builder().ruleName("low").priority(1).condition("true").action("output.put('low', true)").build());
        RulesEngine<Map<String, Object>> firstMatch = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .maxCopies(1).build();
        RulesEngine<Map<String, Object>> allMatches = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .maxCopies(1).build();
        firstMatch.load(rules);
        allMatches.load(rules);

        assertInstanceOf(StatelessRulesEngine.class, firstMatch);
        assertInstanceOf(StatefulRulesEngine.class, allMatches);
        assertEquals(Map.of("high", true), firstMatch.run(new FactMap<>()));
        assertEquals(Map.of("high", true, "low", true), allMatches.run(new FactMap<>()));
    }
}
