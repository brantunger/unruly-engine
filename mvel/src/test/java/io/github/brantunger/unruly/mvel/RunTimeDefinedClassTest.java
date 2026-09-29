package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A class a code generator defines at run time, with {@code MethodHandles.Lookup.defineClass}, in the JDK's
 * application class loader has no class file, so with the JDK's own loaders it isn't found through an import of its
 * package (#701). An import of the class itself finds it, as before. The class is compiled, then defined in this
 * test's class loader and package, once for the JVM.
 */
@DisplayName("a class defined at run time is imported by its class (#701)")
class RunTimeDefinedClassTest {

    private static final String NAME = "RunTimeDefined";

    @BeforeAll
    static void defineClass(@TempDir Path dir) throws Exception {
        String pkg = RunTimeDefinedClassTest.class.getPackageName();
        assertCompiles(List.of("-proc:none", "-d", dir.toString()), List.of(source(NAME,
                "package " + pkg + ";\npublic class " + NAME + " {\n    public int v() { return 9; }\n}\n")));
        MethodHandles.lookup().defineClass(Files.readAllBytes(dir.resolve(pkg.replace('.', '/')).resolve(NAME
                + ".class")));
    }

    private static Object run(String imported) {
        ClassLoader loader = RunTimeDefinedClassTest.class.getClassLoader();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).imports(imported).build()) {
            engine.load(List.of(Rule.builder().ruleName("r").condition("true")
                    .action("output.put('k', new " + NAME + "().v())").build()));
            return engine.run(new FactMap<>());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("the class is defined in the JDK's application class loader, with no class file")
    void definedWithoutClassFile() {
        ClassLoader loader = RunTimeDefinedClassTest.class.getClassLoader();

        assertAll(
                () -> assertTrue(Imports.isJdkLoader(loader), loader.toString()),
                () -> assertNull(loader.getResource(
                        RunTimeDefinedClassTest.class.getPackageName().replace('.', '/') + "/" + NAME + ".class")));
    }

    @Test
    @DisplayName("an import of the class itself finds it")
    void classImportFindsIt() {
        assertEquals(Map.of("k", 9), run(RunTimeDefinedClassTest.class.getPackageName() + "." + NAME));
    }

    @Test
    @DisplayName("an import of its package doesn't, with the JDK's class loaders")
    void packageImportDoesNot() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> run(RunTimeDefinedClassTest.class.getPackageName()));

        assertTrue(ex.getMessage().contains("could not resolve class: " + NAME), ex.getMessage());
    }
}
