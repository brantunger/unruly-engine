package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #420: a run's context and its result say which tags and what time chose the rules it used, so a listener or an
 * audit record can tell why a rule was skipped.
 */
@DisplayName("a run's context and result carry the tags and the start time that chose its rules")
class RunTagsAndStartTest {

    private static final Instant NOW = Instant.parse("2027-06-01T00:00:00Z");
    private static final Clock AT_NOW = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RunOptions RETAIL_EU = RunOptions.defaults().withTags(Set.of("retail", "eu"));

    /** What a listener heard of each run, in the order the callbacks came. */
    private static final class Runs implements RuleListener {

        private final List<RunContext> started = new CopyOnWriteArrayList<>();
        private final List<RunContext> finished = new CopyOnWriteArrayList<>();
        private final List<RunResult<?>> results = new CopyOnWriteArrayList<>();
        private final List<RunContext> failed = new CopyOnWriteArrayList<>();

        @Override
        public void beforeRun(RunContext run) {
            started.add(run);
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            finished.add(run);
            results.add(result);
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            failed.add(run);
        }
    }

    /** A fact whose method starts a run of the same engine, without options, from inside an action. */
    public static final class Nester {

        private final RulesEngine<Map<String, Object>> engine;
        private RunResult<Map<String, Object>> nested;

        Nester(RulesEngine<Map<String, Object>> engine) {
            this.engine = engine;
        }

        /**
         * Runs the engine again, with a fact that stops the rule from matching a second time.
         *
         * @return {@code true}
         */
        public boolean runNested() {
            nested = engine.runWithResult(new FactMap<>(new Fact<Object>("depth", 1)));
            return true;
        }
    }

    private static Rule rule(String name, String condition, String... tags) {
        return Rule.builder().ruleName(name).condition(condition).action("output.put('" + name + "', 1)")
                .tags(Set.of(tags)).build();
    }

    private static RulesEngine<Map<String, Object>> engine(Clock clock, RuleListener listener, Rule... rules) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .clock(clock).listener(listener).build();
        engine.load(List.of(rules));
        return engine;
    }

    /** A clock each read of which is a second later than the one before. */
    private static Clock ticking(AtomicInteger reads) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return NOW.plusSeconds(reads.getAndIncrement());
            }
        };
    }

    @Test
    @DisplayName("afterRun's context and result name the run's tags and start in their descriptions")
    // Uses only toString(), so it compiles against a release without tags() and startedAt().
    void descriptionsNameTheTagsAndStart() {
        Runs runs = new Runs();

        engine(AT_NOW, runs, rule("eu", "true", "eu")).runWithResult(new FactMap<>(), RETAIL_EU);

        String run = runs.finished.get(0).toString();
        String result = runs.results.get(0).toString();
        assertTrue(run.contains("tags=[eu, retail]") && run.contains("startedAt=2027-06-01T00:00:00Z"), run);
        assertTrue(result.contains("tags=[eu, retail]") && result.contains("startedAt=2027-06-01T00:00:00Z"),
                result);
    }

    @Test
    @DisplayName("afterRun's context and result, the caller's too, carry the sorted tags and the clock's instant")
    void tagsAndStartReachListenerAndCaller() {
        Runs runs = new Runs();
        RulesEngine<Map<String, Object>> engine = engine(AT_NOW, runs, rule("eu", "true", "eu"));

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>(), RETAIL_EU);

        RunContext run = runs.finished.get(0);
        assertEquals(List.of("eu", "retail"), new ArrayList<>(run.tags()), "in String order");
        assertThrows(UnsupportedOperationException.class, () -> run.tags().add("uk"));
        assertEquals(NOW, run.startedAt());
        assertSame(run.tags(), result.tags());
        assertEquals(NOW, result.startedAt());
        assertSame(runs.results.get(0), result, "afterRun sees the result the caller gets");
    }

    @Test
    @DisplayName("a run without tags has none, on its context and its result")
    void defaultsGiveNoTags() {
        Runs runs = new Runs();
        RulesEngine<Map<String, Object>> engine = engine(AT_NOW, runs, rule("eu", "true", "eu"));

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>(), RunOptions.defaults());

        assertEquals(Set.of(), runs.finished.get(0).tags());
        assertEquals(Set.of(), result.tags());
        assertEquals(NOW, result.startedAt());
    }

    @Test
    @DisplayName("the context, the result and the validity windows agree on the one instant the run read")
    void oneReadForContextResultAndWindows() {
        AtomicInteger reads = new AtomicInteger();
        Runs runs = new Runs();
        Rule endsSoon = rule("ends-soon", "true").toBuilder().validTo(NOW.plusSeconds(1)).build();
        RulesEngine<Map<String, Object>> engine = engine(ticking(reads), runs, endsSoon);

        RunResult<Map<String, Object>> first = engine.runWithResult(new FactMap<>());
        RunResult<Map<String, Object>> second = engine.runWithResult(new FactMap<>());

        assertEquals(2, reads.get(), "one read for each run");
        assertEquals(RuleEvaluation.Outcome.MATCHED, first.evaluations().get(0).outcome());
        assertEquals(NOW, first.startedAt());
        assertEquals(NOW, runs.finished.get(0).startedAt());
        assertEquals(RuleEvaluation.Outcome.SKIPPED, second.evaluations().get(0).outcome(), "judged at validTo");
        assertEquals(NOW.plusSeconds(1), second.startedAt());
        assertEquals(NOW.plusSeconds(1), runs.finished.get(1).startedAt());
    }

    @Test
    @DisplayName("a nested run has its own start and its own options' tags, none here; the run around it keeps its own")
    void nestedRunHasItsOwn() {
        AtomicInteger reads = new AtomicInteger();
        Runs runs = new Runs();
        Rule outer = Rule.builder().ruleName("outer").condition("depth == 0").action("nester.runNested()")
                .tags(Set.of("eu")).build();
        RulesEngine<Map<String, Object>> engine = engine(ticking(reads), runs, outer);
        Nester nester = new Nester(engine);

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>(new Fact<Object>("depth", 0),
                new Fact<Object>("nester", nester)), RunOptions.defaults().withTags(Set.of("eu")));

        RunContext parent = runs.started.get(0);
        RunContext child = runs.started.get(1);
        assertSame(parent, child.parent());
        assertEquals(Set.of(), child.tags());
        assertEquals(NOW.plusSeconds(1), child.startedAt());
        assertEquals(Set.of(), nester.nested.tags());
        assertEquals(NOW.plusSeconds(1), nester.nested.startedAt());
        assertEquals(Set.of("eu"), parent.tags());
        assertEquals(NOW, parent.startedAt());
        assertEquals(Set.of("eu"), result.tags());
        assertEquals(NOW, result.startedAt());
    }

    @Test
    @DisplayName("onRunError's context carries the tags and the start of a run a rule failed")
    void failedRunCarriesTagsAndStart() {
        Runs runs = new Runs();
        // No fact named x is given, so the condition fails the run.
        RulesEngine<Map<String, Object>> engine = engine(AT_NOW, runs, rule("broken", "x.missing > 1", "eu"));

        assertThrows(RuleExecutionException.class, () -> engine.runWithResult(new FactMap<>(), RETAIL_EU));

        RunContext run = runs.failed.get(0);
        assertEquals(List.of("eu", "retail"), new ArrayList<>(run.tags()));
        assertEquals(NOW, run.startedAt());
        assertTrue(runs.finished.isEmpty());
    }
}
