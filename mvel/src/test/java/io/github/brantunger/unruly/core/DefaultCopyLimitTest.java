package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A copy of the rules is expensive: MVEL recompiles every expression and generates accessor classes for it. A thread
 * pool bounds how many runs overlap, and so how many copies an engine makes; virtual threads don't, and a run for
 * each of ten thousand of them made ten thousand copies. So an engine limits <b>runs on virtual threads</b> to one
 * copy for each processor unless it's told otherwise, and leaves runs on platform threads alone.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("by default an engine limits the copies runs on virtual threads make, and no others")
class DefaultCopyLimitTest {

    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    /** More runs than the default limit, so the runs above it have to wait for a copy. */
    private static final int EXTRA_RUNS = 4;

    /** A fact that holds every run that reaches it, so a test can see how many are in progress at once. */
    public static final class Gate {

        private final CountDownLatch open = new CountDownLatch(1);
        private final AtomicInteger inProgress = new AtomicInteger();
        private final AtomicInteger mostInProgress = new AtomicInteger();

        /**
         * Waits until the gate opens.
         *
         * @return {@code true}, so a condition that calls this matches
         */
        public boolean enter() {
            mostInProgress.accumulateAndGet(inProgress.incrementAndGet(), Math::max);
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

    /**
     * A language that keeps no state between runs, so its rules need no copy at all, and whose condition holds every
     * run at {@code gate}.
     */
    private record StatelessGateLanguage(Gate gate) implements ExpressionLanguage {

        @Override
        public String name() {
            return "stateless";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> gate.enter();
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    private static final Rule RULE = Rule.builder().ruleName("gated").condition("gate.enter()")
            .action("output.put('ok', true)").build();

    private final List<Thread> threads = new ArrayList<>();

    @AfterEach
    void joinTheRuns() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    private static RulesEngine<Map<String, Object>> engine(UnaryOperator<RulesEngineBuilder<Map<String, Object>>>
                                                                   configuration, List<Rule> rules) {
        RulesEngine<Map<String, Object>> engine =
                configuration.apply(RulesEngineBuilder.allMatches(HashMap::new)).build();
        engine.load(rules);
        return engine;
    }

    private static FactStore<Object> facts(Gate gate) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("gate", gate);
        return facts;
    }

    /** Starts {@code count} runs, on virtual threads or platform ones, and remembers them to join afterwards. */
    private void start(int count, boolean virtual, RulesEngine<Map<String, Object>> engine, Gate gate) {
        for (int i = 0; i < count; i++) {
            Runnable run = () -> engine.run(facts(gate));
            Thread thread = virtual ? Thread.ofVirtual().unstarted(run) : Thread.ofPlatform().unstarted(run);
            threads.add(thread);
            thread.start();
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting until " + what);
            Thread.sleep(5);
        }
    }

    /** Whether every run has parked, either at the gate or waiting for a copy: both park with a timeout. */
    private boolean allParked() {
        return threads.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING);
    }

    @Test
    @DisplayName("runs on virtual threads are limited to one copy for each processor")
    void virtualThreadRunsAreLimited() throws InterruptedException {
        Gate gate = new Gate();
        RulesEngine<Map<String, Object>> engine = engine(UnaryOperator.identity(), List.of(RULE));

        start(PROCESSORS + EXTRA_RUNS, true, engine, gate);
        // The runs above the limit wait for a copy; the gate opens well inside the five seconds they give the copies
        // to come back before making an extra one.
        await(() -> gate.inProgress.get() == PROCESSORS && allParked(),
                PROCESSORS + " runs are in progress and " + EXTRA_RUNS + " wait for a copy");
        gate.open.countDown();
        joinTheRuns();

        assertEquals(PROCESSORS, gate.mostInProgress.get(), "no more runs than processors were ever in progress");
    }

    @Test
    @DisplayName("runs on platform threads are not limited: the pool they come from already bounds the copies")
    void platformThreadRunsAreNotLimited() throws InterruptedException {
        Gate gate = new Gate();
        RulesEngine<Map<String, Object>> engine = engine(UnaryOperator.identity(), List.of(RULE));

        start(PROCESSORS + EXTRA_RUNS, false, engine, gate);
        await(() -> gate.inProgress.get() == PROCESSORS + EXTRA_RUNS, "every run is in progress at once");
        gate.open.countDown();
        joinTheRuns();

        assertEquals(PROCESSORS + EXTRA_RUNS, gate.mostInProgress.get());
    }

    @Test
    @DisplayName("unlimitedCopies() turns the default off, so runs on virtual threads don't wait either")
    void unlimitedCopies() throws InterruptedException {
        Gate gate = new Gate();
        RulesEngine<Map<String, Object>> engine =
                engine(RulesEngineBuilder::unlimitedCopies, List.of(RULE));

        start(PROCESSORS + EXTRA_RUNS, true, engine, gate);
        await(() -> gate.inProgress.get() == PROCESSORS + EXTRA_RUNS, "every run is in progress at once");
        gate.open.countDown();
        joinTheRuns();

        assertEquals(PROCESSORS + EXTRA_RUNS, gate.mostInProgress.get());
    }

    @Test
    @DisplayName("maxCopies() limits runs on every kind of thread, not only virtual ones")
    void maxCopiesLimitsPlatformThreadsToo() throws InterruptedException {
        Gate gate = new Gate();
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.maxCopies(2), List.of(RULE));

        start(4, false, engine, gate);
        await(() -> gate.inProgress.get() == 2 && allParked(), "2 runs are in progress and 2 wait for a copy");
        gate.open.countDown();
        joinTheRuns();

        assertEquals(2, gate.mostInProgress.get());
    }

    @Test
    @DisplayName("rules whose languages keep no state between runs need no copies, so no limit applies to them")
    void rulesThatNeedNoCopiesAreNotLimited() throws InterruptedException {
        Gate gate = new Gate();
        StatelessGateLanguage language = new StatelessGateLanguage(gate);
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.language(language).maxCopies(1),
                List.of(Rule.builder().ruleName("gated").language("stateless").condition("c").action("a").build()));

        start(4, false, engine, gate);
        await(() -> gate.inProgress.get() == 4, "every run is in progress at once, although the limit is 1");
        gate.open.countDown();
        joinTheRuns();

        assertEquals(4, gate.mostInProgress.get(), "a session of Session.none() is shared, so nothing waits");
    }
}
