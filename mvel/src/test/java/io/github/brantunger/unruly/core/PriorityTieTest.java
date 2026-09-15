package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the documented tie-break: equal priorities keep their order from the rule list. */
@DisplayName("equal priorities")
class PriorityTieTest {

    private static Rule rule(String name, Integer priority) {
        return Rule.builder()
                .ruleName(name)
                .priority(priority)
                .condition("true")
                .action("output.put('winner', '" + name + "'); output.put('order', output.getOrDefault('order', '') + '" + name + "')")
                .build();
    }

    @Test
    @DisplayName("the stateless engine fires the first-listed of the top-priority rules")
    void statelessPicksFirstListed() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);

        engine.load(List.of(rule("low", 1), rule("a", 5), rule("b", 5)));
        assertEquals("a", engine.run(new FactMap<>()).get("winner"));

        engine.load(List.of(rule("low", 1), rule("b", 5), rule("a", 5)));
        assertEquals("b", engine.run(new FactMap<>()).get("winner"));
    }

    @Test
    @DisplayName("the stateful engine fires equal priorities in list order, null priorities last")
    void statefulKeepsListOrder() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);

        engine.load(List.of(rule("n", null), rule("c", 5), rule("a", 5), rule("b", 5)));

        assertEquals("cabn", engine.run(new FactMap<>()).get("order"));
    }
}
