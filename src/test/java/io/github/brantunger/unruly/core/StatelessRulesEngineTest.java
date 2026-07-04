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

@DisplayName("StatelessRulesEngine")
class StatelessRulesEngineTest {

    private StatelessRulesEngine<Map<String, Object>> engine;

    @BeforeEach
    void setUp() {
        engine = new StatelessRulesEngine<>(HashMap::new);
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
        @DisplayName("returns null when no rules match")
        void returnsNullWhenNoRulesMatch() {
            Rule rule = Rule.builder()
                    .ruleName("impossible")
                    .condition("x > 1000")
                    .action("output.put(\"key\", \"value\")")
                    .priority(1)
                    .build();

            engine.setRuleList(List.of(rule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 1);

            assertNull(engine.run(facts));
        }

        @Test
        @DisplayName("fires only the highest priority matching rule")
        void firesOnlyHighestPriorityRule() {
            Rule highPriority = Rule.builder()
                    .ruleName("high")
                    .condition("x > 0")
                    .action("output.put(\"source\", \"high\")")
                    .priority(10)
                    .build();

            Rule lowPriority = Rule.builder()
                    .ruleName("low")
                    .condition("x > 0")
                    .action("output.put(\"source\", \"low\")")
                    .priority(1)
                    .build();

            engine.setRuleList(Arrays.asList(lowPriority, highPriority));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 5);

            Map<String, Object> result = engine.run(facts);

            assertNotNull(result);
            assertEquals("high", result.get("source"));
            // Only one rule fires, so only one key
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("fires single matching rule when only one matches")
        void firesSingleMatchingRule() {
            Rule matchingRule = Rule.builder()
                    .ruleName("matches")
                    .condition("x > 5")
                    .action("output.put(\"result\", \"matched\")")
                    .priority(1)
                    .build();

            Rule nonMatchingRule = Rule.builder()
                    .ruleName("does-not-match")
                    .condition("x > 100")
                    .action("output.put(\"result\", \"should-not-fire\")")
                    .priority(10)
                    .build();

            engine.setRuleList(Arrays.asList(matchingRule, nonMatchingRule));

            FactStore<Object> facts = new FactMap<>();
            facts.setValue("x", 10);

            Map<String, Object> result = engine.run(facts);

            assertNotNull(result);
            assertEquals("matched", result.get("result"));
        }
    }

    @Nested
    @DisplayName("stateless behavior")
    class StatelessBehavior {

        @Test
        @DisplayName("produces output from a single rule only")
        void producesOutputFromSingleRule() {
            Rule rule1 = Rule.builder()
                    .ruleName("set-name")
                    .condition("true")
                    .action("output.put(\"name\", \"set-by-rule1\")")
                    .priority(10)
                    .build();

            Rule rule2 = Rule.builder()
                    .ruleName("set-level")
                    .condition("true")
                    .action("output.put(\"name\", \"set-by-rule2\"); output.put(\"extra\", \"yes\")")
                    .priority(5)
                    .build();

            engine.setRuleList(Arrays.asList(rule1, rule2));

            FactStore<Object> facts = new FactMap<>();

            Map<String, Object> result = engine.run(facts);

            // Only rule1 (highest priority) should fire
            assertEquals("set-by-rule1", result.get("name"));
            assertNull(result.get("extra"));
        }
    }

    @Nested
    @DisplayName("MVEL expression errors")
    class ExpressionErrors {

        @Test
        @DisplayName("throws exception for invalid condition expression")
        void throwsForInvalidCondition() {
            Rule rule = Rule.builder()
                    .ruleName("bad-condition")
                    .condition("this is not valid MVEL !!!@@@")
                    .action("output.put(\"x\", 1)")
                    .priority(1)
                    .build();

            assertThrows(Exception.class, () -> engine.setRuleList(List.of(rule)));
        }

        @Test
        @DisplayName("throws exception for invalid action expression at compile or run time")
        void throwsForInvalidAction() {
            Rule rule = Rule.builder()
                    .ruleName("bad-action")
                    .condition("true")
                    .action("output.nonExistentMethod(!!!)")
                    .priority(1)
                    .build();

            assertThrows(Exception.class, () -> {
                engine.setRuleList(List.of(rule));
                FactStore<Object> facts = new FactMap<>();
                engine.run(facts);
            });
        }
    }
}
