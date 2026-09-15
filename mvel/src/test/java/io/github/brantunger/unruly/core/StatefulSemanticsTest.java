package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the documented StatefulRulesEngine semantics so a change to them is a deliberate one. */
@DisplayName("StatefulRulesEngine documented semantics")
public class StatefulSemanticsTest {

    // Public with public accessors so MVEL can read and write the property.
    public static class Claim {
        private String status;

        public Claim(String status) {
            this.status = status;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    @Test
    @DisplayName("all conditions are evaluated before any action fires")
    void matchThenFire() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(
                Rule.builder().ruleName("deny").priority(2)
                        .condition("claim.status == 'PENDING'")
                        .action("claim.status = 'DENIED'; output.put('denied', true)").build(),
                Rule.builder().ruleName("approve").priority(1)
                        .condition("claim.status == 'PENDING'")
                        .action("output.put('approved', true)").build()));

        Claim claim = new Claim("PENDING");
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("claim", claim);

        Map<String, Object> result = engine.run(facts);

        assertEquals("DENIED", claim.getStatus());
        assertEquals(Map.of("denied", true, "approved", true), result);
    }

    @Test
    @DisplayName("a failing action does not undo the actions that ran before it")
    void notAtomic() {
        Map<String, Object> output = new HashMap<>();
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(() -> output);
        engine.setRuleList(List.of(
                Rule.builder().ruleName("first").priority(2).condition("true")
                        .action("claim.status = 'CHANGED'; output.put('first', true)").build(),
                Rule.builder().ruleName("broken").priority(1).condition("true")
                        .action("output.noSuchMethod()").build()));

        Claim claim = new Claim("PENDING");
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("claim", claim);

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(facts));

        assertTrue(ex.getMessage().contains("'broken'"));
        assertEquals("CHANGED", claim.getStatus());
        assertEquals(Map.of("first", true), output);
    }
}
