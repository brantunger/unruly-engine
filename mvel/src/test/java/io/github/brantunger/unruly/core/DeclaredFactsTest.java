package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final Rule RULE = Rule.builder().ruleName("r").condition("true")
            .action("output.put('ok', true)").build();

    private static RulesEngine<Map<String, Object>> engine(
            java.util.function.UnaryOperator<RulesEngineBuilder<Map<String, Object>>> configuration) {
        RulesEngine<Map<String, Object>> engine =
                configuration.apply(RulesEngineBuilder.allMatches(HashMap::new)).build();
        engine.load(List.of(RULE));
        return engine;
    }

    private static FactStore<Object> facts(String name, Object value) {
        FactStore<Object> store = new FactMap<>();
        store.setValue(name, value);
        return store;
    }

    @Test
    @DisplayName("a value that isn't an instance of the declared type fails the run, naming the fact")
    void wrongType() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.run(facts("applicant", "not an applicant")));

        assertEquals("Fact 'applicant' was declared as " + Applicant.class.getName()
                + ", but the run supplied a java.lang.String", thrown.getMessage());
    }

    @Test
    @DisplayName("a value of the declared type, or a subtype, runs")
    void rightType() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Object.class)
                .fact("score", Number.class));
        FactStore<Object> store = facts("applicant", new Applicant(700));
        store.setValue("score", 12);

        assertEquals(Map.of("ok", true), engine.run(store));
    }

    @Test
    @DisplayName("a null value passes: it contradicts no declaration, and no language can tell it from an absent fact")
    void nullValue() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class));

        assertEquals(Map.of("ok", true), engine.run(facts("applicant", null)));
    }

    @Test
    @DisplayName("a declared fact the run leaves out changes nothing, unless the engine requires declared facts")
    void missingWithoutRequiring() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class));

        assertEquals(Map.of("ok", true), engine.run(facts("other", 1)));
    }

    @Test
    @DisplayName("with requireDeclaredFacts, a fact nobody declared fails the run")
    void undeclaredWhenRequired() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder.fact("applicant", Applicant.class)
                .requireDeclaredFacts());
        FactStore<Object> store = facts("applicant", new Applicant(700));
        store.setValue("sneaky", 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> engine.run(store));

        assertEquals("Fact 'sneaky' wasn't declared, and this engine was built with requireDeclaredFacts()",
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
    @DisplayName("facts(map) declares several at once, and declaring one twice keeps the last type")
    void declaringSeveral() {
        RulesEngine<Map<String, Object>> engine = engine(builder -> builder
                .facts(Map.of("a", Integer.class, "b", String.class))
                .fact("a", String.class));
        FactStore<Object> store = facts("a", "now a string");
        store.setValue("b", "b");

        assertEquals(Map.of("ok", true), engine.run(store));
    }

    @Test
    @DisplayName("a null name or type is rejected when the engine is built")
    void nullArguments() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        assertThrows(NullPointerException.class, () -> builder.fact(null, Applicant.class));
        assertThrows(NullPointerException.class, () -> builder.fact("a", null));
        assertThrows(NullPointerException.class, () -> builder.facts(null));
    }
}
