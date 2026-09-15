package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RulesEngineBuilder checks its settings, and a built engine keeps them")
class EngineBuilderSettingsTest {

    private static RulesEngineBuilder<Map<String, Object>> builder() {
        return RulesEngineBuilder.firstMatch(HashMap::new);
    }

    private static Rule rule(String name, String language, String condition, String action) {
        return Rule.builder().ruleName(name).language(language).condition(condition).action(action).build();
    }

    private static FactMap<Object> fact(String name, Object value) {
        FactMap<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    /** A listener that records the names of the rules it's told were evaluated, prefixed with its own name. */
    private record Recording(String name, List<String> calls) implements RuleListener {
        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matched) {
            calls.add(name + ":" + rule.getRuleName());
        }
    }

    @Test
    @DisplayName("firstMatch and allMatches reject a null output supplier at once")
    void nullOutputFactory() {
        assertEquals("outputFactory must not be null",
                assertThrows(NullPointerException.class, () -> RulesEngineBuilder.firstMatch(null)).getMessage());
        assertEquals("outputFactory must not be null",
                assertThrows(NullPointerException.class, () -> RulesEngineBuilder.allMatches(null)).getMessage());
    }

    @Test
    @DisplayName("allMatches fires every matching rule, and firstMatch only the highest-priority one")
    void hitPolicies() {
        List<Rule> rules = List.of(
                Rule.builder().ruleName("high").priority(2).condition("true").action("output.put('high', 1)").build(),
                Rule.builder().ruleName("low").priority(1).condition("true").action("output.put('low', 1)").build());
        RulesEngine<Map<String, Object>> all = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        RulesEngine<Map<String, Object>> first = builder().build();
        all.load(rules);
        first.load(rules);

        assertEquals(Map.of("high", 1, "low", 1), all.run(new FactMap<>()));
        assertEquals(Map.of("high", 1), first.run(new FactMap<>()));
    }

    @Nested
    @DisplayName("languages")
    class Languages {

