package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a first-match engine stops at the first matching condition")
class StatelessEvaluationTest {

    private static Rule rule(String name, int priority, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action(action).build();
    }

    private static StatelessRulesEngine<Map<String, Object>> recording(List<String> evaluated) {
        return TestEngines.firstMatch(HashMap::new, builder -> builder.listener(new RuleListener() {
            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                evaluated.add(rule.getRuleName() + "=" + matchResult);
            }
        }));
    }

    @Test
    @DisplayName("the rules below the match aren't evaluated: they're neither matched nor unmatched")
    void stopsAtTheFirstMatch() {
        List<String> evaluated = new CopyOnWriteArrayList<>();
        StatelessRulesEngine<Map<String, Object>> engine = recording(evaluated);
        engine.load(List.of(
                rule("high", 2, "true", "output.put('fired', 'high')"),
                rule("low", 1, "true", "output.put('fired', 'low')")));

        assertEquals(Map.of("fired", "high"), engine.run(new FactMap<>()));
        assertEquals(List.of("high=true"), evaluated);
    }

    @Test
    @DisplayName("conditions are evaluated in priority order until one matches")
    void evaluatesUntilAMatch() {
        List<String> evaluated = new CopyOnWriteArrayList<>();
        StatelessRulesEngine<Map<String, Object>> engine = recording(evaluated);
        engine.load(List.of(
                rule("high", 3, "false", "output.put('fired', 'high')"),
                rule("middle", 2, "true", "output.put('fired', 'middle')"),
                rule("low", 1, "true", "output.put('fired', 'low')")));

        assertEquals(Map.of("fired", "middle"), engine.run(new FactMap<>()));
        assertEquals(List.of("high=false", "middle=true"), evaluated);
    }

    @Test
    @DisplayName("a broken lower-priority condition no longer fails a run that a higher-priority rule already decided")
    void brokenLowerPriorityRuleIsNotEvaluated() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(
                rule("high", 2, "true", "output.put('fired', 'high')"),
                rule("low", 1, "x.missing > 1", "output.put('fired', 'low')")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        assertEquals(Map.of("fired", "high"), engine.run(facts));
    }

    @Test
    @DisplayName("a broken condition still fails the run when no higher-priority rule matched first")
    void brokenConditionBeforeAMatchStillThrows() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(
                rule("broken", 2, "x.missing > 1", "output.put('fired', 'broken')"),
                rule("low", 1, "true", "output.put('fired', 'low')")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.run(facts));

        assertTrue(ex.getMessage().startsWith("Failed to evaluate condition for rule 'broken'"), ex.getMessage());
    }

    @Test
    @DisplayName("an all-matches engine still evaluates every condition before firing")
    void allMatchesStillEvaluatesEverything() {
        List<String> evaluated = new CopyOnWriteArrayList<>();
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.listener(new RuleListener() {
                    @Override
                    public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                        evaluated.add(rule.getRuleName() + "=" + matchResult);
                    }
                }));
        engine.load(List.of(
                rule("high", 2, "true", "output.put('high', 1)"),
                rule("low", 1, "true", "output.put('low', 1)")));

        engine.run(new FactMap<>());

        assertEquals(List.of("high=true", "low=true"), evaluated);
    }
}
