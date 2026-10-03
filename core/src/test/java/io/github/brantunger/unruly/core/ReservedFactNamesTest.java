package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ForwardingExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #468: each language says which fact names it reserves, with {@link ExpressionLanguage#reservedFactNames()}, rather
 * than the engine reserving {@code output} for every language. A run's facts reach every rule, so the engine rejects
 * a name any of its languages reserves, when it's built for a declared fact and when it runs for a supplied one.
 */
@DisplayName("each language reserves its own fact names, and the engine rejects any of them (#468)")
class ReservedFactNamesTest {

    /** A toy language that reserves {@code reserved}, as it returns it each time it's asked. */
    private static ExpressionLanguage reserving(String name, Set<String> reserved) {
        return new ForwardingExpressionLanguage(new ToyExpressionLanguage(name)) {
            @Override
            public Set<String> reservedFactNames() {
                return reserved;
            }
        };
    }

    private static Rule rule(String language, String action) {
        return Rule.builder().ruleName("r").language(language).condition("true").action(action).build();
    }

    @Test
    @DisplayName("a language that reserves no name has a fact named output declared and supplied like any other")
    void nothingReserved() {
        List<CompileContext> contexts = new ArrayList<>();
        ExpressionLanguage language = new ForwardingExpressionLanguage(reserving("toy", Set.of())) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                contexts.add(context);
                return super.newCompiler(context);
            }
        };
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(language).fact("output", String.class).build()) {
            engine.load(List.of(rule("toy", "put seen output")));

            assertEquals(Map.of("seen", "x"), engine.run(new FactMap<>(new Fact<>("output", "x"))));
            assertEquals(Map.of("output", String.class), contexts.get(0).declaredFacts());
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(new FactMap<>(new Fact<>("output", 1))));
            assertEquals("Fact 'output' was declared as java.lang.String, but the run supplied a java.lang.Integer",
                    thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a name one language reserves is rejected at build and at run, in a rule of another language too")
    void reservedByOneLanguage() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(reserving("a", Set.of("ctx"))).language(reserving("b", Set.of()))
                .defaultLanguage("b");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.fact("ctx", String.class).build());
        assertEquals("'ctx' is reserved by the 'a' expression language and cannot be declared as a fact",
                thrown.getMessage());
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(reserving("a", Set.of("ctx"))).language(reserving("b", Set.of()))
                .defaultLanguage("b").build()) {
            // Only b's rule is loaded, and b reserves nothing: a's rules could still be loaded later, with the facts.
            engine.load(List.of(rule("b", "put seen output")));

            thrown = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(new FactMap<>(new Fact<>("ctx", 1))));
            assertEquals("'ctx' is reserved by the 'a' expression language and cannot be used as a fact name",
                    thrown.getMessage());
            // Neither language reserves output.
            assertEquals(Map.of("seen", 1), engine.run(new FactMap<>(new Fact<>("output", 1))));
        }
    }

    @Test
    @DisplayName("a name several languages reserve is reported as reserved by the first of them by name")
    void reservedBySeveral() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(reserving("zed", Set.of("ctx"))).language(reserving("alpha", Set.of("ctx")))
                .defaultLanguage("zed").fact("ctx", String.class);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals("'ctx' is reserved by the 'alpha' expression language and cannot be declared as a fact",
                thrown.getMessage());
    }

    @Test
    @DisplayName("a language whose reservedFactNames() returns null, or a set holding null, fails build(), named")
    void nullAnswerFailsBuild() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                        .language(reserving("broken", null)).build());
        assertEquals("The 'broken' expression language returned null from reservedFactNames()", thrown.getMessage());

        Set<String> withNull = new HashSet<>();
        withNull.add(null);
        thrown = assertThrows(IllegalStateException.class,
                () -> RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                        .language(reserving("broken", withNull)).build());
        assertEquals("The 'broken' expression language returned a null name from reservedFactNames()",
                thrown.getMessage());
    }

    @Test
    @DisplayName("the engine keeps a copy of the names, so a language that changes its set later changes nothing")
    void laterChangeIgnored() {
        Set<String> reserved = new HashSet<>(Set.of("ctx"));
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(reserving("toy", reserved)).build()) {
            engine.load(List.of(rule("toy", "put seen late")));
            reserved.remove("ctx");
            reserved.add("late");

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(new FactMap<>(new Fact<>("ctx", 1))));
            assertEquals("'ctx' is reserved by the 'toy' expression language and cannot be used as a fact name",
                    thrown.getMessage());
            assertEquals(Map.of("seen", 1), engine.run(new FactMap<>(new Fact<>("late", 1))));
        }
    }

    @Test
    @DisplayName("a language is asked for its reserved names once, when the engine is built")
    void askedOnceAtBuild() {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        ExpressionLanguage language = new ForwardingExpressionLanguage(new ToyExpressionLanguage()) {
            @Override
            public Set<String> reservedFactNames() {
                asked.add("asked");
                return super.reservedFactNames();
            }
        };
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(language).build()) {
            assertEquals(1, asked.size());
            engine.load(List.of(rule("toy", "put seen x")));
            engine.run(new FactMap<>(new Fact<>("x", 1)));

            assertEquals(1, asked.size());
        }
    }

    @Test
    @DisplayName("a compile context made outside an engine rejects output, as the default reserves it; one the engine"
            + " makes has the names the engine checked")
    void compileContextReservedNames() {
        ClassLoader loader = ReservedFactNamesTest.class.getClassLoader();
        // What the engine makes: it checked the declarations against the names when it was built.
        EngineCompileContext context = new EngineCompileContext(Set.of(), Set.of(), loader, Object.class, Map.of(),
                Map.of("output", String.class), false, true, List.of(), Set.of());
        assertEquals(Map.of("output", String.class), context.declaredFacts());
        assertEquals(Set.of(), context.reservedFactNames());
        // What the test kit makes, with the two constructors a released kit calls.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> new EngineCompileContext(
                Set.of(), Set.of(), loader, Object.class, Map.of(), Map.of("output", String.class), false, true,
                List.of()));
        assertEquals("'output' is reserved for the output object and cannot be declared as a fact",
                thrown.getMessage());
        thrown = assertThrows(IllegalArgumentException.class, () -> new EngineCompileContext(Set.of(), Set.of(),
                loader, Object.class, Map.of(), Map.of("output", String.class), false));
        assertEquals("'output' is reserved for the output object and cannot be declared as a fact",
                thrown.getMessage());
        assertEquals(Set.of("output"), new EngineCompileContext(Set.of(), Set.of(), loader).reservedFactNames());
        // A null argument is reported before the declared output, as it was while every context rejected the name.
        assertEquals("packageImports must not be null", assertThrows(NullPointerException.class,
                () -> new EngineCompileContext(null, Set.of(), loader, Object.class, Map.of(),
                        Map.of("output", String.class), false, true, List.of())).getMessage());
        assertEquals("classLoader must not be null", assertThrows(NullPointerException.class,
                () -> new EngineCompileContext(Set.of(), Set.of(), null, Object.class, Map.of(),
                        Map.of("output", String.class), false)).getMessage());
        assertEquals("declaredFacts must not be null", assertThrows(NullPointerException.class,
                () -> new EngineCompileContext(Set.of(), Set.of(), loader, Object.class, Map.of(), null, false))
                .getMessage());
        Set<String> withNull = new HashSet<>();
        withNull.add(null);
        assertEquals("reservedFactNames must not contain null", assertThrows(NullPointerException.class,
                () -> new EngineCompileContext(Set.of(), Set.of(), loader, Object.class, Map.of(), Map.of(), false,
                        true, List.of(), withNull)).getMessage());
        assertEquals("reservedFactNames must not be null", assertThrows(NullPointerException.class,
                () -> new EngineCompileContext(Set.of(), Set.of(), loader, Object.class, Map.of(), Map.of(), false,
                        true, List.of(), null)).getMessage());
    }
}
