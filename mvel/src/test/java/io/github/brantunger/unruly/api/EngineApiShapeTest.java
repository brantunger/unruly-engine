package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.CompileContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses reflection, so the test compiles against any {@code RulesEngine} and {@code RulesEngineBuilder}, whatever
 * methods they have.
 */
@DisplayName("an engine is configured once, on a builder, and loads rules with load()")
class EngineApiShapeTest {

    private static List<String> names(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getDeclaringClass() == type)
                .map(Method::getName)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("RulesEngine has only load, run and close: imports, languages and listeners can't change on a built engine")
    void engineMethods() {
        assertEquals(List.of("close", "load", "run"), names(RulesEngine.class));
    }

    @Test
    @DisplayName("RulesEngineBuilder.firstMatch and allMatches return a builder; stateless and stateful are gone")
    void builderFactories() throws NoSuchMethodException {
        for (String factory : List.of("firstMatch", "allMatches")) {
            Method method = RulesEngineBuilder.class.getMethod(factory, Supplier.class);
            assertTrue(Modifier.isStatic(method.getModifiers()), factory + " isn't static");
            assertEquals(RulesEngineBuilder.class, method.getReturnType(), factory);
        }
        List<String> names = names(RulesEngineBuilder.class);
        assertFalse(names.contains("stateless"), names.toString());
        assertFalse(names.contains("stateful"), names.toString());
    }

    @Test
    @DisplayName("the builder sets the languages, imports, listeners, copy limit, output and language options")
    void builderSettings() {
        assertEquals(List.of("allMatches", "build", "defaultLanguage", "firstMatch", "imports", "imports", "language",
                "listener", "listeners", "maxCopies", "option", "outputType", "outputWriter"),
                names(RulesEngineBuilder.class));
    }

    @Test
    @DisplayName("a language is given the imports, the class loader, the output type and its options")
    void compileContextSettings() {
        assertEquals(List.of("classImports", "classLoader", "options", "outputType", "packageImports", "warn"),
                names(CompileContext.class));
    }
}
