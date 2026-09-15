package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("each rule is compiled by the expression language it names")
class ExpressionLanguageRegistrationTest {

    private final StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);

    private static Rule rule(String name, String language, String condition, String action) {
        return Rule.builder().ruleName(name).language(language).condition(condition).action(action).build();
    }

    private static FactStore<Object> fact(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    /** A language that rejects every fact name, saying which language rejected it. */
    private static ExpressionLanguage rejectingLanguage(String languageName) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return languageName;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (action, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }

                    @Override
                    public void checkFactName(String name) {
                        throw new IllegalArgumentException("rejected by " + languageName);
                    }
                };
            }
        };
    }

    @ParameterizedTest(name = "{0} first")
    @ValueSource(strings = {"a", "b"})
    @DisplayName("fact names are checked by the languages in the order the rules use them, highest priority first")
    void factNamesCheckedInOrderOfUse(String first) {
        String second = "a".equals(first) ? "b" : "a";
        engine.registerLanguage(rejectingLanguage("a"));
        engine.registerLanguage(rejectingLanguage("b"));
        engine.setRuleList(List.of(
                Rule.builder().ruleName("low").language(second).priority(1).condition("c").action("a").build(),
                Rule.builder().ruleName("high").language(first).priority(2).condition("c").action("a").build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.run(fact("x", 1)));

        assertEquals("rejected by " + first, ex.getMessage());
    }

    @Test
    @DisplayName("a language registered while a rule list compiles is used from the next setRuleList, not that one")
    void languageRegisteredWhileCompiling() {
        engine.registerLanguage(new ExpressionLanguage() {
            @Override
            public String name() {
                return "first";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                engine.registerLanguage(new ToyExpressionLanguage("late"));
                return new ToyExpressionLanguage("first").newCompiler(context);
            }
        });
        List<Rule> rules = List.of(
                Rule.builder().ruleName("early").language("first").priority(2).condition("true").action("put k 1")
                        .build(),
                Rule.builder().ruleName("later").language("late").priority(1).condition("true").action("put k 2")
                        .build());

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertEquals("Rule 'later' is written in 'late', which isn't a registered expression language. "
                + "Registered languages: [first, mvel]", ex.getMessage());
        engine.setRuleList(rules);
        assertEquals(Map.of("k", 2), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("one rule list can mix MVEL rules, with or without a language, and rules in another language")
    void mixedLanguages() {
        engine.registerLanguage(new ToyExpressionLanguage());
        engine.setRuleList(List.of(
                rule("default", null, "x == 1", "output.put('default', x)"),
                rule("mvel", "mvel", "x == 1", "output.put('mvel', x)"),
                rule("toy", "toy", "x == 1", "put toy x")));

        assertEquals(Map.of("default", 1, "mvel", 1, "toy", 1), engine.run(fact("x", 1)));
        assertNull(engine.run(fact("x", 2)));
    }

    @Test
    @DisplayName("a rule in a language that isn't registered is rejected, naming the rule and the language")
    void unknownLanguage() {
        List<Rule> rules = List.of(rule("prime-rate", "cel", "true", "1"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertEquals("Rule 'prime-rate' is written in 'cel', which isn't a registered expression language. "
                + "Registered languages: [mvel]", ex.getMessage());
    }

    @Test
    @DisplayName("the message for an unregistered language lists every registered language, sorted")
    void unknownLanguageListsLanguagesSorted() {
        engine.registerLanguage(new ToyExpressionLanguage("zeta"));
        engine.registerLanguage(new ToyExpressionLanguage("alpha"));
        engine.registerLanguage(new ToyExpressionLanguage("beta"));
        List<Rule> rules = List.of(rule("prime-rate", "cel", "true", "1"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertEquals("Rule 'prime-rate' is written in 'cel', which isn't a registered expression language. "
                + "Registered languages: [alpha, beta, mvel, zeta]", ex.getMessage());
    }

    @Test
    @DisplayName("a language registered after setRuleList is used from the next setRuleList")
    void registeredForNextRuleList() {
        List<Rule> rules = List.of(rule("toy", "toy", "true", "put k 1"));
        assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        engine.registerLanguage(new ToyExpressionLanguage());
        engine.setRuleList(rules);

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a language named mvel replaces MVEL for rules without a language")
    void replaceDefaultLanguage() {
        engine.registerLanguage(new ToyExpressionLanguage("mvel"));
        engine.setRuleList(List.of(rule("r", null, "x == 1", "put k x")));

        assertEquals(Map.of("k", 1), engine.run(fact("x", 1)));
    }

    @Test
    @DisplayName("registerLanguage(null) names the argument")
    void registerNullLanguage() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.registerLanguage(null));

        assertEquals("language must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("registerLanguage returns the engine, so calls can be chained")
    void registerLanguageReturnsEngine() {
        assertSame(engine, engine.registerLanguage(new ToyExpressionLanguage()));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "  "})
    @DisplayName("a language without a name is rejected")
    void registerUnnamedLanguage(String name) {
        ToyExpressionLanguage unnamed = new ToyExpressionLanguage(name);

        assertThrows(IllegalArgumentException.class, () -> engine.registerLanguage(unnamed));
    }

    @Test
    @DisplayName("fact names are only checked against the languages the rules use")
    void factNamesCheckedByLanguagesInUse() {
        engine.registerLanguage(new ToyExpressionLanguage());
        engine.setRuleList(List.of(rule("toy", "toy", "true", "put k empty")));

        assertEquals(Map.of("k", 1), engine.run(fact("empty", 1)), "MVEL reserves 'empty', but no rule is MVEL");

        engine.setRuleList(List.of(rule("toy", "toy", "true", "put k 1"), rule("mvel", null, "true", "1")));

        assertThrows(IllegalArgumentException.class, () -> engine.run(fact("empty", 1)));
    }

    @Test
    @DisplayName("fact names are checked by every language in use, whichever language's rule runs first")
    void factNamesCheckedByEveryLanguageInUse() {
        engine.registerLanguage(new ToyExpressionLanguage());
        engine.setRuleList(List.of(
                Rule.builder().ruleName("mvel").priority(2).condition("true").action("1").build(),
                Rule.builder().ruleName("toy").language("toy").priority(1).condition("true").action("put k 1")
                        .build()));

        assertThrows(IllegalArgumentException.class, () -> engine.run(fact("empty", 1)), "MVEL reserves 'empty'");
    }

    @Test
    @DisplayName("listeners and the loaded rule have the rule's language")
    void languageInListenerCallbacks() {
        List<String> languages = new CopyOnWriteArrayList<>();
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                languages.add(rule.getLanguage());
            }
        });
        engine.registerLanguage(new ToyExpressionLanguage());
        Rule rule = rule("toy", "toy", "true", "put k 1");
        engine.setRuleList(new ArrayList<>(List.of(rule)));

        engine.run(new FactMap<>());

        assertEquals(List.of("toy"), languages);
        assertEquals("toy", engine.getCompiledRules().get(0).rule().getLanguage());
    }

    @Test
    @DisplayName("an action the language rejects is reported for the action, with the rejection as its cause")
    void rejectedAction() {
        engine.registerLanguage(new ToyExpressionLanguage());
        List<Rule> rules = List.of(rule("r", "toy", "true", "output = 1"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertEquals("Action for rule 'r' assigns output, which an action can't replace", ex.getMessage());
        assertInstanceOf(InvalidExpressionException.class, ex.getCause());
    }

    @Test
    @DisplayName("an OutOfMemoryError inside an exception a language's compiler throws is rethrown by setRuleList")
    void fatalErrorWhileCompiling() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        engine.registerLanguage(new ExpressionLanguage() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        throw new IllegalStateException("compiler failed", oom);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        throw new AssertionError("the condition fails first");
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        });
        List<Rule> rules = List.of(rule("r", "failing", "true", "1"));

        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.setRuleList(rules)));
    }
}
