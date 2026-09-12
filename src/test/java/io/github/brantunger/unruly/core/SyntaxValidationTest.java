package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("syntax validation at setRuleList()")
class SyntaxValidationTest {

    private static Rule rule(String condition, String action) {
        return Rule.builder()
                .ruleName("syntax")
                .condition(condition)
                .action(action)
                .priority(1)
                .build();
    }

    @Test
    @DisplayName("a doubled operator in a condition is rejected at compile time")
    void doubledOperatorInConditionRejected() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("x == == 1", "output.put('k', 1)"))));
        assertTrue(ex.getMessage().contains("'syntax'"));
    }

    @Test
    @DisplayName("a doubled operator in an action is rejected at compile time")
    void doubledOperatorInActionRejected() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

        assertThrows(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("true", "x == == 1"))));
    }

    @ParameterizedTest(name = "valid expression {0} still compiles")
    @ValueSource(strings = {
            "Objects.nonNull(name) && name.startsWith('J')",
            "status in ['A', 'B']",
            "name ~= '[a-z]+'",
            "claim.?address == null",
            "var y = x + 1; y > 1",
            "($ in list if $ > 1).size() > 0",
    })
    void validConditionsStillCompile(String condition) {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.addImport("java.util");

        assertDoesNotThrow(() -> engine.setRuleList(List.of(rule(condition, "output.put('k', 1)"))));
    }

    @ParameterizedTest(name = "valid action {0} still compiles")
    @ValueSource(strings = {
            "if (x > 1) { output.put('a', 1); } else { output.put('b', 2); }",
            "foreach (i : list) { output.put(i, i); }",
            "def f(a) { a * 2 }; output.put('d', f(x))",
            "with (output) { put('a', 1), put('b', 2) }",
            "score = 10; output.put('s', score)",
    })
    void validActionsStillCompile(String action) {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

        assertDoesNotThrow(() -> engine.setRuleList(List.of(rule("true", action))));
    }

    @Test
    @DisplayName("imports added before setRuleList() are visible to the analysis pass")
    void importsVisibleToAnalysis() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.addImport("java.util");
        engine.setRuleList(List.of(rule("Objects.nonNull(name)", "output.put('k', 1)")));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("name", "n");
        assertEquals(1, engine.run(facts).get("k"));
    }

    /** MVEL's parser still accepts this; pinned so a future MVEL upgrade that catches it is noticed. */
    @Test
    @DisplayName("known limitation: a stray closing parenthesis is only reported at run()")
    void strayParenthesisOnlyFailsAtRun() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.setRuleList(List.of(rule("true)", "output.put('k', 1)")));

        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
    }
}
