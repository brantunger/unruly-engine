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
    @DisplayName("RulesEngine loads or validates rules, runs them, reports what it loaded and closes: nothing else")
    void engineMethods() {
        assertEquals(List.of("close", "load", "rules", "run", "runWithResult", "runWithResult", "validate"),
                names(RulesEngine.class));
    }

    @Test
    @DisplayName("RuleListener sees a run start and end as well as each rule")
    void listenerCallbacks() {
        assertEquals(List.of("afterEvaluate", "afterExecute", "afterRun", "beforeEvaluate", "beforeExecute",
                "beforeRun", "onError", "onRunError"), names(RuleListener.class));
    }

    @Test
    @DisplayName("a run's result, its evaluations and the loaded rules are final classes, and a run's context is sealed to the engine")
    // Loaded by name, so this test compiles against a release that doesn't have these types yet.
    void runTypes() throws ClassNotFoundException {
        Class<?> runResult = Class.forName("io.github.brantunger.unruly.api.RunResult");
        Class<?> ruleEvaluation = Class.forName("io.github.brantunger.unruly.api.RuleEvaluation");
        Class<?> ruleSetInfo = Class.forName("io.github.brantunger.unruly.api.RuleSetInfo");
        Class<?> runContext = Class.forName("io.github.brantunger.unruly.api.RunContext");

        assertTrue(Modifier.isFinal(runResult.getModifiers()), "RunResult isn't final");
        assertTrue(Modifier.isFinal(ruleEvaluation.getModifiers()), "RuleEvaluation isn't final");
        assertTrue(Modifier.isFinal(ruleSetInfo.getModifiers()), "RuleSetInfo isn't final");
        assertTrue(runContext.isSealed(), "RunContext isn't sealed");
        assertEquals(List.of("io.github.brantunger.unruly.core.EngineRunContext"),
                Arrays.stream(runContext.getPermittedSubclasses()).map(Class::getName).toList());
        assertEquals(List.of("facts", "matchPolicy", "parent", "ruleSetChecksum", "runId", "startedAt", "tags"),
                names(runContext));
    }

    @Test
    @DisplayName("RulesEngineBuilder.firstMatch, allMatches and uniqueMatch return a builder; stateless and stateful are gone")
    void builderFactories() throws NoSuchMethodException {
        for (String factory : List.of("firstMatch", "allMatches", "uniqueMatch")) {
            Method method = RulesEngineBuilder.class.getMethod(factory, Supplier.class);
            assertTrue(Modifier.isStatic(method.getModifiers()), factory + " isn't static");
            assertEquals(RulesEngineBuilder.class, method.getReturnType(), factory);
        }
        List<String> names = names(RulesEngineBuilder.class);
        assertFalse(names.contains("stateless"), names.toString());
        assertFalse(names.contains("stateful"), names.toString());
    }

    @Test
    @DisplayName("the builder sets the languages, imports, listeners, facts, copy limit, copies at load, timeout, clock, output and options")
    // The copy limit has two setters: maxCopies(n) for every thread, and unlimitedCopies() to turn it off.
    void builderSettings() {
        assertEquals(List.of("allMatches", "build", "clock", "copiesAtLoad", "defaultLanguage", "fact", "facts", "firstMatch", "imports",
                "imports", "language", "listener", "listeners", "maxCopies", "option", "outputType", "outputWriter",
                "requireDeclaredFacts", "runTimeout", "uniqueMatch", "unlimitedCopies"),
                names(RulesEngineBuilder.class));
    }

    @Test
    @DisplayName("a language is given the imports, the class loader, the output type, the declared facts and its options")
    void compileContextSettings() {
        assertEquals(List.of("allFactsDeclared", "classImports", "classLoader", "declaredFacts", "options",
                "outputType", "packageImports", "warn"),
                names(CompileContext.class));
    }
}
