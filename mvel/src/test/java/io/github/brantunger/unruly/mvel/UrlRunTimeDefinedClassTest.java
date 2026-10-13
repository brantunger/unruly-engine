package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A class a code generator defines at run time, with {@code MethodHandles.Lookup.defineClass}, in a plain
 * {@code URLClassLoader} has no class file, so, as with the JDK's own loaders, the rule list's class loader never asks
 * for it by name (#1130): an inline import of it fails, and so does its fully qualified name where MVEL looks it up
 * while the rule compiles, as with strong typing. A class import the engine is built with still finds it, and so does a
 * rule loaded with a subclass of {@code URLClassLoader}. The class is defined, once for the JVM, in the package of a
 * class each loader loads from a directory that has no class file for it.
 */
@DisplayName("a class defined at run time in a plain URLClassLoader is found only through the engine's class import"
        + " (#1130)")
class UrlRunTimeDefinedClassTest {

    private static final String PACKAGE = "urldefined";

    private static final String NAME = "Defined";

    private static final String QUALIFIED = PACKAGE + "." + NAME;

    private static URLClassLoader plain;

    private static URLClassLoader subclass;

    /** The output of an engine with strong typing, which needs an output type MVEL can check. */
    public static final class Out {
        public int k;
    }

    @BeforeAll
    static void defineClass(@TempDir Path dir) throws Exception {
        Path hosts = dir.resolve("hosts");
        Path defined = dir.resolve("defined");
        assertCompiles(List.of("-proc:none", "-d", hosts.toString()), List.of(source(PACKAGE + "/Host",
                "package " + PACKAGE + ";\npublic class Host {\n}\n")));
        assertCompiles(List.of("-proc:none", "-d", defined.toString()), List.of(source(PACKAGE + "/" + NAME,
                "package " + PACKAGE + ";\npublic class " + NAME + " {\n    public int v() { return 9; }\n}\n")));
        byte[] bytes = Files.readAllBytes(defined.resolve(PACKAGE).resolve(NAME + ".class"));
        URL[] urls = {hosts.toUri().toURL()};
        plain = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
        subclass = new URLClassLoader(urls, ClassLoader.getSystemClassLoader()) {
        };
        for (ClassLoader loader : List.of(plain, subclass)) {
            Class<?> host = loader.loadClass(PACKAGE + ".Host");
            MethodHandles.privateLookupIn(host, MethodHandles.lookup()).defineClass(bytes);
        }
    }

    @AfterAll
    static void close() throws IOException {
        plain.close();
        subclass.close();
    }

    private static Object run(ClassLoader loader, String action, String... imports) {
        return withContextClassLoader(loader, () -> {
            try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                    .<Map<String, Object>>allMatches(HashMap::new).imports(imports).build()) {
                engine.load(List.of(Rule.builder().ruleName("r").condition("true").action(action).build()));
                return engine.run(new FactMap<>());
            }
        });
    }

    private static int runTyped(ClassLoader loader, String condition, String... imports) {
        return withContextClassLoader(loader, () -> {
            try (RulesEngine<Out> engine = RulesEngineBuilder.allMatches(Out::new).outputType(Out.class)
                    .fact("n", Integer.class).requireDeclaredFacts().option("mvel", "strongTyping", "true")
                    .imports(imports).build()) {
                engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action("output.k = n").build()));
                return engine.run(new FactMap<>(new Fact<>("n", 1))).k;
            }
        });
    }

    @Test
    @DisplayName("the class is defined in each URL class loader, which serves no class file for it")
    void definedWithoutClassFile() throws ClassNotFoundException {
        assertAll(
                () -> assertSame(URLClassLoader.class, plain.getClass()),
                () -> assertSame(plain, plain.loadClass(QUALIFIED).getClassLoader()),
                () -> assertSame(subclass, subclass.loadClass(QUALIFIED).getClassLoader()),
                () -> assertNull(plain.getResource(PACKAGE + "/" + NAME + ".class")));
    }

    @Test
    @DisplayName("an import of the class itself in the engine finds it, with strong typing or not")
    void engineClassImportFindsIt() {
        assertAll(
                () -> assertEquals(Map.of("k", 9), run(plain, "output.put('k', new " + NAME + "().v())", QUALIFIED)),
                () -> assertEquals(1, runTyped(plain, "new " + NAME + "().v() == 9", QUALIFIED)));
    }

    @Test
    @DisplayName("an inline import of the class fails load()")
    void inlineImportFailsLoad() {
        String action = "import " + QUALIFIED + "; output.put('k', new " + NAME + "().v())";

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> run(plain, action));

        assertEquals("Action for rule 'r' failed to compile at line 1, column 8: class not found: " + action,
                ex.getMessage());
    }

    @Test
    @DisplayName("its fully qualified name fails load() with strong typing")
    void qualifiedNameFailsTypedLoad() {
        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> runTyped(plain, "new " + QUALIFIED + "().v() == 9"));

        assertEquals("Condition for rule 'r' failed to compile at line 1, column 5: could not resolve class: "
                + QUALIFIED, ex.getMessage());
    }

    // A named guard: without strong typing, MVEL looks up the class of a new only when the rule first runs, with the
    // running thread's context class loader, here the URL class loader, not the rule list's.
    @Test
    @DisplayName("its fully qualified name in new still runs without strong typing, found when the rule runs")
    void qualifiedNewFoundWhenRun() {
        assertEquals(Map.of("k", 9), run(plain, "output.put('k', new " + QUALIFIED + "().v())"));
    }

    // A named guard: only a URLClassLoader of that class exactly is taken to serve every class file.
    @Test
    @DisplayName("with a subclass of URLClassLoader, an inline import of the class finds it, as before")
    void subclassInlineImportFindsIt() {
        assertEquals(Map.of("k", 9),
                run(subclass, "import " + QUALIFIED + "; output.put('k', new " + NAME + "().v())"));
    }
}
