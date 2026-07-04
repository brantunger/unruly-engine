package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RuleListenerTest {

    private StatelessRulesEngine<Map<String, Object>> engine;
    private FactMap<Object> facts;
    
    @BeforeEach
    void setUp() {
        engine = new StatelessRulesEngine<>(HashMap::new);
        facts = new FactMap<>();
        facts.put(new Fact<>("input", 10));
    }

    @Test
    @DisplayName("Test RuleListener captures lifecycle events")
    void testRuleListenerCapturesEvents() {
        List<String> events = new ArrayList<>();
        
        RuleListener listener = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                events.add("beforeEvaluate: " + rule.getRuleName());
            }

            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                events.add("afterEvaluate: " + rule.getRuleName() + " (" + matchResult + ")");
            }

            @Override
            public void beforeExecute(Rule rule, Object output) {
                events.add("beforeExecute: " + rule.getRuleName());
            }

            @Override
            public void afterExecute(Rule rule, Object output) {
                events.add("afterExecute: " + rule.getRuleName());
            }
        };

        engine.registerListener(listener);
        
        Rule rule1 = Rule.builder()
                .ruleName("Rule1")
                .condition("input == 5")
                .action("output.put('result', 1)")
                .build();
                
        Rule rule2 = Rule.builder()
                .ruleName("Rule2")
                .condition("input == 10")
                .action("output.put('result', 2)")
                .build();
                
        engine.setRuleList(List.of(rule1, rule2));
        engine.run(facts);

        assertTrue(events.contains("beforeEvaluate: Rule1"));
        assertTrue(events.contains("afterEvaluate: Rule1 (false)"));
        assertTrue(events.contains("beforeEvaluate: Rule2"));
        assertTrue(events.contains("afterEvaluate: Rule2 (true)"));
        assertTrue(events.contains("beforeExecute: Rule2"));
        assertTrue(events.contains("afterExecute: Rule2"));
        assertFalse(events.contains("beforeExecute: Rule1"));
    }

    @Test
    @DisplayName("Test Exception in RuleListener doesn't break engine")
    void testRuleListenerExceptionIsCaught() {
        RuleListener badListener = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                throw new RuntimeException("Bad listener beforeEvaluate");
            }
            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                throw new RuntimeException("Bad listener afterEvaluate");
            }
            @Override
            public void beforeExecute(Rule rule, Object output) {
                throw new RuntimeException("Bad listener beforeExecute");
            }
            @Override
            public void afterExecute(Rule rule, Object output) {
                throw new RuntimeException("Bad listener afterExecute");
            }
        };
        
        engine.registerListener(badListener);
        engine.registerListener(null);
        engine.registerListeners(null);
        engine.registerListeners(List.of(new io.github.brantunger.unruly.api.LoggingRuleListener()));
        
        Rule rule1 = Rule.builder()
                .ruleName("Rule1")
                .condition("input == 10")
                .action("output.put('result', 1)")
                .build();
                
        engine.setRuleList(List.of(rule1));
        
        // This should not throw, the engine should catch it and continue
        Map<String, Object> result = engine.run(facts);
        assertEquals(1, result.get("result"));
    }
}
