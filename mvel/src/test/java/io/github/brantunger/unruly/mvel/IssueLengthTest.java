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
 * #704: so that the whole of MVEL's message, {@code failed to compile at line 1, column 8: } included, is at most 1,000
 * characters once escaped, and the engine reports it without shortening it again.
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
        // 1,000 characters, less the 39 of "failed to compile at line 1, column 8: " and the 27 of the count.
        String description = quoted.substring(0, 934) + "... (" + (quoted.length() - 934) + " more characters)";
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, description)), ex.issues());
        InvalidExpressionException cause = assertInstanceOf(InvalidExpressionException.class, ex.getCause());
        assertEquals("failed to compile at line 1, column 8: " + description, cause.getMessage());
        assertEquals(1_000, cause.getMessage().length());
        assertEquals(ex.issues(), cause.issues());
    }

    // #704: the engine cut MVEL's message again, after MVEL's own count, and wrote a second count of its own.
    @Test
    @DisplayName("MVEL's message fits the engine's limit, so the engine reports it without cutting it again")
    void messageNotCutTwice() {
        String action = "import java.util.Lisst; x = '" + "q".repeat(1_453) + "'";
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(
                List.of(Rule.builder().ruleName("r").condition("true").action(action).build())));

        String quoted = "class not found: " + action;
        // 1,000 characters, less the 39 of "failed to compile at line 1, column 8: " and the 25 of the count.
        String description = quoted.substring(0, 936) + "... (564 more characters)";
        assertEquals(1_500, quoted.length());
        assertEquals("Action for rule 'r' failed to compile at line 1, column 8: " + description, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, description)), ex.issues());
        assertEquals(1_000, ex.getCause().getMessage().length());
    }

    // #704: the engine cut the escaped message inside an escape, and counted the escapes' characters it cut.
    @Test
    @DisplayName("MVEL's message is shortened before the escapes it holds, never inside one, and counts characters")
    void escapesNotCut() {
        String action = "import java.util.Lisst; x = '" + "\u200b".repeat(400) + "'";
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(
                List.of(Rule.builder().ruleName("r").condition("true").action(action).build())));

        // 1,000 characters, less the 39 of "failed to compile at line 1, column 8: " and the 25 of the count, holds
        // the 46 before the first U+200B and 148 of them, each escaped as 6: the count is of the other 253.
        String description = "class not found: import java.util.Lisst; x = '" + "\\u200b".repeat(148)
                + "... (253 more characters)";
        assertEquals("Action for rule 'r' failed to compile at line 1, column 8: " + description, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, description)), ex.issues());
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
        assertTrue(description.endsWith("... (21383 more characters)"), description);
        assertEquals(961, description.length());
    }
}
