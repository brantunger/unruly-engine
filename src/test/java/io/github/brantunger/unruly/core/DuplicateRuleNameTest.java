package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("duplicate rule names")
class DuplicateRuleNameTest {

    private static Rule rule(String name) {
        return Rule.builder().ruleName(name).condition("true").action("output.put('k', 1)").build();
    }

    @Test
    @DisplayName("setRuleList() rejects two rules with the same name")
    void duplicateNamesRejected() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("a"), rule("b"), rule("a"))));
        assertTrue(ex.getMessage().contains("Duplicate rule name 'a'"));
    }

    @Test
    @DisplayName("several unnamed rules are still allowed")
    void unnamedRulesAllowed() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        assertDoesNotThrow(() -> engine.setRuleList(List.of(rule(null), rule(null), rule("named"))));
    }
}
