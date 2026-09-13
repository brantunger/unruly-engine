package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("addImport accepts packages and classes and rejects anything else")
class AddImportValidationTest {

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    @Test
    @DisplayName("a fully qualified class name imports that class")
    void classImport() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImport("java.time.LocalDate");
        engine.setRuleList(List.of(rule("true", "output.put('d', LocalDate.of(2020, 1, 1))")));

        assertEquals(Map.of("d", LocalDate.of(2020, 1, 1)), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("addImports mixes packages and classes")
    void mixedImports() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImports(new LinkedHashSet<>(List.of("java.util", "java.time.LocalDate")));
        engine.setRuleList(List.of(rule("Objects.nonNull(x)", "output.put('d', LocalDate.of(2020, 1, 1))")));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        assertEquals(Map.of("d", LocalDate.of(2020, 1, 1)), engine.run(facts));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "not a package!!", "java..util", "java.", ".java", "1abc", "java.util.1x"})
    void invalidNamesRejected(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.addImport(name));
        assertEquals("'" + name + "' is neither a class nor a valid package name", ex.getMessage());
    }

    @Test
    @DisplayName("a well-formed package name that doesn't exist is accepted")
    void unknownPackageAccepted() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        assertDoesNotThrow(() -> engine.addImport("com.does.not.exist"));
    }

    @Test
    @DisplayName("addImports with one invalid name imports nothing")
    void invalidNameImportsNothing() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        assertThrows(IllegalArgumentException.class,
                () -> engine.addImports(new LinkedHashSet<>(List.of("java.util", "not a package!!"))));

        engine.setRuleList(List.of(rule("Objects.nonNull(x)", "output.put('k', 1)")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        assertThrows(RuleExecutionException.class, () -> engine.run(facts), "java.util must not have been imported");
    }

    @Test
    @DisplayName("a class imported on its own can't be used as a fact name either")
    void classImportShadowsFactName() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImport("java.time.LocalDate");
        engine.setRuleList(List.of(rule("true", "output.put('k', 1)")));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("LocalDate", 1);
        assertThrows(IllegalArgumentException.class, () -> engine.run(facts));
    }
}
