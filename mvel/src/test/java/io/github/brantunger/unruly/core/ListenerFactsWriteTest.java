package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a listener that writes to the facts is told the facts are read-only")
class ListenerFactsWriteTest {

    /** Tries to write a fact and returns the message it was rejected with. */
    private static String attemptWrite(Map<String, Object> facts, String name) {
        try {
            facts.put(name, 2);
            return "write to '" + name + "' was accepted";
        } catch (UnsupportedOperationException e) {
            return e.getMessage();
        }
    }

    @Test
    @DisplayName("beforeEvaluate and afterEvaluate get a message about listeners, and the facts are unchanged")
    void listenerWriteRejected() {
        List<String> messages = new CopyOnWriteArrayList<>();
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.listener(new RuleListener() {
                    @Override
                    public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                        messages.add(attemptWrite(facts, "z"));
                    }

                    @Override
                    public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                        messages.add(attemptWrite(facts, "x"));
                    }
                }));
        engine.load(List.of(Rule.builder().ruleName("r").condition("x == 1").action("output.put('k', x)").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        assertEquals(Map.of("k", 1), engine.run(facts));
        assertEquals(List.of("The facts passed to a RuleListener are read-only; 'z' can't be changed.",
                "The facts passed to a RuleListener are read-only; 'x' can't be changed."), messages);
    }
}
