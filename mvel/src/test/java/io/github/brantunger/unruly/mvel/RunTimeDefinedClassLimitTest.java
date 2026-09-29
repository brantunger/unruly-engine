package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class a code generator defines at run time, with {@code MethodHandles.Lookup.defineClass}, in the JDK's
 * application class loader has no class file, so with the JDK's own loaders the rule list's class loader never asks
 * for it by name (#752): an inline import of it fails, and so does its fully qualified name where MVEL looks it up
 * while the rule compiles, as with strong typing. A class import the engine is built with still finds it, and so does
 * a rule loaded with a class loader of the application's own. The class is compiled, then defined in this test's
 * class loader and package, once for the JVM.
 */
@DisplayName("a class defined at run time is found only through the engine's class import (#752)")
class RunTimeDefinedClassLimitTest {

    private static final String NAME = "RunTimeDefinedLimit";

    private static final String QUALIFIED = RunTimeDefinedClassLimitTest.class.getPackageName() + "." + NAME;

    @BeforeAll
    static void defineClass(@TempDir Path dir) throws Exception {
        String pkg = RunTimeDefinedClassLimitTest.class.getPackageName();
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///" + NAME + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "package " + pkg + ";\npublic class " + NAME + " {\n    public int v() { return 9; }\n}\n";
            }
        };
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean compiled = javac.getTask(null, null, diagnostics, List.of("-proc:none", "-d", dir.toString()), null,
                List.of(source)).call();
        assertTrue(compiled, diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n")));
        MethodHandles.lookup().defineClass(Files.readAllBytes(dir.resolve(pkg.replace('.', '/')).resolve(NAME
                + ".class")));
    }

    /** The output of an engine with strong typing, which needs an output type MVEL can check. */
    public static final class Out {
        public int k;
    }

    // The engine takes its class loader for the rules' classes from the thread that loads them.
    private static <T> T withContextLoader(ClassLoader loader, Supplier<T> call) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return call.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static Object run(ClassLoader loader, String action, String... imports) {
        return withContextLoader(loader, () -> {
            try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                    .<Map<String, Object>>allMatches(HashMap::new).imports(imports).build()) {
                engine.load(List.of(Rule.builder().ruleName("r").condition("true").action(action).build()));
                return engine.run(new FactMap<>());
            }
        });
    }

    private static int runTyped(String condition, String... imports) {
        return withContextLoader(jdkLoader(), () -> {
            try (RulesEngine<Out> engine = RulesEngineBuilder.allMatches(Out::new).outputType(Out.class)
                    .fact("n", Integer.class).requireDeclaredFacts().option("mvel", "strongTyping", "true")
                    .imports(imports).build()) {
                engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action("output.k = n").build()));
                return engine.run(new FactMap<>(new Fact<>("n", 1))).k;
            }
        });
    }

    private static ClassLoader jdkLoader() {
        return RunTimeDefinedClassLimitTest.class.getClassLoader();
    }

    @Test
    @DisplayName("the class is defined in the JDK's application class loader, with no class file")
    void definedWithoutClassFile() {
        assertAll(
                () -> assertTrue(Imports.isJdkLoader(jdkLoader()), jdkLoader().toString()),
                () -> assertNull(jdkLoader().getResource(QUALIFIED.replace('.', '/') + ".class")));
    }

    @Test
    @DisplayName("an inline import of the class fails load(), with the JDK's class loaders")
    void inlineImportFailsLoad() {
        String action = "import " + QUALIFIED + "; output.put('k', new " + NAME + "().v())";

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> run(jdkLoader(), action));

        assertEquals("Action for rule 'r' failed to compile at line 1, column 8: class not found: " + action,
                ex.getMessage());
    }

    @Test
    @DisplayName("its fully qualified name fails load() with strong typing, with the JDK's class loaders")
    void qualifiedNameFailsTypedLoad() {
        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> runTyped("new " + QUALIFIED + "().v() == 9"));

        assertEquals("Condition for rule 'r' failed to compile at line 1, column 5: could not resolve class: "
                + QUALIFIED, ex.getMessage());
    }

    // A named guard: without strong typing, MVEL looks up the class of a new only when the rule first runs, with the
    // running thread's context class loader, not the rule list's.
    @Test
    @DisplayName("its fully qualified name in new still runs without strong typing, found when the rule runs")
    void qualifiedNewFoundWhenRun() {
        assertEquals(Map.of("k", 9), run(jdkLoader(), "output.put('k', new " + QUALIFIED + "().v())"));
    }

    @Test
    @DisplayName("an import of the class itself in the engine finds it, with strong typing or not")
    void engineClassImportFindsIt() {
        assertAll(
                () -> assertEquals(Map.of("k", 9), run(jdkLoader(), "output.put('k', new " + NAME + "().v())",
                        QUALIFIED)),
                () -> assertEquals(1, runTyped("new " + NAME + "().v() == 9", QUALIFIED)));
    }

    @Test
    @DisplayName("with a class loader of the application's own, an inline import of the class finds it, as before")
    void otherLoaderInlineImportFindsIt() {
        ClassLoader own = new ClassLoader(jdkLoader()) {
        };

        assertEquals(Map.of("k", 9), run(own, "import " + QUALIFIED + "; output.put('k', new " + NAME + "().v())"));
    }
}
