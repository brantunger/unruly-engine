package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A class imported on its own, such as {@code app.exp.Outer}, lets a rule name a class nested in it as Java does,
 * {@code Outer.Nested}, as a package import of {@code app.exp} already did. The classes are compiled into a directory
 * of their own, which the thread's context class loader serves, as an application's class path would.
 */
@DisplayName("a class nested in a class imported on its own is named through it (#707)")
class NestedClassImportTest {

    private static final String OUTER = "package app.exp;\n"
            + "public class Outer {\n"
            + "    public static final int FIELD = 3;\n"
            + "    public static class Nested {\n"
            + "        public int v() { return 7; }\n"
            + "        public static class Deeper {\n"
            + "            public int v() { return 8; }\n"
            + "        }\n"
            + "    }\n"
            + "    public static class Lost {\n"
            + "    }\n"
            + "    public static class NeedsDep extends Dep {\n"
            + "    }\n"
            + "}\n";

    @TempDir
    static Path classes;

    // Outer and its nested classes, a class named as one nested in it would be that isn't, and a copy of both in
    // another package whose Lost class file, and the Dep class NeedsDep extends, were left out.
    @BeforeAll
    static void compileClasses() throws IOException {
        for (String pkg : List.of("exp", "gone")) {
            compile(classes, "app/" + pkg + "/Dep", "package app." + pkg + ";\npublic class Dep {\n}\n");
            compile(classes, "app/" + pkg + "/Outer", OUTER.replace("package app.exp;", "package app." + pkg + ";"));
        }
        compile(classes, "app/exp/Outer$Fake", "package app.exp;\npublic class Outer$Fake {\n}\n");
        Files.delete(classes.resolve("app/gone/Outer$Lost.class"));
        Files.delete(classes.resolve("app/gone/Dep.class"));
    }

    private static void compile(Path directory, String path, String text) {
        assertCompiles(List.of("-proc:none", "-cp", directory.toString(), "-d", directory.toString()),
                List.of(source(path, text)));
    }

    /** The application's class loader, which serves the compiled classes and records every name it is asked for. */
    private static final class ApplicationLoader extends URLClassLoader {

        private final List<String> loadedClasses = new CopyOnWriteArrayList<>();

        ApplicationLoader() throws IOException {
            super(new URL[] {classes.toUri().toURL()}, NestedClassImportTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            loadedClasses.add(name);
            return super.loadClass(name, resolve);
        }
    }

    private static Object run(ApplicationLoader loader, String action, String... imports) {
        // The engine resolves its imports when it is built, and takes its class loader from the thread that loads
        // rules.
        return withContextClassLoader(loader, () -> {
            try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                    .<Map<String, Object>>allMatches(HashMap::new).imports(imports).build()) {
                engine.load(List.of(Rule.builder().ruleName("r").condition("true").action(action).build()));
                return engine.run(new FactMap<>());
            }
        });
    }

    private static Object run(String action, String... imports) throws IOException {
        try (ApplicationLoader loader = new ApplicationLoader()) {
            return run(loader, action, imports);
        }
    }

    @Test
    @DisplayName("new Outer.Nested() runs with Outer imported by the engine")
    void engineClassImport() throws IOException {
        assertEquals(Map.of("k", 7), run("output.put('k', new Outer.Nested().v())", "app.exp.Outer"));
    }

    // Review of #707: the nested class was looked for with the rule list's class loader, from the thread that loads
    // the rules, which may not see the class the engine imported when it was built.
    @Test
    @DisplayName("new Outer.Nested() runs when the rules load on a thread whose class loader can't see Outer")
    void loadedWhereOuterIsNotSeen() throws IOException {
        RulesEngine<Map<String, Object>> engine;
        try (ApplicationLoader loader = new ApplicationLoader()) {
            engine = withContextClassLoader(loader, () -> RulesEngineBuilder
                    .<Map<String, Object>>allMatches(HashMap::new).imports("app.exp.Outer").build());
            // This thread's own class loader, the test's, has no app.exp.Outer.
            engine.load(List.of(Rule.builder().ruleName("r").condition("true")
                    .action("output.put('k', new Outer.Nested().v())").build()));

            assertEquals(Map.of("k", 7), engine.run(new FactMap<>()));
        }
    }

