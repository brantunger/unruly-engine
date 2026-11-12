package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleCompilationException;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Declaring a fact's type tells the engine what a run's value must be, whatever language the rules are written in.
 * {@code requireDeclaredFacts()} is what makes the declarations a complete list, so a missing or unexpected fact is
 * a mistake rather than something the run is entitled to do.
 */
@DisplayName("declared facts are checked when a run supplies them")
class DeclaredFactsTest {

    /** An applicant as a record, the shape the docs use. */
    public record Applicant(int creditScore) {
    }

    private static final Rule RULE = Rule.builder().ruleName("r").condition("true").action("put ok true").build();

    // Eight names, so an order that only happens to be the declared one is rare, and each tried as the first one
    // declared, so a test of what comes first can't pass by chance: an order that doesn't follow the declarations
    // puts the same name first in nearly every rotation (#1406).
    private static final List<String> NAMES =
            List.of("applicant", "limits", "tags", "history", "order", "account", "region", "score");

    /** The eight names in the eight orders that go round from each one of them. */
    private static List<List<String>> rotations() {
        List<List<String>> rotations = new ArrayList<>();
        for (int first = 0; first < NAMES.size(); first++) {
            List<String> names = new ArrayList<>(NAMES.subList(first, NAMES.size()));
            names.addAll(NAMES.subList(0, first));
            rotations.add(names);
        }
        return rotations;
    }

