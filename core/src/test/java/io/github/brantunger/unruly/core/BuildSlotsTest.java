package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An engine built with {@code unlimitedCopies()} makes a copy for each run that finds every copy in use. A copy is
 * cheap to make but slow the first time it runs, because the language compiles its expressions then, and on JDK 24 and
 * later the virtual threads waiting on the classes that loads give up their carriers: 100,000 virtual threads made
 * 100,000 copies of 21 MVEL rules (#421). So a run on a virtual thread that no limit covers waits for one of the
 * engine's build slots, one for each processor, before it makes a copy, and holds the slot until that copy's first run
 * ends; a copy given back meanwhile goes to a run that is waiting (#424). Runs on platform threads are left alone: a
 * thread pool's size bounds the copies.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("without a limit, runs on virtual threads make at most one new copy for each processor at once")
class BuildSlotsTest {

    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    /** More runs than there are build slots, so the runs above them have to wait for one. */
    private static final int EXTRA_RUNS = 4;

    /** A fact that holds every run that reaches it, so a test can see how many are in progress at once. */
    private static final class Gate {

        private final CountDownLatch open = new CountDownLatch(1);
        private final AtomicInteger inProgress = new AtomicInteger();

        boolean enter() {
            inProgress.incrementAndGet();
            try {
                assertTrue(open.await(30, TimeUnit.SECONDS), "the gate never opened");
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                inProgress.decrementAndGet();
            }
        }
    }

    /** A session that belongs to one copy, as a language's that keeps state between runs does. */
    private static final class CopySession implements Session {
    }

    /**
     * A language whose condition calls the run's {@code step} fact, and which counts the sessions, one for each copy,
     * it creates. With {@code stateless}, it keeps no state between runs; with {@code failing}, it can't create one.
     */
    private record StepLanguage(AtomicInteger sessions, boolean stateless, boolean failing)
            implements ExpressionLanguage {

        StepLanguage() {
            this(new AtomicInteger(), false, false);
        }

        @Override
        public String name() {
            return "step";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> ((BooleanSupplier) evaluation.facts().get("step")).getAsBoolean();
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    if (failing) {
                        throw new IllegalStateException("no session today");
                    }
                    sessions.incrementAndGet();
                    return stateless ? Session.none() : new CopySession();
                }
            };
        }
    }

    private static final Rule RULE = Rule.builder().ruleName("stepped").condition("step").action("done").build();

    private final List<Thread> threads = new ArrayList<>();
    private final Gate gate = new Gate();

    @AfterEach
    void joinTheRuns() throws InterruptedException {
        gate.open.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    private static RulesEngine<Map<String, Object>> unlimited(StepLanguage language) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).unlimitedCopies().build();
        engine.load(List.of(RULE));
        return engine;
    }

    private static FactStore<Object> facts(BooleanSupplier step) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("step", step);
        return facts;
    }

    /** Starts a run on a thread of its own, remembered to join afterwards. */
    private Thread start(boolean virtual, Runnable run) {
        Thread thread = virtual ? Thread.ofVirtual().unstarted(run) : Thread.ofPlatform().daemon().unstarted(run);
        threads.add(thread);
        thread.start();
        return thread;
    }

    /** Starts {@code count} runs that each wait at the gate. */
    private void startGated(int count, boolean virtual, RulesEngine<Map<String, Object>> engine) {
        for (int i = 0; i < count; i++) {
            start(virtual, () -> engine.run(facts(gate::enter)));
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        await(condition, 20, what);
    }

    private static void await(BooleanSupplier condition, int seconds, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting until " + what);
            Thread.sleep(5);
        }
    }

    /** Whether every run has parked, either at the gate or waiting for a slot: both park with a timeout. */
    private boolean allParked() {
        return threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING);
    }

    /** Fills every build slot with a run waiting at the gate. */
    private void fillTheSlots(RulesEngine<Map<String, Object>> engine) throws InterruptedException {
        startGated(PROCESSORS, true, engine);
        await(() -> gate.inProgress.get() == PROCESSORS && allParked(), "a run holds every slot, at the gate");
    }

    @Test
    @DisplayName("runs above one for each processor wait for a slot, and the first to get one takes a copy given back")
    void newCopiesAreBoundedByTheSlots() throws InterruptedException {
        StepLanguage language = new StepLanguage();
        RulesEngine<Map<String, Object>> engine = unlimited(language);

        startGated(PROCESSORS + EXTRA_RUNS, true, engine);
        // The runs above the slots wait for one; the gate opens well inside the five seconds they give the slots to
        // come back before making a copy without one.
        await(() -> gate.inProgress.get() == PROCESSORS && allParked(),
                PROCESSORS + " runs are in their first run and " + EXTRA_RUNS + " wait for a slot");
        assertEquals(PROCESSORS, language.sessions().get(), "one copy for each slot");
        gate.open.countDown();
        joinTheRuns();

        // A first run keeps its copy before it gives its slot back, so the first run that waited finds that copy. How
        // many of the others make a copy depends on timing: a run that took an idle copy gives its slot straight back,
        // and the next run to get it may find no copy idle yet. Each new copy still has a slot for its first run.
        int copies = language.sessions().get();
        assertTrue(copies < PROCESSORS + EXTRA_RUNS, copies + " copies: every run that waited made its own");

        start(true, () -> engine.run(facts(() -> true))).join(TimeUnit.SECONDS.toMillis(10));
        assertEquals(copies, language.sessions().get(), "a later run took an idle copy");
    }

    @Test
    @DisplayName("runs on platform threads don't wait for a slot: a thread pool's size bounds the copies")
    void platformThreadsDontWait() throws InterruptedException {
        StepLanguage language = new StepLanguage();
        RulesEngine<Map<String, Object>> engine = unlimited(language);

        startGated(PROCESSORS + EXTRA_RUNS, false, engine);
        await(() -> gate.inProgress.get() == PROCESSORS + EXTRA_RUNS, "every run is in progress at once");

        assertEquals(PROCESSORS + EXTRA_RUNS, language.sessions().get());
    }

    @Test
    @DisplayName("a run nested on the same thread makes its copy without waiting for a slot its own thread may hold")
    void nestedRunDoesntWait() throws InterruptedException {
        StepLanguage language = new StepLanguage();
        RulesEngine<Map<String, Object>> engine = unlimited(language);
        startGated(PROCESSORS - 1, true, engine);
        await(() -> gate.inProgress.get() == PROCESSORS - 1 && allParked(), "a run holds all slots but one");
        CountDownLatch nestedDone = new CountDownLatch(1);

        // This run takes the last slot, and its condition runs the engine again on the same thread.
        start(true, () -> engine.run(facts(() -> {
            engine.run(facts(() -> true));
            nestedDone.countDown();
            return gate.enter();
        })));

        assertTrue(nestedDone.await(3, TimeUnit.SECONDS), "the nested run waited for a slot");
        assertEquals(PROCESSORS + 1, language.sessions().get(), "the nested run made a copy of its own");
    }

    @Test
    @DisplayName("a run with a deadline waits for a slot for at most half the time it has left, then makes its copy")
    void deadlineWaitsHalfItsTime() throws InterruptedException {
        StepLanguage language = new StepLanguage();
        RulesEngine<Map<String, Object>> engine = unlimited(language);
        fillTheSlots(engine);
        AtomicReference<Object> outcome = new AtomicReference<>();
        long[] took = new long[1];

        Thread run = start(true, () -> {
            long start = System.nanoTime();
            try {
                outcome.set(engine.runWithResult(facts(() -> true), RunOptions.withTimeoutOf(Duration.ofSeconds(2)))
                        .firedRules().stream().map(Rule::getRuleName).toList());
            } catch (RuntimeException e) {
                outcome.set(e);
            }
            took[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        });
        run.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(List.of("stepped"), outcome.get(), "the run kept the other half of its time for its rules");
        assertTrue(took[0] >= 900, "the run waited " + took[0] + " ms for a slot, not about a second");
        assertEquals(PROCESSORS + 1, language.sessions().get());
    }

    @Test
    @DisplayName("a run with a timeout too long to count in nanoseconds waits for a slot as a run without one does")
    void hugeTimeoutWaitsForASlot() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = unlimited(new StepLanguage());
        fillTheSlots(engine);
        AtomicReference<Object> outcome = new AtomicReference<>();
        // Its deadline is Instant.MAX, and no copy is idle, so the run waits for a slot.
        RunOptions noRealLimit = RunOptions.withTimeoutOf(Duration.ofSeconds(Long.MAX_VALUE));

        Thread run = start(true, () -> {
            try {
                outcome.set(engine.runWithResult(facts(() -> true), noRealLimit)
                        .firedRules().stream().map(Rule::getRuleName).toList());
            } catch (RuntimeException e) {
                outcome.set(e);
            }
        });
        await(() -> run.getState() == Thread.State.TIMED_WAITING || !run.isAlive(), "the run waits for a slot");
        gate.open.countDown();
        run.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(List.of("stepped"), outcome.get());
    }

    @Test
    @DisplayName("a run interrupted while it waits for a slot fails, saying what it was waiting for")
    void interruptedWhileWaiting() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = unlimited(new StepLanguage());
        fillTheSlots(engine);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        Thread run = start(true, () -> {
            try {
                engine.run(facts(() -> true));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        await(() -> run.getState() == Thread.State.TIMED_WAITING, "the run waits for a slot");
        run.interrupt();
        run.join(TimeUnit.SECONDS.toMillis(10));

        RuntimeException thrown = failure.get();
        assertInstanceOf(RuleExecutionException.class, thrown);
        assertEquals("run() was interrupted while waiting to make a compiled copy of the rules: every build slot was"
                + " in use", thrown.getMessage());
        assertInstanceOf(InterruptedException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a thread already interrupted doesn't wait for a slot: it makes its copy, and its run stops")
    void alreadyInterruptedDoesntWait() throws InterruptedException {
        StepLanguage language = new StepLanguage();
        RulesEngine<Map<String, Object>> engine = unlimited(language);
        fillTheSlots(engine);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        long[] took = new long[1];

        Thread run = start(true, () -> {
            long start = System.nanoTime();
            Thread.currentThread().interrupt();
            try {
                engine.run(facts(() -> true));
            } catch (RuntimeException e) {
                failure.set(e);
            }
            took[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        });
        run.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue(took[0] < 3000, "the run waited " + took[0] + " ms");
        assertNotNull(failure.get(), "the interrupted run stopped");
        assertFalse(failure.get().getMessage().contains("build slot"), failure.get().getMessage());
        assertEquals(PROCESSORS + 1, language.sessions().get());
    }

    @Test
    @DisplayName("rules that need no copies give their slot back at once, and every later run shares one set")
    void statelessRulesGiveTheSlotBack() throws InterruptedException {
        StepLanguage language = new StepLanguage(new AtomicInteger(), true, false);
        RulesEngine<Map<String, Object>> engine = unlimited(language);

        startGated(PROCESSORS + EXTRA_RUNS, true, engine);

        // Inside the five seconds a run waiting for a slot that never came back would wait before going on without.
        await(() -> gate.inProgress.get() == PROCESSORS + EXTRA_RUNS, 3, "every run is in progress at once");
    }

    @Test
    @DisplayName("a run whose language fails to make a copy gives its slot back")
    void failedCopyGivesTheSlotBack() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = unlimited(new StepLanguage(new AtomicInteger(), false, true));
        AtomicInteger failures = new AtomicInteger();
        long[] took = new long[1];

        // More runs, one after another, than there are slots: a slot kept by a failed run would leave the last ones
        // waiting five seconds for it.
        Thread runs = start(true, () -> {
            long start = System.nanoTime();
            for (int i = 0; i < PROCESSORS + EXTRA_RUNS; i++) {
                try {
                    engine.run(facts(() -> true));
                } catch (RuleExecutionException e) {
                    failures.incrementAndGet();
                }
            }
            took[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        });
        runs.join(TimeUnit.SECONDS.toMillis(30));

        assertEquals(PROCESSORS + EXTRA_RUNS, failures.get());
        assertTrue(took[0] < 4000, "the runs took " + took[0] + " ms");
    }
}
