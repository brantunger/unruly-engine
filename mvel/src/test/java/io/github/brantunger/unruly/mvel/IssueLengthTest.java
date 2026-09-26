package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #652: MVEL's issues were as long as the text they quote, where the engine's own message about them is shortened to
 * about 1,000 characters. Each is shortened the same way now, in the issue and the language's own exception alike.
 */
@DisplayName("MVEL's issues are shortened as the engine's messages are")
class IssueLengthTest {

    private static RuleCompilationException validated(RulesEngine<?> engine, String action) {
        return engine.validate(List.of(Rule.builder().ruleName("r").condition("true").action(action).build())).get(0);
    }

    @Test
    @DisplayName("MVEL's description that quotes the rest of a long expression is shortened")
    void longExpressionQuoted() {
        StringBuilder sum = new StringBuilder("import java.util.Lisst; x = 0");
        while (sum.length() < 100_000) {
            sum.append(" + 1");
        }
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        RuleCompilationException ex = validated(engine, sum.toString());

        String quoted = "class not found: " + sum;
        String description = quoted.substring(0, 1_000) + "... (" + (quoted.length() - 1_000) + " more characters)";
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, description)), ex.issues());
        InvalidExpressionException cause = assertInstanceOf(InvalidExpressionException.class, ex.getCause());
        assertEquals("failed to compile at line 1, column 8: " + description, cause.getMessage());
        assertEquals(ex.issues(), cause.issues());
    }

    @Test
    @DisplayName("MVEL's strong-typing error list is shortened on its one line")
    void longErrorListShortened() {
        StringBuilder news = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            news.append("new Nosuch").append(i).append("(); ");
        }
        RulesEngine<StringBuilder> engine = RulesEngineBuilder.allMatches(StringBuilder::new)
                .outputType(StringBuilder.class).fact("n", Integer.class).requireDeclaredFacts()
                .option("mvel", "strongTyping", "true").build();

        String description = validated(engine, news.toString()).issues().get(0).message();

        assertTrue(description.startsWith("(1,5) could not resolve class: Nosuch0; (1,20) could not resolve class: "
                + "Nosuch1; "), description);
        assertTrue(description.endsWith("... (21317 more characters)"), description);
        assertEquals(1_027, description.length());
    }
}
