package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("afterExecute receives the output object run() returns")
class ListenerOutputTest {

    @Test
    @DisplayName("the output passed to afterExecute is the object run() returns, already changed by the action")
    void afterExecuteReceivesOutput() {
        AtomicReference<Object> seen = new AtomicReference<>();
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.listener(new RuleListener() {
                    @Override
                    public void afterExecute(Rule rule, Object output) {
                        seen.set(output);
                    }
                }));
        engine.load(List.of(Rule.builder().ruleName("a").condition("true").action("output.put('k', 1)").build()));

        Map<String, Object> output = engine.run(new FactMap<>());

        assertSame(output, seen.get());
        assertEquals(Map.of("k", 1), output);
    }
}
