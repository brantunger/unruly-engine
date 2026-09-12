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

// Public, as is the fact class: MVEL's reflective accessors need to reach its getter.
@DisplayName("error message names and descriptions")
public class ErrorMessageNameTest {

    public static class Exploding {
        public int getValue() {
            throw new IllegalStateException();
        }
    }

    private static RuleExecutionException runUnnamed(String condition, String action) {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(Rule.builder().condition(condition).action(action).build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("claim", new Exploding());
        return assertThrows(RuleExecutionException.class, () -> engine.run(facts));
    }

    @Test
    @DisplayName("a failing condition on an unnamed rule is reported as (unnamed)")
    void unnamedConditionFailure() {
        RuleExecutionException ex = runUnnamed("missing > 1", "output.put('k', 1)");

        assertTrue(ex.getMessage().contains("rule '(unnamed)'"), ex.getMessage());
        assertFalse(ex.getMessage().contains("'null'"));
    }

    @Test
    @DisplayName("null and non-boolean conditions on an unnamed rule are reported as (unnamed)")
    void unnamedConditionResults() {
        assertTrue(runUnnamed("null", "output.put('k', 1)").getMessage().contains("rule '(unnamed)'"));
        assertTrue(runUnnamed("'text'", "output.put('k', 1)").getMessage().contains("rule '(unnamed)'"));
    }

    @Test
    @DisplayName("a failing action on an unnamed rule is reported as (unnamed)")
    void unnamedActionFailure() {
        RuleExecutionException ex = runUnnamed("true", "output.noSuchMethod()");

        assertTrue(ex.getMessage().contains("rule '(unnamed)'"), ex.getMessage());
    }

    @Test
    @DisplayName("an exception without a message never produces a message ending in ': null'")
    void messagelessExceptionDescribed() {
        RuleExecutionException ex = runUnnamed("claim.value > 1", "output.put('k', 1)");

        assertFalse(ex.getMessage().endsWith(": null"), ex.getMessage());
    }

    @Test
    @DisplayName("describe falls back to the class name when there is no message")
    void describeFallsBackToClassName() {
        assertEquals("java.lang.IllegalStateException", AbstractRulesEngine.describe(new IllegalStateException()));
        assertEquals("boom", AbstractRulesEngine.describe(new IllegalStateException("boom")));
    }
}
