package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("assigning to output in an action")
class OutputReassignmentTest {

    private static Rule rule(String name, int priority, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition("true").action(action).build();
    }

    @Test
    @DisplayName("an immutable output can't be replaced; the assignment throws instead of being ignored")
    void immutableOutputReassignmentThrows() {
        RulesEngine<Integer> engine = RulesEngineBuilder.stateful(() -> 0);
        engine.setRuleList(List.of(rule("increments", 1, "output = output + 1")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("rule 'increments'"));
        assertTrue(ex.getMessage().contains("Cannot assign 'output'"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "output = new java.util.HashMap()",
            "if (true) { output = new java.util.HashMap() }",
            "foreach (i : [1, 2]) { output = new java.util.HashMap() }",
            "java.util.Map output = new java.util.HashMap()",
    })
    void mapOutputReassignmentThrows(String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateless(HashMap::new);
        engine.setRuleList(List.of(rule("replaces", 1, action)));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        assertTrue(ex.getMessage().contains("Cannot assign 'output'"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"output += 1", "output++"})
    void compoundOutputAssignmentThrows(String action) {
        RulesEngine<Integer> engine = RulesEngineBuilder.stateless(() -> 0);
        engine.setRuleList(List.of(rule("compound", 1, action)));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        assertTrue(ex.getMessage().contains("Cannot assign 'output'"));
    }

    @Test
    @DisplayName("changing the output in place and assigning other locals still work")
    void inPlaceChangesStillWork() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);
        engine.setRuleList(List.of(
                rule("put", 4, "output.put('a', 1)"),
                rule("with", 3, "with (output) { put('w', 2) }"),
                rule("locals", 2, "x = 5; int y = 6; output.put('xy', x + y)"),
                rule("function-local", 1, "def f() { output = 1 }; f(); output.put('f', 1)")));

        assertEquals(Map.of("a", 1, "w", 2, "xy", 11, "f", 1), engine.run(new FactMap<>()));
    }
}
