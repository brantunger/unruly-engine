package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a run reports the output, the rules that fired and the rules it used")
class RunResultTest {

    private static final Rule HIGH = Rule.builder().ruleName("high").priority(2).condition("true")
            .action("output.put('high', 1)").build();
    private static final Rule LOW = Rule.builder().ruleName("low").priority(1).condition("true")
            .action("output.put('low', 1)").build();
    private static final Rule NEVER = Rule.builder().ruleName("never").condition("false")
            .action("output.put('never', 1)").build();

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
}
