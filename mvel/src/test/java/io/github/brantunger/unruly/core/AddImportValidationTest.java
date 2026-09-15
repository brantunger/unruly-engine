package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("addImport accepts packages and classes and rejects anything else")
class AddImportValidationTest {

    /** A class nested two levels deep, imported by its dotted name. */
    public static final class Mid {
        public static final class Inner {
        }
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    /** Loads one rule written in a language that keeps the context it is compiled with, and returns that context. */
    private static CompileContext compileContextOf(StatelessRulesEngine<Map<String, Object>> engine) {
        AtomicReference<CompileContext> captured = new AtomicReference<>();
        engine.registerLanguage(new ExpressionLanguage() {
            @Override
            public String name() {
                return "capture";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                captured.set(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (action, session) -> {
                        };
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        });
        engine.setRuleList(List.of(Rule.builder().ruleName("r").language("capture").condition("c").action("a").build()));
        return captured.get();
    }

    @Test
    @DisplayName("a fully qualified class name imports that class")
    void classImport() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImport("java.time.LocalDate");
        engine.setRuleList(List.of(rule("true", "output.put('d', LocalDate.of(2020, 1, 1))")));

        assertEquals(Map.of("d", LocalDate.of(2020, 1, 1)), engine.run(new FactMap<>()));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"java.util.Map.Entry", "java.util.Map$Entry"})
    @DisplayName("a nested class is imported whether it is written with a dot or a dollar sign")
    void nestedClassImport(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImport(name);
        engine.setRuleList(List.of(rule("true", "output.put('e', Entry)")));

        assertEquals(Map.of("e", Map.Entry.class), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a class nested two levels deep is imported by its dotted name, not taken for a package")
    void twoLevelNestedClassImport() {
        String name = "io.github.brantunger.unruly.core.AddImportValidationTest.Mid.Inner";
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImport(name);
        engine.setRuleList(List.of(rule("true", "output.put('c', Inner)")));

        assertSame(Mid.Inner.class, ImportResolver.resolve(name));
        assertEquals(Map.of("c", Mid.Inner.class), engine.run(new FactMap<>()));
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
    @DisplayName("addImports with a class name before an invalid name imports neither")
    void invalidNameAfterClassImportsNothing() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        assertThrows(IllegalArgumentException.class,
                () -> engine.addImports(new LinkedHashSet<>(List.of("java.time.LocalDate", "not a package!!"))));

        CompileContext context = compileContextOf(engine);
        assertEquals(Set.of(), context.classImports());
        assertEquals(Set.of(), context.packageImports());
    }

    @Test
    @DisplayName("a language gets unmodifiable imports that later addImport calls don't change")
    void compileContextImportsAreAnUnmodifiableSnapshot() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImport("java.util").addImport("java.time.LocalDate");
        CompileContext context = compileContextOf(engine);

        assertThrows(UnsupportedOperationException.class, () -> context.packageImports().add("java.text"));
        assertThrows(UnsupportedOperationException.class, () -> context.classImports().add(LocalTime.class));
        engine.addImport("java.text").addImport("java.time.LocalTime");

        assertEquals(Set.of("java.util"), context.packageImports());
        assertEquals(Set.of(LocalDate.class), context.classImports());
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