    /** A builder that declares each name as a String, one by one and in the order given. */
    private static RulesEngineBuilder<Map<String, Object>> declaring(List<String> names) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);
        names.forEach(name -> builder.fact(name, String.class));
        return builder;
    }

    private static RulesEngine<Map<String, Object>> engine(
            java.util.function.UnaryOperator<RulesEngineBuilder<Map<String, Object>>> configuration) {
        return engine(RULE, configuration);
    }

    private static RulesEngine<Map<String, Object>> engine(Rule rule,
            java.util.function.UnaryOperator<RulesEngineBuilder<Map<String, Object>>> configuration) {
        RulesEngine<Map<String, Object>> engine =
                configuration.apply(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                        .language(new ToyExpressionLanguage())).build();
        engine.load(List.of(rule));
        return engine;
    }

    @Test
    @DisplayName("a value that isn't an instance of the declared type fails the run, naming the fact")
    void wrongType() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("applicant", "not an applicant"))));

        assertEquals("Fact 'applicant' was declared as " + Applicant.class.getName()
                + ", but the run supplied a java.lang.String", thrown.getMessage());
    }

    @Test
    @DisplayName("a value of the declared type, or a subtype, runs")
    void rightType() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Object.class)
                .fact("score", Number.class));
        FactStore<Object> store = new FactMap<>(new Fact<>("applicant", new Applicant(700)));
        store.setValue("score", 12);

        assertEquals(Map.of("ok", true), engine.run(store));
    }

    @Test
    @DisplayName("a null value passes: it contradicts no declaration, and no language can tell it from an absent fact")
    void nullValue() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class));

        assertEquals(Map.of("ok", true), engine.run(new FactMap<>(new Fact<>("applicant", null))));
    }

    @Test
    @DisplayName("a declared fact the run leaves out changes nothing, unless the engine requires declared facts")
    void missingWithoutRequiring() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class));

        assertEquals(Map.of("ok", true), engine.run(new FactMap<>(new Fact<>("other", 1))));
    }

    @Test
    @DisplayName("with requireDeclaredFacts, a fact nobody declared fails the run")
    void undeclaredWhenRequired() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class)
                .requireDeclaredFacts());
        FactStore<Object> store = new FactMap<>(new Fact<>("applicant", new Applicant(700)));
        store.setValue("sneaky", 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> engine.run(store));

        assertEquals("Fact 'sneaky' wasn't declared, and this engine was built with requireDeclaredFacts()",
                thrown.getMessage());
    }

    @Test
    @DisplayName("with requireDeclaredFacts, a fact nobody declared whose name ends in a no-break space fails the run, "
            + "and the message shows the space escaped, so the name doesn't look like a valid one")
    void undeclaredNameWithNoBreakSpaceEscaped() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class)
                .requireDeclaredFacts());
        FactStore<Object> store = new FactMap<>(new Fact<>("applicant", new Applicant(700)));
        store.setValue("x" + (char) 0xa0, 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> engine.run(store));

        assertEquals("Fact 'x\\u00a0' wasn't declared, and this engine was built with requireDeclaredFacts()",
                thrown.getMessage());
    }

    @Test
    @DisplayName("with requireDeclaredFacts, a declared fact the run leaves out fails the run")
    void missingWhenRequired() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class)
                .requireDeclaredFacts());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>()));

        assertEquals("Fact 'applicant' was declared, but the run didn't supply it, and this engine was built with "
                + "requireDeclaredFacts()", thrown.getMessage());
    }

    @Test
    @DisplayName("with requireDeclaredFacts, a run that leaves out every declared fact is told of the first one "
            + "declared, whichever that is (#1406)")
    void missingNamesTheFirstDeclared() {
        for (List<String> names : rotations()) {
            try (RulesEngine<Map<String, Object>> engine = declaring(names).language(new ToyExpressionLanguage())
                    .requireDeclaredFacts().build()) {
                engine.load(List.of(RULE));

                IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                        () -> engine.run(new FactMap<>()));

                assertEquals("Fact '" + names.get(0) + "' was declared, but the run didn't supply it, and this engine "
                        + "was built with requireDeclaredFacts()", thrown.getMessage());
            }
        }
    }

    /** The declared names a language is given when an engine built by {@code builder} loads a rule, in order. */
    private static List<String> namesToldToTheLanguage(RulesEngineBuilder<Map<String, Object>> builder) {
        List<String> told = new ArrayList<>();
        try (RulesEngine<Map<String, Object>> engine = builder.language(new StubExpressionLanguage()
                .onNewCompiler(context -> told.addAll(context.declaredFacts().keySet()))).build()) {
            engine.load(List.of(RULE));
        }
        return told;
    }

    @Test
    @DisplayName("a language is given the declared facts in the order they were declared, one by one or with "
            + "facts(map) (#1406)")
    void languageIsGivenTheDeclaredOrder() {
        for (List<String> names : rotations()) {
            Map<String, Class<?>> ordered = new LinkedHashMap<>();
            names.forEach(name -> ordered.put(name, String.class));

            assertEquals(names, namesToldToTheLanguage(declaring(names)));
            assertEquals(names, namesToldToTheLanguage(
                    RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).facts(ordered)));
        }
    }

    @Test
    @DisplayName("the declared facts a language is given, by an engine or by a context made outside one, throw "
            + "NullPointerException when asked for a null name or type (#1406)")
    void declaredFactsRejectANullName() {
        List<Map<String, Type>> told = new ArrayList<>();
        try (RulesEngine<Map<String, Object>> engine = declaring(NAMES).language(new StubExpressionLanguage()
                .onNewCompiler(context -> told.add(context.declaredFacts()))).build()) {
            engine.load(List.of(RULE));
        }
        told.add(new EngineCompileContext(Set.of(), Set.of(), ClassLoader.getSystemClassLoader(), Object.class,
                Map.of(), Map.of("age", int.class), true).declaredFacts());

        assertEquals(2, told.size());
        for (Map<String, Type> facts : told) {
            assertThrows(NullPointerException.class, () -> facts.get(null));
            assertThrows(NullPointerException.class, () -> facts.containsKey(null));
            assertThrows(NullPointerException.class, () -> facts.getOrDefault(null, String.class));
            assertThrows(NullPointerException.class, () -> facts.containsValue(null));
            assertNull(facts.get("nobody"));
            assertFalse(facts.containsKey("nobody"));
            assertFalse(facts.containsValue(Object.class));
        }
    }

    @Test
    @DisplayName("the declared facts a language is given, by an engine or by a context made outside one, throw "
            + "UnsupportedOperationException from every method that could change them, even one with nothing to "
            + "change (#1406)")
    void declaredFactsRejectEveryChange() {
        List<Map<String, Type>> told = new ArrayList<>();
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .fact("age", int.class).language(new StubExpressionLanguage()
                        .onNewCompiler(context -> told.add(context.declaredFacts()))).build()) {
            engine.load(List.of(RULE));
        }
        told.add(new EngineCompileContext(Set.of(), Set.of(), ClassLoader.getSystemClassLoader(), Object.class,
                Map.of(), Map.of("age", int.class), true).declaredFacts());

        assertEquals(2, told.size());
        told.forEach(DeclaredFactsTest::assertRejectsEveryChange);
    }

    private static void assertRejectsEveryChange(Map<String, Type> facts) {
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> facts.put("age", Integer.class)),
                () -> assertThrows(UnsupportedOperationException.class, () -> facts.remove("nobody")),
                () -> assertThrows(UnsupportedOperationException.class, () -> facts.remove(null)),
                () -> assertThrows(UnsupportedOperationException.class, () -> facts.remove("nobody", String.class)),
                () -> assertThrows(UnsupportedOperationException.class, () -> facts.putAll(Map.of())),
                () -> assertThrows(UnsupportedOperationException.class, facts::clear),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> facts.putIfAbsent("age", String.class)),
                () -> assertThrows(UnsupportedOperationException.class, () -> facts.replace("nobody", String.class)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> facts.replace("nobody", String.class, Long.class)),
                () -> assertThrows(UnsupportedOperationException.class, () -> facts.replaceAll((name, type) -> type)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> facts.computeIfAbsent("age", name -> String.class)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> facts.computeIfPresent("nobody", (name, type) -> type)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> facts.compute("nobody", (name, type) -> type)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> facts.merge("age", Integer.class, (old, type) -> old)));
        assertEquals(Map.of("age", Integer.class), facts);
    }

    @Test
    @DisplayName("the declared names a language can't use are listed by load() in the order they were declared "
            + "(#1406)")
    void unusableDeclaredNamesAreListedInDeclaredOrder() {
        Pattern named = Pattern.compile("Declared fact '([a-z]+)' can't be used: ");
        for (List<String> names : rotations()) {
            try (RulesEngine<Map<String, Object>> engine = declaring(names)
                    .language(new StubExpressionLanguage().checkFactName(name -> {
                        throw new IllegalArgumentException("no name is one rules can refer to");
                    })).build()) {

                RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                        () -> engine.load(List.of(RULE)));

                assertTrue(thrown.getMessage().startsWith("8 failures while loading the rules: "),
                        thrown.getMessage());
                List<String> listed = new ArrayList<>();
                Matcher matcher = named.matcher(thrown.getMessage());
                while (matcher.find()) {
                    listed.add(matcher.group(1));
                }
                assertEquals(names, listed);
            }
        }
    }

    @Test
    @DisplayName("facts(map) declares several at once, and declaring one twice keeps the last type")
    void declaringSeveral() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder
                .facts(Map.of("a", Integer.class, "b", String.class))
                .fact("a", String.class));
        FactStore<Object> store = new FactMap<>(new Fact<>("a", "now a string"));
        store.setValue("b", "b");

        assertEquals(Map.of("ok", true), engine.run(store));
        // Only the map declares b, so a run that gives it the wrong type is rejected only if the map declared it.
        store.setValue("b", 1);
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> engine.run(store));
        assertEquals("Fact 'b' was declared as java.lang.String, but the run supplied a java.lang.Integer",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a null name, type or map is rejected when it is declared")
    void nullArguments() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        assertThrows(NullPointerException.class, () -> builder.fact(null, Applicant.class));
        assertThrows(NullPointerException.class, () -> builder.fact("a", null));
        assertThrows(NullPointerException.class, () -> builder.facts(null));
    }

    // ---- #654: facts(map) declares all of the map or none of it ----

    private static RulesEngineBuilder<Map<String, Object>> requiring() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ToyExpressionLanguage()).requireDeclaredFacts();
    }

    /** A map whose valid first entry is read before its second, which facts(map) may reject. */
    private static Map<String, Class<?>> validThen(String name, Class<?> type) {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        types.put("a", String.class);
        types.put(name, type);
        return types;
    }

    /** Runs with no facts, which an engine requiring declared facts allows only if none was declared. */
    private static void assertNothingDeclared(RulesEngineBuilder<Map<String, Object>> builder) {
        try (RulesEngine<Map<String, Object>> engine = builder.build()) {
            engine.load(List.of(RULE));
            assertEquals(Map.of("ok", true), engine.run(new FactMap<>()));
        }
    }

    @Test
    @DisplayName("a map with a fact named output is declared, and build() rejects it, as the languages reserve output")
    void outputInAMapRejectedByBuild() {
        RulesEngineBuilder<Map<String, Object>> builder = requiring();

        assertSame(builder, builder.facts(validThen("output", String.class)));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals("'output' is reserved for the output object and cannot be declared as a fact",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a map with a blank name declares none of its entries")
    void rejectedBlankNameDeclaresNothing() {
        RulesEngineBuilder<Map<String, Object>> builder = requiring();

        assertThrows(IllegalArgumentException.class, () -> builder.facts(validThen(" ", String.class)));

        assertNothingDeclared(builder);
    }

    @Test
    @DisplayName("a map with a null type declares none of its entries")
    void rejectedNullTypeDeclaresNothing() {
        RulesEngineBuilder<Map<String, Object>> builder = requiring();

        assertThrows(NullPointerException.class, () -> builder.facts(validThen("b", null)));

        assertNothingDeclared(builder);
    }

    @Test
    @DisplayName("a map with a null name declares none of its entries")
    void rejectedNullNameDeclaresNothing() {
        RulesEngineBuilder<Map<String, Object>> builder = requiring();

        assertThrows(NullPointerException.class, () -> builder.facts(validThen(null, String.class)));

        assertNothingDeclared(builder);
    }

    @Test
    @DisplayName("a rejected map doesn't replace a fact declared before it")
    void rejectedMapKeepsEarlierDeclaration() {
        RulesEngineBuilder<Map<String, Object>> builder = requiring().fact("a", Integer.class);

        assertThrows(IllegalArgumentException.class, () -> builder.facts(validThen(" ", String.class)));

        try (RulesEngine<Map<String, Object>> engine = builder.build()) {
            engine.load(List.of(RULE));
            assertEquals(Map.of("ok", true), engine.run(new FactMap<>(new Fact<>("a", 1))));
        }
    }

    @Test
    @DisplayName("each of the map's own entries is checked, even one a copy of the map would merge with another")
    void everyEntryOfTheMapIsChecked() {
        RulesEngineBuilder<Map<String, Object>> builder = requiring();
        // Two entries with equal names, as an IdentityHashMap or a TreeMap with its own comparator can hold.
        Map<String, Class<?>> types = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Class<?>>> entrySet() {
                return new LinkedHashSet<>(List.of(new SimpleEntry<>("a", null),
                        new SimpleEntry<>("a", String.class)));
            }
        };

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> builder.facts(types));

        assertEquals("type must not be null", thrown.getMessage());
        assertNothingDeclared(builder);
    }

    // ---- #361: a declaration no run could satisfy fails before the first run ----

    @Test
    @DisplayName("a primitive type accepts its wrapper, so a run's boxed value passes")
    void primitiveTypeIsWrapped() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("age", int.class));

        assertEquals(Map.of("ok", true), engine.run(new FactMap<>(new Fact<>("age", 30))));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("age", "thirty"))));
        assertEquals("Fact 'age' was declared as int, but the run supplied a java.lang.String", thrown.getMessage());
    }

    @Test
    @DisplayName("declaring a fact named output is rejected by build(): the engine's languages reserve that name")
    void outputCannotBeDeclared() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new StubExpressionLanguage());

        // The languages say which names they reserve, so fact() can't know: only build() has the languages.
        assertSame(builder, builder.fact("output", String.class));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals("'output' is reserved for the output object and cannot be declared as a fact",
                thrown.getMessage());
        RulesEngineBuilder<Map<String, Object>> other = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(new StubExpressionLanguage());
        assertSame(other, other.facts(Map.of("output", String.class)));
        thrown = assertThrows(IllegalArgumentException.class, other::build);
        assertEquals("'output' is reserved for the output object and cannot be declared as a fact",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a fact whose name differs from output only in case is declared and supplied like any other")
    void outputInAnotherCaseIsAFact() {
        RulesEngine<Map<String, Object>> engine = engine(Rule.builder().ruleName("r").condition("true")
                .action("put declared OUTPUT; put supplied Output").build(),
                builder -> builder.fact("OUTPUT", String.class));
        FactStore<Object> store = new FactMap<>(new Fact<>("OUTPUT", "x"));
        store.setValue("Output", 1);

        assertEquals(Map.of("declared", "x", "supplied", 1), engine.run(store));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("OUTPUT", 1))));
        assertEquals("Fact 'OUTPUT' was declared as java.lang.String, but the run supplied a java.lang.Integer",
                thrown.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("declaring a fact with a blank name is rejected, as a blank rule name or tag is")
    void blankNameCannotBeDeclared(String name) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.fact(name, String.class));
        assertEquals("fact name must not be blank", thrown.getMessage());
        thrown = assertThrows(IllegalArgumentException.class, () -> builder.facts(Map.of(name, String.class)));
        assertEquals("fact name must not be blank", thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new EngineCompileContext(Set.of(), Set.of(),
                ClassLoader.getSystemClassLoader(), Object.class, Map.of(), Map.of(name, String.class), true));
    }

    @Test
    @DisplayName("a declaration's name is checked for null, then its type, then the name for blank and output")
    void declarationCheckOrder() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        assertEquals("name must not be null",
                assertThrows(NullPointerException.class, () -> builder.fact(null, null)).getMessage());
        assertEquals("type must not be null",
                assertThrows(NullPointerException.class, () -> builder.fact(" ", null)).getMessage());
        assertEquals("type must not be null",
                assertThrows(NullPointerException.class, () -> builder.fact("output", null)).getMessage());
        // A name with whitespace that isn't blank is the language's to check, at load().
        assertSame(builder, builder.fact(" a ", String.class));
    }

    @Test
    @DisplayName("a compile context made outside an engine, as the test kit does, wraps primitives and rejects output")
    void compileContextDeclarationsMatchAnEngine() {
        EngineCompileContext context = new EngineCompileContext(java.util.Set.of(), java.util.Set.of(),
                ClassLoader.getSystemClassLoader(), Object.class, Map.of(), Map.of("age", int.class), true);

        assertEquals(Map.of("age", Integer.class), context.declaredFacts());
        assertThrows(IllegalArgumentException.class, () -> new EngineCompileContext(java.util.Set.of(),
                java.util.Set.of(), ClassLoader.getSystemClassLoader(), Object.class, Map.of(),
                Map.of("output", String.class), true));
    }

    @Test
    @DisplayName("a declared name no language can refer to fails load(), naming the fact")
    void unusableDeclaredNameFailsLoading() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new StubExpressionLanguage().checkFactName(name -> {
                    if (!name.chars().allMatch(Character::isJavaIdentifierPart)) {
                        throw new IllegalArgumentException("'" + name + "' is not a name rules can refer to");
                    }
                }))
                .fact("not a name", String.class).build();

        RuleCompilationException thrown = assertThrows(
                RuleCompilationException.class,
                () -> engine.load(List.of(RULE)));

        assertTrue(thrown.getMessage().startsWith("Declared fact 'not a name' can't be used: "), thrown.getMessage());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }
}
