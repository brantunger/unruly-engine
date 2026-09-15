package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///com/example/lang/TestContext.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        boolean compiled = javac.getTask(null, null, diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                null, List.of(file)).call();

        String errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        assertFalse(compiled, "the test double compiled");
        assertTrue(errors.contains("sealed"), errors);
    }
}
