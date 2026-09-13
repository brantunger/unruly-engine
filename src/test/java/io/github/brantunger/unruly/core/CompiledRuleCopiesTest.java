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

@DisplayName("each run uses a compiled copy of the rules that no other run is using")
class CompiledRuleCopiesTest {

    private static FactStore<Object> x(int value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", value);
        return facts;
    }

    @Test
    @DisplayName("a run started while another is still running gets its own copy and the right result")
    void runDuringRun() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        List<Object> innerOutputs = new CopyOnWriteArrayList<>();
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                // Only the outer run starts an inner one, while it still holds its copy of the rules.
                if (innerOutputs.isEmpty()) {
                    innerOutputs.add("started");
                    innerOutputs.set(0, engine.run(x(2)));
                }
            }
        });
        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("x > 0").action("output.put('x', x)").build()));

        assertEquals(Map.of("x", 1), engine.run(x(1)));
        assertEquals(List.of(Map.of("x", 2)), innerOutputs);
        assertEquals(Map.of("x", 3), engine.run(x(3)), "both copies are reused afterwards");
    }

    @Test
    @DisplayName("getCompiledRules returns the rules compiled by setRuleList, unmodifiable, or null before it")
    void compiledRules() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        assertNull(engine.getCompiledRules());

        engine.setRuleList(List.of(Rule.builder().ruleName("r").condition("x > 0").action("output.put('x', x)").build()));
        List<CompiledRule> rules = engine.getCompiledRules();

        assertEquals(List.of("r"), rules.stream().map(CompiledRule::displayName).toList());
        assertThrows(UnsupportedOperationException.class, () -> rules.remove(0));
    }
}
