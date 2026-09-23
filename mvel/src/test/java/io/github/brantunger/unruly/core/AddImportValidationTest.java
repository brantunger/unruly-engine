package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
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

@DisplayName("an engine's imports accept packages and classes, and build() rejects anything else")
class AddImportValidationTest {

    /** A class nested two levels deep, imported by its dotted name. */
    public static final class Mid {
        public static final class Inner {
        }
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    /** A language that keeps the context each of its compilers is created with. */
    private static ExpressionLanguage capturing(AtomicReference<CompileContext> captured) {
        return StubExpressionLanguage.named("capture").onNewCompiler(captured::set);
    }

    /** Loads one rule written in the {@link #capturing} language, and returns the context it was compiled with. */
    private static CompileContext loadedContext(RulesEngine<Map<String, Object>> engine,
                                                AtomicReference<CompileContext> captured) {
        engine.load(List.of(Rule.builder().ruleName("r").language("capture").condition("c").action("a").build()));
        return captured.get();
    }

    @Test
    @DisplayName("a fully qualified class name imports that class")
    void classImport() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.imports("java.time.LocalDate"));
        engine.load(List.of(rule("true", "output.put('d', LocalDate.of(2020, 1, 1))")));

        assertEquals(Map.of("d", LocalDate.of(2020, 1, 1)), engine.run(new FactMap<>()));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"java.util.Map.Entry", "java.util.Map$Entry"})
    @DisplayName("a nested class is imported whether it is written with a dot or a dollar sign")
    void nestedClassImport(String name) {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.imports(name));
        engine.load(List.of(rule("true", "output.put('e', Entry)")));

        assertEquals(Map.of("e", Map.Entry.class), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a class nested two levels deep is imported by its dotted name, not taken for a package")
    void twoLevelNestedClassImport() {
        String name = "io.github.brantunger.unruly.core.AddImportValidationTest.Mid.Inner";
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.imports(name));
        engine.load(List.of(rule("true", "output.put('c', Inner)")));

        assertSame(Mid.Inner.class, ImportResolver.resolve(name));
        assertEquals(Map.of("c", Mid.Inner.class), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("imports mix packages and classes")
    void mixedImports() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.imports(new LinkedHashSet<>(List.of("java.util", "java.time.LocalDate"))));
        engine.load(List.of(rule("Objects.nonNull(x)", "output.put('d', LocalDate.of(2020, 1, 1))")));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        assertEquals(Map.of("d", LocalDate.of(2020, 1, 1)), engine.run(facts));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "not a package!!", "java..util", "java.", ".java", "1abc", "java.util.1x"})
    void invalidNamesRejected(String name) {
        RulesEngineBuilder<Map<String, Object>> builder =
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                        .imports(name);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals("'" + name + "' is neither a class nor a valid package name", ex.getMessage());
    }

    @Test
    @DisplayName("a well-formed package name that doesn't exist is accepted")
    void unknownPackageAccepted() {
        RulesEngineBuilder<Map<String, Object>> builder =
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                        .imports("com.does.not.exist");

        assertDoesNotThrow(builder::build);
    }

    @ParameterizedTest(name = "after \"{0}\"")
    @ValueSource(strings = {"java.util", "java.time.LocalDate"})
    @DisplayName("an invalid name fails build() after a valid package or class name, so no engine is created")
    void invalidNameAfterValidNameFailsBuild(String valid) {
        RulesEngineBuilder<Map<String, Object>> builder =
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                        .imports(new LinkedHashSet<>(List.of(valid, "not a package!!")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals("'not a package!!' is neither a class nor a valid package name", ex.getMessage());
    }

    @Test
    @DisplayName("a language gets unmodifiable imports that later calls on the builder don't change")
    void compileContextImportsAreAnUnmodifiableSnapshot() {
        AtomicReference<CompileContext> captured = new AtomicReference<>();
        RulesEngineBuilder<Map<String, Object>> builder =
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                        .language(capturing(captured))
                        .imports("java.util", "java.time.LocalDate");
        RulesEngine<Map<String, Object>> engine = builder.build();
        CompileContext context = loadedContext(engine, captured);

        assertThrows(UnsupportedOperationException.class, () -> context.packageImports().add("java.text"));
        assertThrows(UnsupportedOperationException.class, () -> context.classImports().add(LocalTime.class));
        builder.imports("java.text", "java.time.LocalTime");

        assertEquals(Set.of("java.util"), context.packageImports());
        assertEquals(Set.of(LocalDate.class), context.classImports());
        CompileContext reloaded = loadedContext(engine, captured);
        assertEquals(Set.of("java.util"), reloaded.packageImports(), "a built engine keeps its imports");
        assertEquals(Set.of(LocalDate.class), reloaded.classImports(), "a built engine keeps its imports");
    }

    @Test
    @DisplayName("a class imported on its own can't be used as a fact name either")
    void classImportShadowsFactName() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.imports("java.time.LocalDate"));
        engine.load(List.of(rule("true", "output.put('k', 1)")));

        FactStore<Object> facts = new FactMap<>();
        facts.setValue("LocalDate", 1);
        assertThrows(IllegalArgumentException.class, () -> engine.run(facts));
    }
}
