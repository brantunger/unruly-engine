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

@DisplayName("each listener callback gets its own Rule copy")
class ListenerRuleCopyTest {

    private static String describe(Rule rule) {
        return rule.getRuleName() + "/" + rule.getCondition() + "/" + rule.getAction() + "/" + rule.getPriority()
                + "/" + rule.getDescription();
    }

    /** Records the rule it is handed, then vandalizes it. */
    private static final class MutatingListener implements RuleListener {
        private final List<String> seen = new CopyOnWriteArrayList<>();

        private void record(Rule rule) {
            seen.add(describe(rule));
            rule.setRuleName("hacked");
            rule.setCondition("false");
            rule.setAction("output.put('hacked', true)");
            rule.setPriority(-99);
            rule.setDescription("hacked");
        }

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            record(rule);
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            record(rule);
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            record(rule);
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            record(rule);
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            record(rule);
        }
    }

    private static StatefulRulesEngine<Map<String, Object>> engine(String action, RuleListener... listeners) {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        for (RuleListener listener : listeners) {
            engine.registerListener(listener);
        }
        engine.setRuleList(List.of(Rule.builder().ruleName("orig").condition("true").action(action)
                .priority(5).description("d").build()));
        return engine;
    }

    @Test
    @DisplayName("a listener's edits don't reach its later callbacks or later runs")
    void editsDoNotPersist() {
        MutatingListener listener = new MutatingListener();
        StatefulRulesEngine<Map<String, Object>> engine = engine("output.put('k', 1)", listener);

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
        assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));

        String original = "orig/true/output.put('k', 1)/5/d";
        assertEquals(List.of(original, original, original, original, original, original, original, original),
                listener.seen);
    }

    @Test
    @DisplayName("a listener's edits don't reach the next listener")
    void editsDoNotReachOtherListeners() {
        MutatingListener first = new MutatingListener();
        MutatingListener second = new MutatingListener();
        StatefulRulesEngine<Map<String, Object>> engine = engine("output.put('k', 1)", first, second);

        engine.run(new FactMap<>());

        assertEquals(first.seen, second.seen);
        assertTrue(second.seen.stream().allMatch(s -> s.startsWith("orig/")));
    }

    @Test
    @DisplayName("onError also receives an unedited copy, and the error still names the original rule")
    void onErrorGetsCopy() {
        MutatingListener listener = new MutatingListener();
        StatefulRulesEngine<Map<String, Object>> engine = engine("output.noSuchMethod()", listener);

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("rule 'orig'"));
        assertEquals(4, listener.seen.size());
        assertTrue(listener.seen.stream().allMatch(s -> s.equals("orig/true/output.noSuchMethod()/5/d")));
    }
}
