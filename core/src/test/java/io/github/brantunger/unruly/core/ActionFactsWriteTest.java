package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an action can't write to the facts, and is told why")
class ActionFactsWriteTest {

    /**
     * A language whose condition is always true. An action {@code write} puts 99 into the facts as {@code x}; any other
     * action copies fact {@code x} to the output.
     */
    private static final ExpressionLanguage WRITER = StubExpressionLanguage.named("writer")
            .compileAction(expression -> "write".equals(expression.text())
                    ? (action, session) -> {
                        action.facts().put("x", 99);
                        return ActionResult.done();
                    }
                    : (action, session) -> {
                        asMap(action.output()).put("x", action.facts().get("x"));
                        return ActionResult.done();
                    });

    /** The output object, which every engine here builds with {@code HashMap::new}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object output) {
        return (Map<String, Object>) output;
    }

    private static Rule rule(String name, int priority, String action) {
        return Rule.builder().ruleName(name).language("writer").priority(priority).condition("c").action(action)
                .build();
    }

    private static FactStore<Object> x(int value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", value);
        return facts;
    }

    @Test
    @DisplayName("a write fails the action with a message that explains the rule")
    void writeExplained() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.language(WRITER));
        engine.load(List.of(rule("act", 1, "write")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(x(1)));

        assertEquals("Failed to execute action for rule 'act': The facts passed to an action are read-only; 'x' can't "
                + "be changed. Put the result in the output object instead.", ex.getMessage());
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }

    @Test
    @DisplayName("a rule that writes a fact fails the run, so no later rule in the run sees the write")
    void laterRuleNeverSeesWrite() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.language(WRITER));
        engine.load(List.of(rule("writer", 2, "write"), rule("reader", 1, "read")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(x(1)),
                "a writable map would let the reader output x=99");

        assertTrue(ex.getMessage().startsWith("Failed to execute action for rule 'writer': "), ex.getMessage());
    }
}
