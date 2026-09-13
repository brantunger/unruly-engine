package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a listener registered inside a callback starts with a before* callback")
class ListenerRegisteredMidRuleTest {

    private static final class Recorder implements RuleListener {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            events.add("beforeEvaluate:" + rule.getRuleName());
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            events.add("afterEvaluate:" + rule.getRuleName());
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            events.add("beforeExecute:" + rule.getRuleName());
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            events.add("afterExecute:" + rule.getRuleName());
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            events.add("onError:" + rule.getRuleName());
        }
    }

    /** Registers {@code late} the first time any rule's condition is about to be evaluated. */
    private static RuleListener registersOnFirstBeforeEvaluate(AbstractRulesEngine<?> engine, RuleListener late) {
        AtomicBoolean added = new AtomicBoolean();
        return new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                if (added.compareAndSet(false, true)) {
                    engine.registerListener(late);
                }
            }
        };
    }

    private static Rule rule(String name, int priority, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action(action).build();
    }

    @Test
    @DisplayName("registered during a condition, it skips that condition's afterEvaluate and gets whole pairs after")
    void registeredDuringCondition() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        Recorder late = new Recorder();
        engine.registerListener(registersOnFirstBeforeEvaluate(engine, late));
        engine.setRuleList(List.of(
                rule("first", 2, "true", "output.put('a', 1)"),
                rule("second", 1, "true", "output.put('b', 2)")));

        engine.run(new FactMap<>());

        assertEquals(List.of(
                "beforeEvaluate:second", "afterEvaluate:second",
                "beforeExecute:first", "afterExecute:first",
                "beforeExecute:second", "afterExecute:second"), late.events);
    }

    @Test
    @DisplayName("registered during a failing condition, it doesn't get that condition's onError")
    void registeredDuringFailingCondition() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        Recorder late = new Recorder();
        engine.registerListener(registersOnFirstBeforeEvaluate(engine, late));
        engine.setRuleList(List.of(rule("bad", 1, "missing > 1", "output.put('k', 1)")));

        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals(List.of(), late.events);
    }
}