        @Test
        @DisplayName("language() rejects a null language, a null or blank name, and a second language with the same name")
        void languageChecked() {
            assertEquals("language must not be null",
                    assertThrows(NullPointerException.class, () -> builder().language(null)).getMessage());
            for (String name : Arrays.asList(null, " ")) {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> builder().language(new ToyExpressionLanguage(name)));
                assertEquals("An expression language's name must not be null or blank: "
                        + ToyExpressionLanguage.class.getName(), ex.getMessage());
            }
            RulesEngineBuilder<Map<String, Object>> builder = builder().language(new ToyExpressionLanguage());
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> builder.language(new ToyExpressionLanguage()));
            assertEquals("Two expression languages are named 'toy': " + ToyExpressionLanguage.class.getName() + " and "
                    + ToyExpressionLanguage.class.getName(), ex.getMessage());
        }

        @Test
        @DisplayName("an engine without language() finds MVEL, which is then the language of rules without one")
        void discoveredMvelIsTheDefault() {
            RulesEngine<Map<String, Object>> engine = builder().build();
            engine.load(List.of(rule("r", null, "true", "output.put('k', 1)")));

            assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
        }

        @Test
        @DisplayName("an engine given languages has only those: a rule without a language is written in the only one")
        void givenLanguagesReplaceDiscovery() {
            RulesEngine<Map<String, Object>> engine = builder().language(new ToyExpressionLanguage()).build();
            engine.load(List.of(rule("r", null, "true", "put k 1")));

            assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
            RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                    () -> engine.load(List.of(rule("m", "mvel", "true", "output.put('k', 1)"))));
            assertEquals("Rule 'm' is written in 'mvel', which isn't one of the engine's expression languages: [toy]",
                    ex.getMessage());
        }

        @Test
        @DisplayName("a rule without a language on an engine given another language isn't compiled as MVEL")
        void noImplicitMvel() {
            RulesEngine<Map<String, Object>> engine = builder().language(new ToyExpressionLanguage()).build();

            RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(List.of(
                    rule("sneaky", null, "true", "output.put('home', System.getProperty('user.home'))"))));
            assertTrue(ex.getMessage().startsWith("Action for rule 'sneaky' failed to compile: "), ex.getMessage());
        }

        @Test
        @DisplayName("with several languages, defaultLanguage() names the language of rules without one")
        void defaultLanguageChosen() {
            RulesEngine<Map<String, Object>> engine = builder().language(new MvelExpressionLanguage())
                    .language(new ToyExpressionLanguage()).defaultLanguage(ToyExpressionLanguage.LANGUAGE_NAME).build();
            engine.load(List.of(rule("toy", null, "true", "put k 1"),
                    rule("mvel", "mvel", "true", "output.put('m', 2)")));

            assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
        }

        @Test
        @DisplayName("build() fails for several languages without a default, and for a default the engine doesn't have")
        void defaultLanguageRequired() {
            RulesEngineBuilder<Map<String, Object>> builder = builder().language(new MvelExpressionLanguage())
                    .language(new ToyExpressionLanguage());

            assertEquals("The engine has several expression languages, [mvel, toy], so name the language of rules "
                    + "without one with defaultLanguage()",
                    assertThrows(IllegalStateException.class, builder::build).getMessage());
            builder.defaultLanguage("nope");
            assertEquals("The default language 'nope' isn't one of the engine's expression languages: [mvel, toy]",
                    assertThrows(IllegalStateException.class, builder::build).getMessage());
            assertEquals("name must not be null",
                    assertThrows(NullPointerException.class, () -> builder.defaultLanguage(null)).getMessage());
        }

        @Test
        @DisplayName("an empty rule list checks fact names against the default language, not against MVEL")
        void emptyListUsesDefaultLanguage() {
            RulesEngine<Map<String, Object>> toy = builder().language(new ToyExpressionLanguage()).build();
            RulesEngine<Map<String, Object>> mvel = builder().build();
            toy.load(List.of());
            mvel.load(List.of());

            assertNull(toy.run(fact("empty", 1)));
            assertThrows(IllegalArgumentException.class, () -> mvel.run(fact("empty", 1)));
        }
    }

    @Nested
    @DisplayName("imports")
    class Imports {

        @Test
        @DisplayName("imports are resolved by build(), so a bad one fails building the engine")
        void resolvedAtBuild() {
            RulesEngineBuilder<Map<String, Object>> builder = builder().imports("not a package!");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals("'not a package!' is neither a class nor a valid package name", ex.getMessage());
        }

        @Test
        @DisplayName("rules compile with the imports, given as strings or as a collection")
        void rulesUseImports() {
            RulesEngine<Map<String, Object>> engine = builder().imports("java.time.LocalDate")
                    .imports(List.of("java.util")).build();
            engine.load(List.of(rule("r", null, "LocalDate.of(2020, 1, 1).getYear() == 2020 && Objects.nonNull(1)",
                    "output.put('k', 1)")));

            assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
        }

        @Test
        @DisplayName("imports() rejects null, and a null element, adding nothing")
        void nullRejected() {
            RulesEngineBuilder<Map<String, Object>> builder = builder();

            assertEquals("names must not be null", assertThrows(NullPointerException.class,
                    () -> builder.imports((String[]) null)).getMessage());
            assertEquals("names must not be null", assertThrows(NullPointerException.class,
                    () -> builder.imports((Collection<String>) null)).getMessage());
            assertEquals("names must not contain null", assertThrows(NullPointerException.class,
                    () -> builder.imports(Arrays.asList("java.util", null))).getMessage());
            RulesEngine<Map<String, Object>> engine = builder.build();
            engine.load(List.of(rule("r", null, "true", "output.put('k', Objects.nonNull(1))")));
            assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>()));
        }
    }

    @Nested
    @DisplayName("listeners")
    class Listeners {

        @Test
        @DisplayName("listeners are called in the order they were added")
        void order() {
            List<String> calls = new ArrayList<>();
            RulesEngine<Map<String, Object>> engine = builder().listener(new Recording("a", calls))
                    .listeners(List.of(new Recording("b", calls), new Recording("c", calls))).build();
            engine.load(List.of(rule("r", null, "true", "output.put('k', 1)")));

            engine.run(new FactMap<>());

            assertEquals(List.of("a:r", "b:r", "c:r"), calls);
        }

        @Test
        @DisplayName("listener() and listeners() reject null, and a collection with a null adds nothing")
        void nullRejected() {
            List<String> calls = new ArrayList<>();
            RulesEngineBuilder<Map<String, Object>> builder = builder();

            assertEquals("listener must not be null",
                    assertThrows(NullPointerException.class, () -> builder.listener(null)).getMessage());
            assertEquals("listeners must not be null",
                    assertThrows(NullPointerException.class, () -> builder.listeners(null)).getMessage());
            assertEquals("listeners must not contain null", assertThrows(NullPointerException.class,
                    () -> builder.listeners(Arrays.asList(new Recording("a", calls), null))).getMessage());
            RulesEngine<Map<String, Object>> engine = builder.build();
            engine.load(List.of(rule("r", null, "true", "output.put('k', 1)")));
            engine.run(new FactMap<>());
            assertEquals(List.of(), calls);
        }

        @Test
        @DisplayName("a listener added to the builder after build() doesn't reach the engine already built")
        void fixedAtBuild() {
            List<String> calls = new ArrayList<>();
            RulesEngineBuilder<Map<String, Object>> builder = builder();
            RulesEngine<Map<String, Object>> engine = builder.build();
            builder.listener(new Recording("late", calls));
            engine.load(List.of(rule("r", null, "true", "output.put('k', 1)")));

            engine.run(new FactMap<>());

            assertEquals(List.of(), calls);
        }
    }

    @Test
    @DisplayName("maxCopies() must be at least 1")
    void maxCopiesChecked() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> builder().maxCopies(0));
        assertEquals("maxCopies must be at least 1, but was 0", ex.getMessage());
        RulesEngine<Map<String, Object>> engine = builder().maxCopies(1).build();
        engine.load(List.of(rule("r", null, "true", "output.put('k', 1)")));
        assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("build() can be called again, and each engine has its own rules")
    void buildTwice() {
        RulesEngineBuilder<Map<String, Object>> builder = builder();
        RulesEngine<Map<String, Object>> loaded = builder.build();
        RulesEngine<Map<String, Object>> other = builder.build();
        loaded.load(List.of(rule("r", null, "true", "output.put('k', 1)")));

        assertNotSame(loaded, other);
        assertEquals(Map.of("k", 1), loaded.run(new FactMap<>()));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> other.run(new FactMap<>()));
        assertEquals("load() must be called before run()", ex.getMessage());
    }
}