    @Test
    @DisplayName("a class nested in a class of the JDK's own, which the boot loader defines, is named through it")
    void nestedInBootClass() throws IOException {
        assertEquals(Map.of("k", 1),
                run("output.put('k', new AbstractMap.SimpleEntry('a', 1).getValue())", "java.util.AbstractMap"));
    }

    @Test
    @DisplayName("new Outer.Nested() runs with Outer imported in the rule's own text")
    void inlineClassImport() throws IOException {
        assertEquals(Map.of("k", 7), run("import app.exp.Outer; output.put('k', new Outer.Nested().v())"));
    }

    @Test
    @DisplayName("a class nested in a nested class is named through each")
    void nestedTwice() throws IOException {
        assertEquals(Map.of("k", 8), run("output.put('k', new Outer.Nested.Deeper().v())", "app.exp.Outer"));
    }

    @Test
    @DisplayName("new Outer.Nested() runs with Outer's package imported, as it did before")
    void packageImport() throws IOException {
        assertEquals(Map.of("k", 7), run("output.put('k', new Outer.Nested().v())", "app.exp"));
    }

    @Test
    @DisplayName("new app.exp.Outer$Nested() runs, as it did before")
    void binaryName() throws IOException {
        assertEquals(Map.of("k", 7), run("output.put('k', new app.exp.Outer$Nested().v())"));
    }

    @Test
    @DisplayName("new app.exp.Outer.Nested(), which names no import, still fails when it runs")
    void fullyQualifiedStillFails() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> run("output.put('k', new app.exp.Outer.Nested().v())"));

        assertTrue(ex.getMessage().contains("could not resolve class: app.exp.Outer.Nested"), ex.getMessage());
    }

    @Test
    @DisplayName("a static field of an imported class is still read, and no class is looked up by its name")
    void fieldNotLookedUpAsClass() throws IOException {
        try (ApplicationLoader loader = new ApplicationLoader()) {
            assertEquals(Map.of("k", 3), run(loader, "output.put('k', Outer.FIELD)", "app.exp.Outer"));

            assertEquals(List.of(), loader.loadedClasses.stream().filter(name -> name.contains("FIELD")).toList());
        }
    }

    @Test
    @DisplayName("a name that isn't a class nested in the imported one fails when it runs, as it did before")
    void notNested() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> run("output.put('k', new Outer.Missing())", "app.exp.Outer"));

        assertTrue(ex.getMessage().contains("could not resolve class: Outer.Missing"), ex.getMessage());
    }

    @Test
    @DisplayName("a nested class whose class file is missing fails when it runs, as a class that isn't there")
    void nestedClassFileMissing() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> run("output.put('k', new Outer.Lost())", "app.gone.Outer"));

        assertTrue(ex.getMessage().contains("could not resolve class: Outer.Lost"), ex.getMessage());
    }

    // Review of #707: every class Outer declares was loaded, so one that couldn't be hid the others.
    @Test
    @DisplayName("a nested class runs though another class nested beside it is missing, or needs a missing class")
    void nestedClassBesideBrokenOnes() throws IOException {
        assertEquals(Map.of("k", 7), run("output.put('k', new Outer.Nested().v())", "app.gone.Outer"));
    }

    @Test
    @DisplayName("a nested class that needs a missing class fails when it runs, as a class that isn't there")
    void nestedClassNeedsMissingClass() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> run("output.put('k', new Outer.NeedsDep())", "app.gone.Outer"));

        assertTrue(ex.getMessage().contains("could not resolve class: Outer.NeedsDep"), ex.getMessage());
    }

    @Test
    @DisplayName("a class named as a nested one would be, but not declared in the imported class, isn't nested in it")
    void classNamedLikeNestedIsNot() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> run("output.put('k', new Outer.Fake())", "app.exp.Outer"));

        assertTrue(ex.getMessage().contains("could not resolve class: Outer.Fake"), ex.getMessage());
    }
}
