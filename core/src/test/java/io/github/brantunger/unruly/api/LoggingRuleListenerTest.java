package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class LoggingRuleListenerTest {

    @Test
    @DisplayName("Test LoggingRuleListener executes without exceptions")
    void testLoggingRuleListener() {
        LoggingRuleListener listener = new LoggingRuleListener();
        Rule rule = Rule.builder().ruleName("test").condition("true").action("").build();
        Map<String, Object> facts = new HashMap<>();
        
        assertDoesNotThrow(() -> {
            listener.beforeEvaluate(rule, facts);
            listener.afterEvaluate(rule, facts, true);
            listener.beforeExecute(rule, new Object());
            listener.afterExecute(rule, new Object());
        });
    }

    @Test
    @DisplayName("Test RuleListener default methods execute without exceptions")
    void testRuleListenerDefaultMethods() {
        RuleListener listener = new RuleListener() {};
        Rule rule = Rule.builder().ruleName("test").condition("true").action("").build();
        Map<String, Object> facts = new HashMap<>();
        
        assertDoesNotThrow(() -> {
            listener.beforeEvaluate(rule, facts);
            listener.afterEvaluate(rule, facts, true);
            listener.beforeExecute(rule, new Object());
            listener.afterExecute(rule, new Object());
        });
    }
}
