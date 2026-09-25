package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When several rules fail to compile, {@code load()} throws one {@link RuleCompilationException} whose message lists
 * the first failures whole, as many as fit in 1,000 characters and always the first, and counts the rest, which
 * {@link RuleCompilationException#failures()} still has.
 */
@DisplayName("the message of a load() that several rules fail")
class CombinedLoadFailureTest {

    /** A language whose every condition fails to compile, with the condition's text as the reason. */
    private static final ExpressionLanguage FAILING = new ExpressionLanguage() {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    throw new IllegalArgumentException(source.text());
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    };

    /**
     * Loads rules named {@code rule-0}, {@code rule-1} and so on, in that order, each failing with its condition.
     *
     * @param conditions The rules' conditions
     * @return What the load threw
     */
    private static RuleCompilationException loadFailing(List<String> conditions) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(FAILING).defaultLanguage("failing").build();
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            rules.add(Rule.builder().ruleName("rule-" + i).priority(conditions.size() - i).condition(conditions.get(i))
                    .action("a").build());
        }
        return assertThrows(RuleCompilationException.class, () -> engine.load(rules));
    }

    @Test
    @DisplayName("a thousand failures list the first whole, and count the rest")
    void thousandFailuresAreBounded() {
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            conditions.add("Malformed expression");
        }

        RuleCompilationException ex = loadFailing(conditions);

        String message = ex.getMessage();
        String first = ex.failures().get(0).getMessage();
        assertEquals(1_000, ex.failures().size());
        assertTrue(message.startsWith("1000 rules failed to compile: " + first + "; "), message);
        assertTrue(message.matches(".*; and \\d+ more \\(see failures\\(\\)\\)"), message);
        String listed = message.substring("1000 rules failed to compile: ".length(), message.lastIndexOf("; and "));
        assertTrue(listed.length() <= 1_000, listed.length() + " characters listed: " + message);
        // Counted by matching the failures in order, as a reason can itself hold "; ".
        StringBuilder expected = new StringBuilder(first);
        int count = 1;
        while (listed.startsWith(expected + "; " + ex.failures().get(count).getMessage())) {
            expected.append("; ").append(ex.failures().get(count).getMessage());
            count++;
        }
        assertEquals(expected.toString(), listed, "the failures listed aren't the first, whole");
        assertTrue(message.endsWith("; and " + (1_000 - count) + " more (see failures())"), message);
        String next = ex.failures().get(count).getMessage();
        assertTrue(listed.length() + 2 + next.length() > 1_000, "the next failure would have fit: " + message);
    }

    @Test
    @DisplayName("a first failure longer than the limit is listed whole, and the rest counted")
    void longFirstFailureListedWhole() {
        RuleCompilationException ex = loadFailing(List.of("x".repeat(990), "short", "short"));

        String first = ex.failures().get(0).getMessage();
        assertTrue(first.length() > 1_000, first);
        assertEquals("3 rules failed to compile: " + first + "; and 2 more (see failures())", ex.getMessage());
        assertEquals(3, ex.failures().size());
    }

    @ParameterizedTest(name = "{0} characters past the limit")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("a failure that brings the list, with its separator, to exactly the limit is listed, and one that"
            + " would bring it just past the limit is counted")
    void failureAtTheLimit(int past) {
        String prefix = "Condition for rule 'rule-0' failed to compile: ";
        String second = "y".repeat(1_000 - 2 - 2 * prefix.length() - 400 + past);

        RuleCompilationException ex = loadFailing(List.of("x".repeat(400), second, "short"));

        String first = ex.failures().get(0).getMessage();
        String next = ex.failures().get(1).getMessage();
        assertEquals(prefix + "x".repeat(400), first);
        assertEquals(1_000 + past, first.length() + 2 + next.length(), next);
        String listed = past == 0 ? first + "; " + next + "; and 1 more" : first + "; and 2 more";
        assertEquals("3 rules failed to compile: " + listed + " (see failures())", ex.getMessage());
    }

    @Test
    @DisplayName("a few short failures are all listed, with nothing counted")
    void fewFailuresAllListed() {
        RuleCompilationException ex = loadFailing(List.of("one", "two", "three"));

        assertEquals("3 rules failed to compile: " + ex.failures().get(0).getMessage() + "; "
                + ex.failures().get(1).getMessage() + "; " + ex.failures().get(2).getMessage(), ex.getMessage());
        assertTrue(ex.getMessage().contains("rule 'rule-2'"), ex.getMessage());
    }
}
