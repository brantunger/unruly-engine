package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine's own checks of a run's facts run before it waits for a copy of the rules, so a run whose facts the
 * engine rejects fails at once, however many copies are in use, rather than after waiting for one or instead of timing
 * out (#773). Listeners still hear of it: it gets {@code beforeRun}, then {@code onRunError}, without a copy. The
 * languages check the names once the run holds its copy, as before, so no compiler is closed while it checks one.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a run whose facts are rejected fails at once, without waiting for a copy of the rules")
class FactCheckBeforeCopyTest {

    /**
     * Longer than the rejected runs take, but far shorter than the five seconds a run waits for a copy before it makes
     * an extra one, so a run that waits stops at its deadline instead.
     */
    private static final RunOptions WAITING_TIMEOUT = RunOptions.withTimeoutOf(Duration.ofSeconds(1));

    /** What listeners heard, in order. */
    private final List<String> events = new CopyOnWriteArrayList<>();

    /** What listeners heard of the rejected run, before the holding run was released. */
    private List<String> heard = List.of();

    /** How many sessions the engine's language made: one for each copy of the rules. */
    private final AtomicInteger sessions = new AtomicInteger();

    private final CountDownLatch holding = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** Records every run callback, naming what a failed run failed with. */
    private final RuleListener recording = new RuleListener() {
        @Override
        public void beforeRun(RunContext run) {
            events.add("beforeRun");
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            events.add("afterRun");
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            events.add("onRunError " + error.getClass().getSimpleName()
                    + (error.getCause() == null ? "" : " caused by " + error.getCause().getClass().getSimpleName()));
        }
    };

    /**
     * An engine with a limit of one copy of the rules, whose language checks each fact name with {@code check} and has
     * a session per copy, and whose one rule holds its run until the test releases it.
     */
    private RulesEngine<Map<String, Object>> engineOfOneCopy(Consumer<String> check) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new StubExpressionLanguage().checkFactName(check).newSession(() -> {
                    sessions.incrementAndGet();
                    return new Session() {
                    };
                }).action((action, session) -> {
                    holding.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS), "the test never released the holding run");
                    return ActionResult.done();
                })).maxCopies(1).fact("n", Integer.class).listener(recording).build();
        engine.load(List.of(Rule.builder().ruleName("held").condition("c").action("a").build()));
        return engine;
    }

    /**
     * Runs {@code rejected} on the test's thread while a run on another thread holds the engine's only copy of the
     * rules, and returns what it threw. What listeners heard of it is left in {@link #heard}.
     */
    private Throwable failureWhileTheOnlyCopyIsHeld(RulesEngine<Map<String, Object>> engine, FactMap<Object> rejected)
            throws InterruptedException {
        Thread holder = new Thread(() -> engine.run(new FactMap<>()), "holder");
        holder.start();
        try {
            assertTrue(holding.await(20, TimeUnit.SECONDS), "the run holding the only copy never started");
            int made = sessions.get();
            events.clear();

            Throwable thrown = assertThrows(Throwable.class, () -> engine.runWithResult(rejected, WAITING_TIMEOUT));

            heard = List.copyOf(events);
            assertEquals(made, sessions.get(), "the rejected run made a copy of the rules");
            return thrown;
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(20));
        }
    }

    @Test
    @DisplayName("a declared fact of the wrong type is rejected at once while every copy is in use, and listeners"
            + " hear of the run")
    void aWrongTypeIsRejectedWithoutWaiting() throws InterruptedException {
        try (RulesEngine<Map<String, Object>> engine = engineOfOneCopy(name -> {
        })) {
            Throwable thrown = failureWhileTheOnlyCopyIsHeld(engine, new FactMap<>(new Fact<>("n", "not a number")));

            IllegalArgumentException rejection = assertInstanceOf(IllegalArgumentException.class, thrown);
            assertEquals("Fact 'n' was declared as java.lang.Integer, but the run supplied a java.lang.String",
                    rejection.getMessage());
            assertEquals(List.of("beforeRun", "onRunError IllegalArgumentException"), heard);
        }
    }

    @Test
    @DisplayName("a fact named output is rejected at once while every copy is in use")
    void theOutputsNameIsRejectedWithoutWaiting() throws InterruptedException {
        try (RulesEngine<Map<String, Object>> engine = engineOfOneCopy(name -> {
        })) {
            Throwable thrown = failureWhileTheOnlyCopyIsHeld(engine, new FactMap<>(new Fact<>("output", 1)));

            IllegalArgumentException rejection = assertInstanceOf(IllegalArgumentException.class, thrown);
            assertEquals("'output' is reserved for the output object and cannot be used as a fact name",
                    rejection.getMessage());
            assertEquals(List.of("beforeRun", "onRunError IllegalArgumentException"), heard);
        }
    }

    @Test
    @DisplayName("a fatal Error from a language's check of a fact name is rethrown unchanged, and reaches onRunError")
    void aFatalErrorFromTheLanguagesCheckIsRethrown() {
        // A named guard: the language checks the name inside the run's scope, once the run holds its copy.
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        try (RulesEngine<Map<String, Object>> engine = engineOfOneCopy(name -> {
            if ("bad".equals(name)) {
                throw oom;
            }
        })) {
            events.clear();

            assertSame(oom, assertThrows(OutOfMemoryError.class,
                    () -> engine.run(new FactMap<>(new Fact<>("bad", 1)))));

            assertEquals(List.of("beforeRun", "onRunError RuleExecutionException caused by OutOfMemoryError"),
                    events);
        }
    }

    @Test
    @DisplayName("a run a language's check of a fact name starts names the run checking it as its parent")
    void aRunTheLanguagesCheckStartsHasTheRunAsItsParent() {
        // A named guard: the language checks the name inside the run's scope, where the run is the thread's run.
        AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();
        List<Long> parents = new CopyOnWriteArrayList<>();
        List<Long> runs = new CopyOnWriteArrayList<>();
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new StubExpressionLanguage().checkFactName(name -> {
                    if ("outer".equals(name)) {
                        self.get().run(new FactMap<>(new Fact<>("inner", 1)));
                    }
                })).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        runs.add(run.runId());
                        parents.add(run.parent() == null ? 0 : run.parent().runId());
                    }
                }).build()) {
            self.set(engine);
            engine.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()));

            engine.run(new FactMap<>(new Fact<>("outer", 1)));

            assertEquals(List.of(0L, runs.get(0)), parents, "the parents of the outer run and the one it started");
        }
    }

    @Test
    @DisplayName("a run that reads the rules again because the list it read was closed is checked against the list it"
            + " runs")
    void aRunMovedToAnotherListIsCheckedAgainstIt() {
        // A named guard: the languages check the names against the list the run uses, once it holds a copy, never
        // with a compiler of a list that was closed before the run could borrow from it. The closed list's language
        // records every name it's asked about, and the engine's own rejects 'a'.
        List<String> askedOfClosed = new CopyOnWriteArrayList<>();
        RuleSet closedRules = new RuleSet(List.of(), Map.of("x", StubExpressionLanguage.named("x")
                .checkFactName(askedOfClosed::add).newCompiler(null)), CopyLimit.none(),
                new CopyPermits(RuleSet.UNLIMITED));
        closedRules.retire();
        IllegalArgumentException rejection = new IllegalArgumentException("'a' is not allowed");
        AtomicInteger reads = new AtomicInteger();
        EngineConfiguration<String> configuration = new EngineConfiguration<>(
                Map.of("x", StubExpressionLanguage.named("x").checkFactName(name -> {
                    throw rejection;
                })),
                null, List.of(), List.of(recording), CopyLimit.none(), 0, null, Clock.systemUTC(), Object.class,
                OutputWriter.beansAndMaps(), Map.of(), Map.of(), false, Map.of());
        AbstractRulesEngine<String> engine = new AbstractRulesEngine<>(String::new, configuration) {
            @Override
            RuleSet currentRules() {
                return reads.getAndIncrement() == 0 ? closedRules : super.currentRules();
            }

            @Override
            RunResult<String> runRules(FactStore<?> facts, Duration timeout, Set<String> tags) {
                return runInScope(facts, timeout, tags, (rules, copy, runFacts) ->
                        RunResult.of("ran", List.of(), rules.checksum()));
            }

            @Override
            String matchPolicy() {
                return "firstMatch";
            }
        };
        engine.load(List.of(Rule.builder().ruleName("r").language("x").condition("c").action("a").build()));
        reads.set(0);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("a", 1))));

        assertSame(rejection, thrown);
        assertEquals(2, reads.get(), "the run didn't read the rules again");
        assertEquals(List.of(), askedOfClosed, "names the closed list's compiler was asked to check");
        assertEquals(List.of("beforeRun", "onRunError IllegalArgumentException"), events);
    }

    @Test
    @DisplayName("a run's facts are checked once, whether they pass or not")
    void theFactsAreCheckedOnce() {
        AtomicInteger checks = new AtomicInteger();
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new StubExpressionLanguage().checkFactName(name -> checks.incrementAndGet()))
                .build()) {
            engine.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()));

            engine.run(new FactMap<>(new Fact<>("a", 1)));

            assertEquals(1, checks.get(), "how many times the run checked its one fact's name");
        }
    }
}
