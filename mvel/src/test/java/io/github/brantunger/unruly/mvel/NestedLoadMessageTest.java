package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #912: MVEL fits its compile errors and its rejections of an option to 1,000 characters once escaped, and the engine
 * puts words of its own around them, such as the rule's or the language's name, so the message a nested
 * {@code load()} fails with can be longer. The run around that {@code load()} cut the message where the limit fell,
 * inside one of MVEL's escapes too, leaving half of it. It now leaves out an escape the limit falls inside whole, and
 * counts the characters of the nested failure's message it left out.
 */
@DisplayName("a nested load()'s MVEL error is shortened before an escape, never inside one")
class NestedLoadMessageTest {

    private static final String OUTER_FAILURE = "Failed to execute action for rule 'outer-rule': a nested load() "
            + "failed: ";

    /** The end of a text that holds the start of an escape the engine writes and not the rest. */
    private static final Pattern HALF_AN_ESCAPE = Pattern.compile("\\\\(u[0-9a-f]{0,3})?$");

    /**
     * Runs an engine whose one rule loads {@code rules} into {@code inner}, and asserts that its message shows the
     * message {@code load()} failed with cut before an escape, never inside one, with the count of its characters left
     * out.
     */
    private static void assertCutBeforeEscape(RulesEngine<Map<String, Object>> inner, List<Rule> rules) {
        AtomicReference<String> nested = new AtomicReference<>();
        RulesEngine<Map<String, Object>> outer = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((context, session) -> {
                    try {
                        inner.load(rules);
                    } catch (RuleCompilationException e) {
                        nested.set(e.getMessage());
                        throw e;
                    }
                    return ActionResult.done();
                })).build();
        outer.load(List.of(Rule.builder().ruleName("outer-rule").condition("c").action("a").build()));

        String message = assertThrows(RuleExecutionException.class, () -> outer.run(new FactMap<>())).getMessage();

        assertTrue(message.startsWith(OUTER_FAILURE), message);
        String shown = message.substring(OUTER_FAILURE.length());
        int kept = shown.lastIndexOf("... (");
        assertTrue(kept > 994 && kept <= 1_000, shown);
        assertEquals(nested.get().substring(0, kept) + "... (" + (nested.get().length() - kept) + " more characters)",
                shown);
        assertFalse(HALF_AN_ESCAPE.matcher(shown.substring(0, kept)).find(),
                () -> "cut inside an escape: ..." + shown.substring(kept - 20));
    }

    // #704's path: MVEL's compile error fits 1,000 characters, and the rule's name in front of it pushes it past.
    @ParameterizedTest(name = "a rule name of {0} characters")
    @ValueSource(ints = {80, 81, 82, 83, 84, 85})
    @DisplayName("a nested load()'s compile error with a long rule name is cut before an escape")
    void compileError(int nameLength) {
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        Rule rule = Rule.builder().ruleName("r".repeat(nameLength)).condition("true")
                .action("import java.util.Lisst; x = '" + "\u200b".repeat(400) + "'").build();

        assertCutBeforeEscape(inner, List.of(rule));
    }

    // The language's name, 6 characters long here, is in front of MVEL's rejection, which fits 1,000 characters.
    @ParameterizedTest(name = "{0} dashes")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("a nested load()'s rejected option key, under a 6-character language name, is cut before an escape")
    void optionKey(int dashes) {
        ExpressionLanguage mvel = new MvelExpressionLanguage();
        ExpressionLanguage custom = new ExpressionLanguage() {
            @Override
            public String name() {
                return "custom";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return mvel.newCompiler(context);
            }
        };
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(custom).option("custom", "-".repeat(dashes) + "\u0001".repeat(199), "true").build();
        Rule rule = Rule.builder().ruleName("r").condition("true").action("x = 1").build();

        assertCutBeforeEscape(inner, List.of(rule));
    }
}
