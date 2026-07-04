package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StatefulRulesEngine")
class StatefulRulesEngineTest {

    private StatefulRulesEngine<Map<String, Object>> engine;

    @BeforeEach
    void setUp() {
        engine = new StatefulRulesEngine<>(HashMap::new);
    }

    @Nested
    @DisplayName("run()")
    class Run {

        @Test
        @DisplayName("returns null when no rules are set")
        void returnsNullWhenNoRulesSet() {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 10);

            assertNull(engine.run(facts));
        }

        @Test
        @DisplayName("returns null when rule list is empty")
        void returnsNullWhenRuleListIsEmpty() {
            engine.setRuleList(Collections.emptyList());

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 10);

            assertNull(engine.run(facts));
        }

        @Test
        @DisplayName("fires action when condition is true")
        void firesActionWhenConditionIsTrue() {
            Rule rule = Rule.builder()
                    .ruleName("set-approved")
                    .condition("x > 5")
                    .action("output.put(\"approved\", true)")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 10);

            Map<String, Object> result = engine.run(facts);

            assertNotNull(result);
            assertEquals(true, result.get("approved"));
        }

        @Test
        @DisplayName("returns null when no rules match")
        void returnsNullWhenNoRulesMatch() {
            Rule rule = Rule.builder()
                    .ruleName("set-approved")
                    .condition("x > 100")
                    .action("output.put(\"approved\", true)")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 10);

            Map<String, Object> result = engine.run(facts);

            assertNull(result);
        }

        @Test
        @DisplayName("fires all matching rules and accumulates state")
        void firesAllMatchingRulesAndAccumulatesState() {
            Rule rule1 = Rule.builder()
                    .ruleName("set-name")
                    .condition("x > 0")
                    .action("output.put(\"name\", \"approved\")")
                    .priority(2)
                    .build();

            Rule rule2 = Rule.builder()
                    .ruleName("set-level")
                    .condition("x > 5")
                    .action("output.put(\"level\", \"high\")")
                    .priority(1)
                    .build();

            engine.setRuleList(Arrays.asList(rule1, rule2));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 10);

            Map<String, Object> result = engine.run(facts);

            assertEquals("approved", result.get("name"));
            assertEquals("high", result.get("level"));
        }

        @Test
        @DisplayName("lower priority rules can override higher priority fields")
        void lowerPriorityRulesCanOverride() {
            Rule highPriority = Rule.builder()
                    .ruleName("high-priority")
                    .condition("x > 0")
                    .action("output.put(\"status\", \"initial\")")
                    .priority(10)
                    .build();

            Rule lowPriority = Rule.builder()
                    .ruleName("low-priority")
                    .condition("x > 5")
                    .action("output.put(\"status\", \"overridden\")")
                    .priority(1)
                    .build();

            engine.setRuleList(Arrays.asList(lowPriority, highPriority));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 10);

            Map<String, Object> result = engine.run(facts);

            // High priority fires first, then low priority overrides the field
            assertEquals("overridden", result.get("status"));
        }
    }

    @Nested
    @DisplayName("rule ordering")
    class RuleOrdering {

        @Test
        @DisplayName("rules are sorted by priority descending")
        void rulesAreSortedByPriorityDescending() {
            Rule lowPriority = Rule.builder()
                    .ruleName("low")
                    .condition("true")
                    .action("output.put(\"last\", \"low\")")
                    .priority(1)
                    .build();

            Rule highPriority = Rule.builder()
                    .ruleName("high")
                    .condition("true")
                    .action("output.put(\"last\", \"high\")")
                    .priority(10)
                    .build();

            // Intentionally add in reverse priority order
            engine.setRuleList(Arrays.asList(lowPriority, highPriority));

            FactStore<Object> facts = new FactMap<>();

            Map<String, Object> result = engine.run(facts);

            // Low priority fires last (since it's sorted desc), so it overwrites
            assertEquals("low", result.get("last"));
        }
    }

    @Nested
    @DisplayName("complex fact objects")
    class ComplexFacts {

        @Test
        @DisplayName("can invoke methods on fact objects in conditions")
        void canInvokeMethodsOnFacts() {
            Rule rule = Rule.builder()
                    .ruleName("string-check")
                    .condition("name.startsWith(\"John\")")
                    .action("output.put(\"greeting\", \"Hello John\")")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("name", "John Doe");

            Map<String, Object> result = engine.run(facts);

            assertEquals("Hello John", result.get("greeting"));
        }

        @Test
        @DisplayName("can access facts in actions to compute output dynamically")
        void canAccessFactsInAction() {
            Rule rule = Rule.builder()
                    .ruleName("compute-discount")
                    .condition("amount > 100")
                    .action("output.put(\"discount\", amount * 0.10)")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("amount", 200.0);

            Map<String, Object> result = engine.run(facts);

            assertEquals(20.0, result.get("discount"));
        }
    }
}
