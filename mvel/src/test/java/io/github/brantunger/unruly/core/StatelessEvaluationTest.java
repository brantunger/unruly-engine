package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a first-match engine evaluates every condition before firing one action")
class StatelessEvaluationTest {

    private static Rule rule(String name, int priority, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action(action).build();
    }

    @Test
    @DisplayName("a lower-priority condition is evaluated even though a higher-priority rule already matched")
    void everyConditionEvaluated() {
        List<String> evaluated = new CopyOnWriteArrayList<>();
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.listener(new RuleListener() {
                    @Override
                    public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                        evaluated.add(rule.getRuleName() + "=" + matchResult);
                    }
                }));
        engine.load(List.of(
                rule("high", 2, "true", "output.put('fired', 'high')"),
                rule("low", 1, "true", "output.put('fired', 'low')")));

        assertEquals(Map.of("fired", "high"), engine.run(new FactMap<>()));
        assertEquals(List.of("high=true", "low=true"), evaluated);
    }

    @Test
    @DisplayName("a lower-priority condition that throws makes run() throw")
    void lowerPriorityConditionFailureThrows() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(
                rule("high", 2, "true", "output.put('fired', 'high')"),
                rule("low", 1, "x.missing > 1", "output.put('fired', 'low')")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));

        assertTrue(ex.getMessage().startsWith("Failed to evaluate condition for rule 'low'"), ex.getMessage());
    }
}
