package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A declaration without {@code =} passes the text check in {@code load()} and is only caught when the
 * condition runs, so the run-time message has to describe declarations, not just assignments.
 */
@DisplayName("a declaration in a condition gets an error that says so")
class ConditionDeclarationMessageTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "int y; y == 0  | y",
            "String s; true | s",
    })
    void declarationMessage(String condition, String variable) {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new);
        engine.load(List.of(Rule.builder().ruleName("declares").condition(condition)
                .action("output.put('k', 1)").build()));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("rule 'declares'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Cannot assign or declare '" + variable + "' in a condition"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("move variables and functions into the action"), ex.getMessage());
    }
}
