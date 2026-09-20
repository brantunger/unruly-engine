package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
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

    private static Rule rule(String name, String language, String condition, String action) {
        return Rule.builder().ruleName(name).language(language).condition(condition).action(action).build();
    }

    /** An engine with exactly the given languages. */
    private static StatefulRulesEngine<Map<String, Object>> engine(ExpressionLanguage... languages) {
        return TestEngines.allMatches(HashMap::new, builder -> {
            for (ExpressionLanguage language : languages) {
                builder.language(language);
            }
            return builder;
        });
    }

    /** An engine with MVEL, the language of rules without one, and the given languages. */
    private static StatefulRulesEngine<Map<String, Object>> withMvel(ExpressionLanguage... languages) {
        return TestEngines.allMatches(HashMap::new, builder -> {
            builder.language(new MvelExpressionLanguage()).defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME);
            for (ExpressionLanguage language : languages) {
                builder.language(language);
            }
            return builder;
        });
    }

    /** A language that rejects every fact name, saying which language rejected it. */
    private static ExpressionLanguage rejectingLanguage(String languageName) {
        return StubExpressionLanguage.named(languageName).checkFactName(name -> {
            throw new IllegalArgumentException("rejected by " + languageName);
        });
    }

    @ParameterizedTest(name = "{0} first")
    @ValueSource(strings = {"a", "b"})
    @DisplayName("fact names are checked by the languages in the order the rules use them, highest priority first")
    void factNamesCheckedInOrderOfUse(String first) {
        String second = "a".equals(first) ? "b" : "a";
        StatefulRulesEngine<Map<String, Object>> engine = withMvel(rejectingLanguage("a"), rejectingLanguage("b"));
        engine.load(List.of(
                Rule.builder().ruleName("low").language(second).priority(1).condition("c").action("a").build(),
                Rule.builder().ruleName("high").language(first).priority(2).condition("c").action("a").build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("x", 1))));

        assertEquals("rejected by " + first, ex.getMessage());
    }

    @Test
    @DisplayName("one rule list can mix MVEL rules, with or without a language, and rules in another language")
    void mixedLanguages() {
        StatefulRulesEngine<Map<String, Object>> engine = withMvel(new ToyExpressionLanguage());
        engine.load(List.of(
                rule("default", null, "x == 1", "output.put('default', x)"),
                rule("mvel", "mvel", "x == 1", "output.put('mvel', x)"),
                rule("toy", "toy", "x == 1", "put toy x")));

        assertEquals(Map.of("default", 1, "mvel", 1, "toy", 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
        assertNull(engine.run(new FactMap<>(new Fact<>("x", 2))));
    }

    @Test
    @DisplayName("a rule in a language the engine doesn't have is rejected, naming the rule and the language")
    void unknownLanguage() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new);
        List<Rule> rules = List.of(rule("prime-rate", "cel", "true", "1"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("Rule 'prime-rate' is written in 'cel', which isn't one of the engine's expression languages: "
                + "[mvel]", ex.getMessage());
    }

    @Test
    @DisplayName("the message for an unknown language lists every language the engine has, sorted")
    void unknownLanguageListsLanguagesSorted() {
        StatefulRulesEngine<Map<String, Object>> engine = withMvel(new ToyExpressionLanguage("zeta"),
                new ToyExpressionLanguage("alpha"), new ToyExpressionLanguage("beta"));
        List<Rule> rules = List.of(rule("prime-rate", "cel", "true", "1"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("Rule 'prime-rate' is written in 'cel', which isn't one of the engine's expression languages: "
                + "[alpha, beta, mvel, zeta]", ex.getMessage());
    }

    @Test
    @DisplayName("an engine given only another language compiles rules without a language in it, not in MVEL")
    void onlyLanguageIsTheDefault() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(new ToyExpressionLanguage());
        engine.load(List.of(rule("r", null, "x == 1", "put k x")));

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
        List<Rule> mvel = List.of(rule("m", null, "true", "output.put('home', System.getProperty('user.home'))"));
        assertThrows(RuleCompilationException.class, () -> engine.load(mvel));
    }

    @Test
    @DisplayName("defaultLanguage() chooses the language of rules without one")
    void chosenDefaultLanguage() {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(new MvelExpressionLanguage()).language(new ToyExpressionLanguage()).defaultLanguage("toy"));
        engine.load(List.of(rule("r", null, "x == 1", "put k x")));

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
    }

    @Test
    @DisplayName("with an empty rule list, fact names are checked against the default language")
    void emptyRuleListChecksDefaultLanguage() {
        StatefulRulesEngine<Map<String, Object>> toy = engine(new ToyExpressionLanguage());
        toy.load(List.of());
        StatefulRulesEngine<Map<String, Object>> mvel = withMvel(new ToyExpressionLanguage());
        mvel.load(List.of());

        assertNull(toy.run(new FactMap<>(new Fact<>("empty", 1))), "the toy language accepts 'empty'");
        assertThrows(IllegalArgumentException.class, () -> mvel.run(new FactMap<>(new Fact<>("empty", 1))),
                "MVEL reserves 'empty'");
    }

    @Test
    @DisplayName("an engine with several languages and no default language isn't built")
    void severalLanguagesWithoutDefault() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new ToyExpressionLanguage("zeta")).language(new ToyExpressionLanguage("alpha"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);

        assertEquals("The engine has several expression languages, [alpha, zeta], so name the language of rules "
                + "without one with defaultLanguage()", ex.getMessage());
    }

    @Test
    @DisplayName("a default language the engine doesn't have isn't accepted")
    void unknownDefaultLanguage() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new ToyExpressionLanguage()).defaultLanguage("cel");

        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);

        assertEquals("The default language 'cel' isn't one of the engine's expression languages: [toy]",
                ex.getMessage());
    }

    @Test
    @DisplayName("language(null) names the argument")
    void nullLanguage() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        NullPointerException ex = assertThrows(NullPointerException.class, () -> builder.language(null));

        assertEquals("language must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("language() returns the builder, so calls can be chained")
    void languageReturnsBuilder() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        assertSame(builder, builder.language(new ToyExpressionLanguage()));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "  "})
    @DisplayName("a language without a name is rejected")
    void unnamedLanguage(String name) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);
        ToyExpressionLanguage unnamed = new ToyExpressionLanguage(name);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> builder.language(unnamed));

        assertEquals("An expression language's name must not be null or blank: " + ToyExpressionLanguage.class.getName(),
                ex.getMessage());
    }

    @Test
    @DisplayName("two languages with the same name are rejected, naming both")
    void sameNameTwice() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new ToyExpressionLanguage("mvel"));
        MvelExpressionLanguage mvel = new MvelExpressionLanguage();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> builder.language(mvel));

        assertEquals("Two expression languages are named 'mvel': " + ToyExpressionLanguage.class.getName() + " and "
                + MvelExpressionLanguage.class.getName(), ex.getMessage());
    }

    @Test
    @DisplayName("fact names are only checked against the languages the rules use")
    void factNamesCheckedByLanguagesInUse() {
        StatefulRulesEngine<Map<String, Object>> engine = withMvel(new ToyExpressionLanguage());
        engine.load(List.of(rule("toy", "toy", "true", "put k empty")));

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>(new Fact<>("empty", 1))),
                "MVEL reserves 'empty', but no rule is MVEL");

        engine.load(List.of(rule("toy", "toy", "true", "put k 1"), rule("mvel", null, "true", "1")));

        assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(new Fact<>("empty", 1))));
    }

    @Test
    @DisplayName("fact names are checked by every language in use, whichever language's rule runs first")
    void factNamesCheckedByEveryLanguageInUse() {
        StatefulRulesEngine<Map<String, Object>> engine = withMvel(new ToyExpressionLanguage());
        engine.load(List.of(
                Rule.builder().ruleName("mvel").priority(2).condition("true").action("1").build(),
                Rule.builder().ruleName("toy").language("toy").priority(1).condition("true").action("put k 1")
                        .build()));

        assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(new Fact<>("empty", 1))),
                "MVEL reserves 'empty'");
    }

    @Test
    @DisplayName("listeners and the loaded rule have the rule's language")
    void languageInListenerCallbacks() {
        List<String> languages = new CopyOnWriteArrayList<>();
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(new ToyExpressionLanguage())
                .listener(new RuleListener() {
                    @Override
                    public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                        languages.add(rule.getLanguage());
                    }
                }));
        Rule rule = rule("toy", "toy", "true", "put k 1");
        engine.load(new ArrayList<>(List.of(rule)));

        engine.run(new FactMap<>());

        assertEquals(List.of("toy"), languages);
        assertEquals("toy", engine.getCompiledRules().get(0).rule().getLanguage());
    }

    @Test
    @DisplayName("an action the language rejects is reported for the action, with the rejection as its cause")
    void rejectedAction() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(new ToyExpressionLanguage());
        List<Rule> rules = List.of(rule("r", "toy", "true", "output = 1"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("Action for rule 'r' assigns output, which an action can't replace", ex.getMessage());
        assertInstanceOf(InvalidExpressionException.class, ex.getCause());
    }

    @Test
    @DisplayName("an OutOfMemoryError inside an exception a language's compiler throws is rethrown by load")
    void fatalErrorWhileCompiling() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        StatefulRulesEngine<Map<String, Object>> engine = engine(new ExpressionLanguage() {
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

        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.load(rules)));
    }
}
