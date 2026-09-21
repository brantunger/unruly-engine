package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the documented tie-break: equal priorities keep their order from the rule list. */
@DisplayName("equal priorities")
class PriorityTieTest {

    /**
     * A language whose rules all match, and whose action records its rule as the last one to fire and appends the
     * rule's name to {@code order}. An action's text is the name of the rule it belongs to.
     */
    private static final ExpressionLanguage WINNER = new StubExpressionLanguage()
            .compileAction(expression -> (context, session) -> {
                Map<String, Object> output = asMap(context.output());
                output.put("winner", expression.text());
                output.merge("order", expression.text(), (fired, name) -> "" + fired + name);
                return ActionResult.done();
            });

    /** The output object, which every engine here builds with {@code HashMap::new}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object output) {
        return (Map<String, Object>) output;
    }

    private static Rule rule(String name, Integer priority) {
        return Rule.builder()
                .ruleName(name)
                .priority(priority)
                .condition("true")
                .action(name)
                .build();
    }

    @Test
    @DisplayName("a first-match run fires the first-listed of the top-priority rules")
    void statelessPicksFirstListed() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.language(WINNER));

        engine.load(List.of(rule("low", 1), rule("a", 5), rule("b", 5)));
        assertEquals("a", engine.run(new FactMap<>()).get("winner"));

        engine.load(List.of(rule("low", 1), rule("b", 5), rule("a", 5)));
        assertEquals("b", engine.run(new FactMap<>()).get("winner"));
    }

    @Test
    @DisplayName("an all-matches run fires equal priorities in list order, null priorities last")
    void statefulKeepsListOrder() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.language(WINNER));

        engine.load(List.of(rule("n", null), rule("c", 5), rule("a", 5), rule("b", 5)));

        assertEquals("cabn", engine.run(new FactMap<>()).get("order"));
    }
}
