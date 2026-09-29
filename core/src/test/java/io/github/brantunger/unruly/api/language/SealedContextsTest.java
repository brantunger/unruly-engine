package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.JavaSources.Compilation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static io.github.brantunger.unruly.JavaSources.compile;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("only the engine implements the contexts it passes to a language")
class SealedContextsTest {

    private static final String CORE = "io.github.brantunger.unruly.core.";

    private static List<String> permittedSubclasses(Class<?> type) {
        assertTrue(type.isSealed(), type.getSimpleName() + " isn't sealed");
        return Arrays.stream(type.getPermittedSubclasses()).map(Class::getName).sorted().toList();
    }

    @Test
    @DisplayName("CompileContext permits only the engine's record")
    void compileContextSealed() {
        assertEquals(List.of(CORE + "EngineCompileContext"), permittedSubclasses(CompileContext.class));
    }

    @Test
    @DisplayName("EvaluationContext permits only ActionContext and the engine's record")
    void evaluationContextSealed() {
        assertEquals(List.of(ActionContext.class.getName(), CORE + "EngineEvaluationContext"),
                permittedSubclasses(EvaluationContext.class));
    }

    @Test
    @DisplayName("ActionContext permits only the engine's record")
    void actionContextSealed() {
        assertEquals(List.of(CORE + "EngineActionContext"), permittedSubclasses(ActionContext.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EngineCompileContext", "EngineEvaluationContext", "EngineActionContext"})
    @DisplayName("the engine's context records are public, so the interfaces in another package can permit them")
    void recordsArePublic(String simpleName) throws ClassNotFoundException {
        Class<?> type = Class.forName(CORE + simpleName);

        assertTrue(type.isRecord(), simpleName + " isn't a record");
        assertTrue(Modifier.isPublic(type.getModifiers()), simpleName + " isn't public");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "record TestContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader) "
                    + "implements CompileContext {}",
            "record TestContext(Map<String, Object> facts) implements EvaluationContext {}",
            "record TestContext(Map<String, Object> facts, Object output) implements ActionContext {}"})
    @DisplayName("a class outside the engine that implements a context doesn't compile")
    void testDoubleDoesNotCompile(String declaration, @TempDir Path classes) {
        String source = "package com.example.lang;\n"
                + "import io.github.brantunger.unruly.api.language.*;\n"
                + "import java.util.Map;\n"
                + "import java.util.Set;\n"
                + declaration + "\n";
        Compilation compilation = compile(
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                List.of(source("com/example/lang/TestContext", source)));

        assertFalse(compilation.compiled(), "the test double compiled");
        assertTrue(compilation.errors().contains("sealed"), compilation.errors());
    }
}
