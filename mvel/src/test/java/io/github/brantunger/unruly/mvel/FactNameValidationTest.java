package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("fact names rules can't refer to are rejected at run()")
class FactNameValidationTest {

    private static RulesEngine<Map<String, Object>> engine(String condition) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build();
        engine.load(List.of(rule(condition)));
        return engine;
    }

    private static Rule rule(String condition) {
        return Rule.builder().ruleName("r").condition(condition).action("output.put('hit', true)").build();
    }

    /**
     * An engine whose rule names the fact in a string: MVEL checks only the names its rules' text holds, wherever they
     * are, and a rule can't name a keyword such as {@code in} as a variable.
     */
    private static RulesEngine<Map<String, Object>> naming(String name) {
        return engine("'" + name + "' != null");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"my-fact", "a.b", "1x"})
    void nonIdentifiersRejected(String name) {
        RulesEngine<Map<String, Object>> engine = engine(name + " == 5");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>(name, 1))));

        assertEquals("'" + name + "' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier", ex.getMessage());
    }

    @Test
    @DisplayName("a name with a space in it is a fact's: no rule can name it, so MVEL doesn't check it")
    void nameWithASpaceNotChecked() {
        assertEquals(Map.of("hit", true), engine("true").run(new FactMap<>(new Fact<>("has space", 1))));
    }

    @Test
    @DisplayName("an empty name, which the engine rejects before asking the language, is rejected by MVEL's own check")
    void emptyNameRejectedByTheCompiler() {
        try (ExpressionCompiler compiler = new MvelExpressionLanguage().newCompiler(LanguageTestContexts.compile())) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> compiler.checkFactName(""));

            assertEquals("'' is not a valid fact name: rules can only refer to a fact named with a Java identifier",
                    ex.getMessage());
        }
    }

    @Test
    @DisplayName("a name with a letter outside the BMP is rejected, as MVEL can't read such a letter in a rule")
    void supplementaryLetterRejected() {
        // U+1D49C MATHEMATICAL SCRIPT CAPITAL A: a Java identifier, but MVEL can't read it in a rule's text.
        String name = "\uD835\uDC9C";
        RulesEngine<Map<String, Object>> engine = naming(name);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>(name, 1))));

        assertEquals("'" + name + "' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier", ex.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"empty", "nil", "null", "true", "this", "isdef", "in", "with", "var", "def", "Math",
            "String"})
    void reservedNamesRejected(String name) {
        RulesEngine<Map<String, Object>> engine = naming(name);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>(name, 5))));

        assertEquals("'" + name + "' cannot be used as a fact name: MVEL reads it as a keyword or class name, "
                + "so rules would never see the fact", ex.getMessage());
    }

    @Test
    @DisplayName("a class name from an imported package is rejected; without the import it is an ordinary fact")
    void importedClassNameRejected() {
        assertEquals(Map.of("hit", true), engine("Date == 5").run(new FactMap<>(new Fact<>("Date", 5))));

        RulesEngine<Map<String, Object>> imported = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("java.util").build();
        imported.load(List.of(rule("Date == 5")));

        assertThrows(IllegalArgumentException.class, () -> imported.run(new FactMap<>(new Fact<>("Date", 5))));
    }

    @Test
    @DisplayName("a class name found in two imported packages is rejected like any other class name")
    void ambiguousClassNameRejected() {
        RulesEngine<Map<String, Object>> imported = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports("java.util", "java.sql").build();
        imported.load(List.of(rule("'Date' != null")));

        for (int i = 0; i < 2; i++) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> imported.run(new FactMap<>(new Fact<>("Date", 5))));
            assertEquals("'Date' cannot be used as a fact name: MVEL reads it as a keyword or class name, "
                    + "so rules would never see the fact", ex.getMessage());
        }
    }

    @Test
    @DisplayName("a hyphenated name no longer silently matches as a subtraction")
    void hyphenatedNameNotMisparsed() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("my-fact", 5);
        facts.setValue("my", 10);
        facts.setValue("fact", 9);

        assertThrows(IllegalArgumentException.class, () -> engine("my-fact == 1").run(facts));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"total", "_x", "$x", "claim", "Process", "Date"})
    void validNamesAccepted(String name) {
        assertEquals(Map.of("hit", true), engine(name + " == 5").run(new FactMap<>(new Fact<>(name, 5))));
    }

    @Test
    @DisplayName("repeated runs with the same names are answered from the cache")
    void repeatedRunsAccepted() {
        RulesEngine<Map<String, Object>> engine = engine("claim == 5");
        for (int i = 0; i < 3; i++) {
            assertEquals(Map.of("hit", true), engine.run(new FactMap<>(new Fact<>("claim", 5))));
        }
    }

}
