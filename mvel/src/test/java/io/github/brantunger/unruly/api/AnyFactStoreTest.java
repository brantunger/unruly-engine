package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles against the old and new {@code RulesEngine}, so it can show what didn't work before.
 */
@DisplayName("run() accepts a FactStore of any type")
class AnyFactStoreTest {

    @Test
    @DisplayName("run() takes a FactStore<?>")
    void runTakesWildcard() throws NoSuchMethodException {
        ParameterizedType parameter = (ParameterizedType) RulesEngine.class.getMethod("run", FactStore.class)
                .getGenericParameterTypes()[0];

        assertInstanceOf(WildcardType.class, parameter.getActualTypeArguments()[0]);
    }

    @Test
    @DisplayName("code that passes a FactMap<Integer> to run() compiles")
    void typedFactMapCompiles(@TempDir Path classes) {
        String source = """
                package com.example.facts;

                import io.github.brantunger.unruly.api.FactMap;
                import io.github.brantunger.unruly.api.RulesEngine;
                import java.util.Map;

                class Scores {
                    Map<String, Object> run(RulesEngine<Map<String, Object>> engine) {
                        FactMap<Integer> facts = new FactMap<>();
                        facts.setValue("score", 780);
                        return engine.run(facts);
                    }
                }
                """;
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///com/example/facts/Scores.java"),
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
        assertTrue(compiled, errors);
    }
}
