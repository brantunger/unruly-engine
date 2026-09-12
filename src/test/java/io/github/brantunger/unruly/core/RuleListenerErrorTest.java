package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.LoggingRuleListener;
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

@DisplayName("RuleListener.onError")
class RuleListenerErrorTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<RuleExecutionException> errors = new CopyOnWriteArrayList<>();

    private final RuleListener recorder = new RuleListener() {
        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            events.add("beforeEvaluate");
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            events.add("afterEvaluate");
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            events.add("beforeExecute");
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            events.add("afterExecute");
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            events.add("onError:" + rule.getRuleName());
            errors.add(error);
        }
    };

    private RuleExecutionException runExpectingFailure(String condition, String action) {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.registerListener(recorder);
        engine.setRuleList(List.of(Rule.builder()
                .ruleName("failing")
                .condition(condition)
                .action(action)
                .priority(1)
                .build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("status", "OPEN");

        return assertThrows(RuleExecutionException.class, () -> engine.run(facts));
    }

    @Test
    @DisplayName("a condition that throws ends beforeEvaluate with onError")
    void conditionException() {
        RuleExecutionException thrown = runExpectingFailure("missing > 1", "output.put('k', 1)");

        assertEquals(List.of("beforeEvaluate", "onError:failing"), events);
        assertSame(thrown, errors.get(0));
        assertNotNull(thrown.getCause());
    }

    @Test
    @DisplayName("a condition that evaluates to null ends beforeEvaluate with onError")
    void conditionNull() {
        RuleExecutionException thrown = runExpectingFailure("null", "output.put('k', 1)");

        assertEquals(List.of("beforeEvaluate", "onError:failing"), events);
        assertSame(thrown, errors.get(0));
    }

    @Test
    @DisplayName("a non-boolean condition ends beforeEvaluate with onError")
    void conditionNonBoolean() {
        RuleExecutionException thrown = runExpectingFailure("status", "output.put('k', 1)");

        assertEquals(List.of("beforeEvaluate", "onError:failing"), events);
        assertSame(thrown, errors.get(0));
    }

    @Test
    @DisplayName("an action that throws ends beforeExecute with onError")
    void actionException() {
        RuleExecutionException thrown = runExpectingFailure("true", "output.noSuchMethod()");

        assertEquals(List.of("beforeEvaluate", "afterEvaluate", "beforeExecute", "onError:failing"), events);
        assertSame(thrown, errors.get(0));
    }

    @Test
    @DisplayName("a listener that throws in onError does not replace the rule's exception")
    void throwingOnErrorIsContained() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.registerListener(new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw new IllegalStateException("listener bug");
            }
        });
        engine.registerListener(recorder);
        engine.setRuleList(List.of(Rule.builder().ruleName("failing").condition("null")
                .action("output.put('k', 1)").build()));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        assertTrue(thrown.getMessage().contains("evaluated to null"));
        assertEquals(List.of(thrown), errors, "later listeners are still notified");
    }

    @Test
    @DisplayName("default and logging implementations accept onError")
    void defaultAndLoggingImplementations() {
        Rule rule = Rule.builder().ruleName("r").condition("true").action("x").build();
        RuleExecutionException error = new RuleExecutionException("boom");

        assertDoesNotThrow(() -> new RuleListener() { }.onError(rule, error));
        assertDoesNotThrow(() -> new LoggingRuleListener().onError(rule, error));
    }
}
