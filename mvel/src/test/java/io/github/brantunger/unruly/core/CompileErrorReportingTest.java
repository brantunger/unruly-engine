package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("load reports every rule that fails to compile, which expression failed, and where")
class CompileErrorReportingTest {

    private final RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
            HashMap::new).build();

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    @Test
    @DisplayName("every broken rule is reported at once, in the order the rules compile")
    void everyBrokenRuleReported() {
        List<Rule> rules = List.of(
                rule("r1", "x >= ", "output.put('k', 1)"),
                rule("ok", "true", "output.put('k', 1)"),
                rule("r2", "true", "output.put('k', "),
                rule("r3", "x == == 1", "output.put('k', 1)"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertTrue(ex.getMessage().startsWith("3 rules failed to compile: Condition for rule 'r1' "), ex.getMessage());
        assertTrue(ex.getMessage().contains("; Action for rule 'r2' "), ex.getMessage());
        assertTrue(ex.getMessage().contains("; Condition for rule 'r3' "), ex.getMessage());
        assertEquals("r1", ex.getRuleName());
    }

    @Test
    @DisplayName("a broken action is named as the action, with MVEL's description, line and column")
    void actionNamedWithPosition() {
        List<Rule> rules = List.of(rule("r2", "true", "output.put('k', "));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("Action for rule 'r2' failed to compile at line 1, column 11: unbalanced braces ( ... )",
                ex.getMessage());
        assertEquals("r2", ex.getRuleName());
    }

    @Test
    @DisplayName("an error on a later line of a condition gives that line")
    void laterLineReported() {
        List<Rule> rules = List.of(rule("r", "x ==\n  == 1", "output.put('k', 1)"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("Condition for rule 'r' failed to compile at line 2, column 6: Malformed expression",
                ex.getMessage());
    }
}
