package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("assignments in conditions are rejected at setRuleList()")
class ConditionAssignmentRejectionTest {

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).priority(1).condition(condition).action(action).build();
    }

    private static FactStore<Object> claim(Map<String, Object> claim) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("claim", claim);
        return facts;
    }

    @Test
    @DisplayName("a property assignment is rejected before it can change the fact")
    void propertyAssignmentRejected() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(
                List.of(rule("typo", "claim.approved = true", "output.put('fired', true)"))));

        assertEquals("Condition for rule 'typo' contains an assignment ('=' at position 15). "
                + "Conditions can't change facts or declare variables; use == to compare.", ex.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "claim.amount += 5; true",
            "claim.count++; true",
            "claim['status'] = 'DENIED'; true",
            "claim.check(claim.approved = true)",
            "with (claim) { status = 'X' }; true",
            "def f() { true }; f()",
            "if (true) { claim.a = 1 }; true",
    })
    void otherWritesRejected(String condition) {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        assertThrows(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("writes", condition, "output.put('k', 1)"))));
    }

    @Test
    @DisplayName("import_static is rejected with its own explanation")
    void staticImportRejected() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(
                List.of(rule("max", "import_static java.lang.Math.max; max(x, 1) == 5", "output.put('k', 1)"))));

        assertEquals("Condition for rule 'max' uses import_static (at position 0), which declares the method as a "
                + "variable, and conditions can't declare variables. Call the method through its class instead, such "
                + "as Math.max(a, b).", ex.getMessage());
    }

    @Test
    @DisplayName("a property named with, written on the line after the dot, is read, not rejected")
    void keywordMemberAfterLineBreak() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("fluent", "claim.\n    with == 2", "output.put('k', 1)")));

        assertEquals(Map.of("k", 1), engine.run(claim(new HashMap<>(Map.of("with", 2)))));
    }

    @Test
    @DisplayName("a rejected rule list leaves the previous rules in place")
    void previousRulesKept() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("ok", "claim.approved == true", "output.put('ok', true)")));

        assertThrows(RuleCompilationException.class, () -> engine.setRuleList(
                List.of(rule("typo", "claim.approved = true", "output.put('typo', true)"))));

        assertEquals(Map.of("ok", true), engine.run(claim(new HashMap<>(Map.of("approved", true)))));
    }

    @Test
    @DisplayName("actions may still assign local variables")
    void actionsMayAssign() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("assigns", "true", "score = 10; score += 1; output.put('score', score)")));

        assertEquals(Map.of("score", 11), engine.run(new FactMap<>()));
    }

    /** The check reads the condition's text, so it can't tell that a method call changes a fact. */
    @Test
    @DisplayName("known limitation: a write made by calling a method is not detected")
    void methodCallWriteNotDetected() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("mutates", "claim.put('status', 'DENIED') == 'OPEN'", "output.put('k', 1)")));
        Map<String, Object> claim = new HashMap<>(Map.of("status", "OPEN"));

        engine.run(claim(claim));

        assertEquals("DENIED", claim.get("status"));
    }
}
