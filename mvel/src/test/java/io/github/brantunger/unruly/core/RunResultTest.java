package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a run reports the output, the rules that fired and the rules it used")
class RunResultTest {

    private static final Rule HIGH = Rule.builder().ruleName("high").priority(2).condition("true")
            .action("output.put('high', 1)").build();
    private static final Rule LOW = Rule.builder().ruleName("low").priority(1).condition("true")
            .action("output.put('low', 1)").build();
    private static final Rule NEVER = Rule.builder().ruleName("never").condition("false")
            .action("output.put('never', 1)").build();
    private static final Instant NOW = Instant.parse("2027-06-01T00:00:00Z");

    private static RulesEngine<Map<String, Object>> firstMatch(Rule... rules) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(rules));
        return engine;
    }

    private static RulesEngine<Map<String, Object>> allMatches(Rule... rules) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        engine.load(List.of(rules));
        return engine;
    }

    @Test
    @DisplayName("a first-match engine reports the one rule that fired")
    void firstMatchFiresOne() {
        RulesEngine<Map<String, Object>> engine = firstMatch(HIGH, LOW);

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>());

        assertEquals(Map.of("high", 1), result.output());
        assertEquals(List.of(HIGH), result.firedRules());
        assertEquals(engine.rules().checksum(), result.ruleSetChecksum());
    }

    @Test
    @DisplayName("an all-matches engine reports every rule that fired, in firing order")
    void allMatchesFiresEach() {
        RunResult<Map<String, Object>> result = allMatches(HIGH, LOW).runWithResult(new FactMap<>());

        assertEquals(Map.of("high", 1, "low", 1), result.output());
        assertEquals(List.of(HIGH, LOW), result.firedRules());
    }

    @Test
    @DisplayName("no match and an empty rule list report no output and no fired rules, with the rules' checksum")
    void nothingFired() {
        RulesEngine<Map<String, Object>> noMatch = firstMatch(NEVER);
        RulesEngine<Map<String, Object>> empty = firstMatch();

        for (RulesEngine<Map<String, Object>> engine : List.of(noMatch, empty)) {
            RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>());

            assertNull(result.output());
            assertEquals(List.of(), result.firedRules());
            assertEquals(engine.rules().checksum(), result.ruleSetChecksum());
        }
    }

    @Test
    @DisplayName("run() returns the result's output")
    void runReturnsTheOutput() {
        RulesEngine<Map<String, Object>> engine = firstMatch(HIGH);

        assertEquals(engine.runWithResult(new FactMap<>()).output(), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a result keeps the checksum of the rules its run used, not the engine's later rules")
    void checksumIsOfTheRulesTheRunUsed() {
        RulesEngine<Map<String, Object>> engine = firstMatch(HIGH);
        RunResult<Map<String, Object>> before = engine.runWithResult(new FactMap<>());

        engine.load(List.of(LOW));

        assertNotEquals(engine.rules().checksum(), before.ruleSetChecksum());
        assertEquals(engine.runWithResult(new FactMap<>()).ruleSetChecksum(), engine.rules().checksum());
    }

    @Test
    @DisplayName("a result names the rules that fired in its toString")
    void readableToString() {
        RunResult<Map<String, Object>> result = allMatches(HIGH, LOW).runWithResult(new FactMap<>());

        assertTrue(result.toString().startsWith("RunResult(output={"), result.toString());
        assertTrue(result.toString().contains("firedRules=[high, low]"), result.toString());
    }

    @Test
    @DisplayName("a result names the run's tags and when it started in its toString")
    void toStringNamesTheTagsAndStart() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)).build();
        engine.load(List.of(HIGH));

        RunResult<Map<String, Object>> result = engine.runWithResult(new FactMap<>(),
                RunOptions.defaults().withTags(Set.of("retail", "eu")));

        assertTrue(result.toString().endsWith(", tags=[eu, retail], startedAt=2027-06-01T00:00:00Z)"),
                result.toString());
    }

    @Test
    @DisplayName("a result created with of(...) has no tags and no start")
    void ofCarriesNoRun() {
        RuleEvaluation matched = RuleEvaluation.of(HIGH, RuleEvaluation.Outcome.MATCHED);

        for (RunResult<String> result : List.of(RunResult.of("out", List.of(HIGH), "c"),
                RunResult.of("out", List.of(HIGH), List.of(matched), "c"))) {
            assertEquals(Set.of(), result.tags());
            assertNull(result.startedAt());
            assertTrue(result.toString().endsWith(", tags=[], startedAt=null)"), result.toString());
        }
    }

    @Test
    @DisplayName("withRun copies the result with the run's tags and start, and keeps everything else")
    void withRunCarriesTheRun() {
        AtomicReference<RunContext> seen = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        seen.set(run);
                    }
                }).build();
        engine.load(List.of(HIGH));
        engine.runWithResult(new FactMap<>(), RunOptions.defaults().withTags(Set.of("retail", "eu")));
        RuleEvaluation matched = RuleEvaluation.of(HIGH, RuleEvaluation.Outcome.MATCHED);
        RunResult<String> plain = RunResult.of("out", List.of(HIGH), List.of(matched), "c");

        RunResult<String> carried = plain.withRun(seen.get());

        assertNotSame(plain, carried);
        assertSame(seen.get().tags(), carried.tags(), "the run's own sorted, unmodifiable tags");
        assertEquals(List.of("eu", "retail"), List.copyOf(carried.tags()));
        assertEquals(NOW, carried.startedAt());
        assertEquals("out", carried.output());
        assertEquals(List.of(HIGH), carried.firedRules());
        assertEquals(List.of(matched), carried.evaluations());
        assertEquals("c", carried.ruleSetChecksum());
        assertEquals(Set.of(), plain.tags(), "the original is unchanged");
        assertNull(plain.startedAt());
    }

    @Test
    @DisplayName("withRun rejects a null run")
    void withRunRejectsNull() {
        RunResult<String> result = RunResult.of("out", List.of(), "c");

        assertEquals("run must not be null",
                assertThrows(NullPointerException.class, () -> result.withRun(null)).getMessage());
    }
}
