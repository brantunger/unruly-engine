package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code load()} reports everything that failed: the broken rules, a language that couldn't create its compiler
 * and a declared fact name the languages reject. Before #404 and #405, the language failure was thrown at once,
 * dropping the rule failures already collected, and declared names were checked only once every rule compiled.
 */
@DisplayName("load reports a failed language and a rejected declared name together with the broken rules")
class LoadFailureCollectionTest {

    private static final Rule BROKEN = Rule.builder().ruleName("broken").priority(10)
            .condition("x == == 1").action("output.put('k', 1)").build();
    private static final Rule ALSO_BROKEN = Rule.builder().ruleName("also-broken").priority(9)
            .condition("y == == 2").action("output.put('k', 2)").build();
    private static final String LANGUAGE_FAILED = "The 'b' expression language failed to create a compiler: no engine";

    /** A language whose {@code newCompiler} counts its calls and returns what it's given. */
    private static ExpressionLanguage language(String name, AtomicInteger calls,
                                               Supplier<ExpressionCompiler> newCompiler) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                calls.incrementAndGet();
                return newCompiler.get();
            }
        };
    }

    private static Supplier<ExpressionCompiler> throwing(String message) {
        return () -> {
            throw new IllegalStateException(message);
        };
    }

    private static Rule in(String language, String name, int priority) {
        return Rule.builder().ruleName(name).language(language).priority(priority).condition("c").action("a").build();
    }

    /** An engine with MVEL as its default language and {@code extra} beside it. */
    private static StatefulRulesEngine<Map<String, Object>> withMvelAnd(ExpressionLanguage extra) {
        return TestEngines.allMatches(HashMap::new, builder -> builder
                .language(new MvelExpressionLanguage()).language(extra)
                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME));
    }

    // #404

    @Test
    @DisplayName("a language that can't create its compiler is one failure among the broken rules, in rule order")
    void failedLanguageIsReportedOnceWithTheBrokenRules() {
        AtomicInteger calls = new AtomicInteger();
        StatefulRulesEngine<Map<String, Object>> engine = withMvelAnd(language("b", calls, throwing("no engine")));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(BROKEN, in("b", "b1", 5), in("b", "b2", 1))));

        assertEquals(2, ex.failures().size(), ex.getMessage());
        assertEquals("broken", ex.failures().get(0).getRuleName());
        assertEquals(LANGUAGE_FAILED, ex.failures().get(1).getMessage());
        assertNull(ex.failures().get(1).getRuleName());
        assertTrue(ex.getMessage().startsWith("2 failures while loading the rules: Condition for rule 'broken' "),
                ex.getMessage());
        assertTrue(ex.getMessage().endsWith("; " + LANGUAGE_FAILED), ex.getMessage());
        assertEquals(1, calls.get(), "newCompiler is asked once, not once per rule written in the language");
    }

    @Test
    @DisplayName("a language that returns no compiler is collected the same way")
    void languageReturningNoCompilerIsCollected() {
        StatefulRulesEngine<Map<String, Object>> engine = withMvelAnd(language("b", new AtomicInteger(), () -> null));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(BROKEN, in("b", "b1", 5))));

        assertEquals(Arrays.asList("broken", null),
                ex.failures().stream().map(RuleCompilationException::getRuleName).toList());
        assertEquals("The 'b' expression language returned no compiler", ex.failures().get(1).getMessage());
    }

    @Test
    @DisplayName("the language failure takes the place of the first rule that needed it")
    void failedLanguageIsReportedWhereItsFirstRuleWas() {
        StatefulRulesEngine<Map<String, Object>> engine = withMvelAnd(
                language("b", new AtomicInteger(), throwing("no engine")));

        Rule later = Rule.builder().ruleName("later").priority(7).condition("y == == 2").action("a").build();

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(BROKEN, in("b", "b1", 8), later, in("b", "b2", 1))));

        assertEquals(Arrays.asList("broken", null, "later"),
                ex.failures().stream().map(RuleCompilationException::getRuleName).toList());
    }

    @Test
    @DisplayName("guard: a language failure with no broken rule is still thrown as itself, and asked once")
    void lonelyLanguageFailureIsThrownAsItself() {
        AtomicInteger calls = new AtomicInteger();
        StatefulRulesEngine<Map<String, Object>> engine = withMvelAnd(language("b", calls, throwing("no engine")));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(in("b", "b1", 5), in("b", "b2", 1))));

        assertEquals(LANGUAGE_FAILED, ex.getMessage());
        assertEquals(List.of(ex), ex.failures());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("a failed default language isn't asked again to check the declared names")
    void failedDefaultLanguageIsNotAskedAgainForDeclaredNames() {
        AtomicInteger calls = new AtomicInteger();
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(language("d", calls, throwing("no engine"))).fact("x", String.class));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(in("d", "d1", 5))));

        assertEquals("The 'd' expression language failed to create a compiler: no engine", ex.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("when rules failed, the default language isn't created only to check the declared names")
    void defaultLanguageIsNotCreatedToCheckDeclaredNamesWhenRulesFailed() {
        AtomicInteger defaultCalls = new AtomicInteger();
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(language("d", defaultCalls, throwing("default broken")))
                .language(language("b", new AtomicInteger(), throwing("no engine")))
                .defaultLanguage("d").fact("x", String.class));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(in("b", "b1", 5))));

        assertEquals(LANGUAGE_FAILED, ex.getMessage());
        assertEquals(0, defaultCalls.get(), "no rule is written in the default language, and the list isn't empty");
    }

    // #405

    @Test
    @DisplayName("a declared name the language rejects is reported after the broken rules, in the same load")
    void rejectedDeclaredNameIsReportedWithTheBrokenRules() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.fact("empty", String.class));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(BROKEN)));

        assertEquals(2, ex.failures().size(), ex.getMessage());
        assertEquals("broken", ex.failures().get(0).getRuleName());
        assertTrue(ex.failures().get(1).getMessage().startsWith("Declared fact 'empty' can't be used: "),
                ex.failures().get(1).getMessage());
        assertNull(ex.failures().get(1).getRuleName());
        assertTrue(ex.getMessage().startsWith("2 failures while loading the rules: Condition for rule 'broken' "),
                ex.getMessage());
    }

    @Test
    @DisplayName("every rejected declared name is reported, after the broken rules")
    void everyRejectedDeclaredNameIsReported() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.fact("empty", String.class).fact("x", String.class).fact("nil", String.class));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(BROKEN)));

        assertEquals(3, ex.failures().size(), ex.getMessage());
        assertTrue(ex.getMessage().startsWith("3 failures while loading the rules: "), ex.getMessage());
        assertEquals("broken", ex.failures().get(0).getRuleName());
        // The declared facts are an unordered map, so the two rejections may come in either order.
        Set<String> rejected = ex.failures().subList(1, 3).stream()
                .map(failure -> failure.getMessage().replaceAll(" can't be used: .*", ""))
                .collect(Collectors.toSet());
        assertEquals(Set.of("Declared fact 'empty'", "Declared fact 'nil'"), rejected);
    }

    @Test
    @DisplayName("guard: when every failure is a rule's, the message still counts rules")
    void ruleFailuresAloneStillCountRules() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.fact("x", String.class));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> engine.load(List.of(BROKEN, ALSO_BROKEN)));

        assertEquals(2, ex.failures().size());
        assertTrue(ex.getMessage().startsWith("2 rules failed to compile: "), ex.getMessage());
    }
}
