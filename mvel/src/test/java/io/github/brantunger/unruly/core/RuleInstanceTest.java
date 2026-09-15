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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a Rule is immutable, so the engine keeps the instance it was given instead of copying it")
class RuleInstanceTest {

    /** Records the rule each callback receives. */
    private static final class Recorder implements RuleListener {

        private final List<Rule> received = new CopyOnWriteArrayList<>();

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            received.add(rule);
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            received.add(rule);
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            received.add(rule);
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            received.add(rule);
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            received.add(rule);
        }
    }

    private static StatefulRulesEngine<Map<String, Object>> engine(Rule rule, RuleListener listener) {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.registerListener(listener);
        engine.setRuleList(List.of(rule));
        return engine;
    }

    @Test
    @DisplayName("the loaded rule and every listener callback are the rule passed to setRuleList()")
    void sameInstance() {
        Rule rule = Rule.builder().ruleName("r").condition("true").action("output.put('k', 1)").build();
        Recorder recorder = new Recorder();
        StatefulRulesEngine<Map<String, Object>> engine = engine(rule, recorder);

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));

        assertSame(rule, engine.getCompiledRules().get(0).rule());
        assertEquals(4, recorder.received.size());
        recorder.received.forEach(received -> assertSame(rule, received));
    }

    @Test
    @DisplayName("onError gets the same instance too")
    void sameInstanceOnError() {
        Rule rule = Rule.builder().ruleName("r").condition("true").action("output.noSuchMethod()").build();
        Recorder recorder = new Recorder();
        StatefulRulesEngine<Map<String, Object>> engine = engine(rule, recorder);

        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals(4, recorder.received.size());
        recorder.received.forEach(received -> assertSame(rule, received));
    }
}
