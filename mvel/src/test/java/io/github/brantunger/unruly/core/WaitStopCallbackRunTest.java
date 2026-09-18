package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run that stops while it waits for a compiled copy of the rules still opens a scope for its listeners: a run
 * started from that run's {@code beforeRun} or {@code onRunError} inherits its deadline, which has passed, and sees it
 * as its {@code parent()} on the same engine.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("the callbacks of a run stopped while waiting carry the run's deadline and identity")
class WaitStopCallbackRunTest {

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

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /** An engine whose one rule takes as long as the test lets it. */
    private static RulesEngine<Map<String, Object>> slowEngine() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(rule("slow", "pause.longer()", "output.put('x', 1)")));
        return engine;
    }

    private static FactStore<Object> pauseFacts() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("pause", new Pause());
        return facts;
    }

    /**
     * Runs {@code listener}'s engine twice: one run holds the only copy, the other waits and stops at its deadline.
     * The waiting run's exception is returned.
     */
    private static RuleExecutionException stopWhileWaiting(RuleListener listener) throws Exception {
        return stopWhileWaiting(listener, new AtomicReference<>());
    }

    private static RuleExecutionException stopWhileWaiting(RuleListener listener,
                                                           AtomicReference<RulesEngine<Map<String, Object>>> self)
            throws Exception {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .maxCopies(1).runTimeout(SHORT).listener(listener).build();
        self.set(engine);
        engine.load(List.of(rule("hold", "true", "output.put('held', gate.hold())")));
        Gate gate = new Gate();
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("gate", gate);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> engine.run(facts));
            assertTrue(gate.entered.await(30, TimeUnit.SECONDS), "the holder never took the copy");

            Future<?> waiter = threads.submit(() -> engine.run(new FactMap<>()));
            RuleExecutionException stop = assertInstanceOf(RuleExecutionException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> waiter.get(30, TimeUnit.SECONDS)).getCause());
            assertTrue(stop.getMessage().contains("while waiting for a compiled copy"), stop.getMessage());
            gate.release.countDown();
            try {
                holder.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                // The holder's action ran past its own deadline too.
            }
            return stop;
        } finally {
            gate.release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("a run started from beforeRun stops at the waiting run's deadline")
    void runFromBeforeRun() throws Exception {
        RulesEngine<Map<String, Object>> other = slowEngine();
        AtomicReference<Throwable> nested = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();

        stopWhileWaiting(new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                // The waiting run is the one with no facts; the holder's has the gate.
                if (run.facts().isEmpty() && once.compareAndSet(false, true)) {
                    nested.set(assertThrows(RuleExecutionException.class, () -> other.run(pauseFacts())));
                }
            }
        });

        RuleExecutionException thrown = assertInstanceOf(RuleExecutionException.class, nested.get(),
                "the run started from beforeRun didn't stop");
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a run started from onRunError stops at the waiting run's deadline")
    void runFromOnRunError() throws Exception {
        RulesEngine<Map<String, Object>> other = slowEngine();
        AtomicReference<Throwable> nested = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();

        stopWhileWaiting(new RuleListener() {
            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                if (run.facts().isEmpty() && once.compareAndSet(false, true)) {
                    nested.set(assertThrows(RuleExecutionException.class, () -> other.run(pauseFacts())));
                }
            }
        });

        RuleExecutionException thrown = assertInstanceOf(RuleExecutionException.class, nested.get(),
                "the run started from onRunError didn't stop");
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    @DisplayName("an interrupted wait still hands its deadline to a run started from its callbacks")
    void interruptedWaitKeepsTheDeadline() throws Exception {
        RulesEngine<Map<String, Object>> other = slowEngine();
        AtomicReference<Throwable> nested = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        stopWhileWaitingInterrupted(new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                if (run.facts().isEmpty() && once.compareAndSet(false, true)) {
                    // A listener that swallows the interrupt, as blocking code often does, leaves only the deadline.
                    Thread.interrupted();
                    try {
                        other.run(pauseFacts());
                        nested.set(new IllegalStateException("the nested run returned normally"));
                    } catch (RuntimeException e) {
                        nested.set(e);
                    } finally {
                        done.countDown();
                    }
                }
            }
        }, done);

        RuleExecutionException thrown = assertInstanceOf(RuleExecutionException.class, nested.get(),
                "the run started from beforeRun didn't stop: " + nested.get());
        assertInstanceOf(TimeoutException.class, thrown.getCause(), String.valueOf(thrown.getCause()));
    }

    /** Like {@link #stopWhileWaiting}, but the waiting run is interrupted instead of passing its deadline. */
    private static void stopWhileWaitingInterrupted(RuleListener listener, CountDownLatch done) throws Exception {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .maxCopies(1).runTimeout(SHORT).listener(listener).build();
        engine.load(List.of(rule("hold", "true", "output.put('held', gate.hold())")));
        Gate gate = new Gate();
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("gate", gate);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> engine.run(facts));
            assertTrue(gate.entered.await(30, TimeUnit.SECONDS), "the holder never took the copy");

            Future<?> waiter = threads.submit(() -> engine.run(new FactMap<>()));
            Thread.sleep(50);
            waiter.cancel(true);
            assertTrue(done.await(30, TimeUnit.SECONDS), "the waiting run's beforeRun started no run");
            gate.release.countDown();
            try {
                holder.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                // The holder's action ran past its own deadline too.
            }
        } finally {
            gate.release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("each run started from those callbacks on the same engine names the waiting run as its parent")
    void nestedRunsOnTheSameEngineHaveAParent() throws Exception {
        AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();
        AtomicReference<RunContext> waiting = new AtomicReference<>();
        List<RunContext> nested = new CopyOnWriteArrayList<>();
        AtomicBoolean once = new AtomicBoolean();

        stopWhileWaiting(new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                if (run.facts().isEmpty() && once.compareAndSet(false, true)) {
                    waiting.set(run);
                    // Both stop while waiting too, at the deadline they inherit, which has already passed. The
                    // second shows that the first put the waiting run back as the run the thread is inside, rather
                    // than leaving the thread with no run at all.
                    assertThrows(RuleExecutionException.class, () -> self.get().run(new FactMap<>()));
                    assertThrows(RuleExecutionException.class, () -> self.get().run(new FactMap<>()));
                } else if (run.facts().isEmpty()) {
                    nested.add(run);
                }
            }
        }, self);

        assertNotNull(waiting.get(), "the waiting run's beforeRun didn't arrive");
        assertEquals(2, nested.size(), "the runs started from beforeRun sent no beforeRun of their own");
        assertTrue(nested.stream().allMatch(run -> run.parent() == waiting.get()),
                "each run started from beforeRun has the waiting run as its parent");
    }
}
