package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVEL looks each name in a rule up in each imported package by loading it as a class. A parallel-capable class
 * loader, as the JDK's application class loader is, keeps a lock object for every name it is asked to load, found or
 * not, for as long as it lives, so every name that isn't a class left some behind for each package imported. With the
 * JDK's own class loaders, the class file is looked up first, as it is for a fact's name, and a name without one is
 * never loaded. Any other class loader is asked as before, as it may define a class it serves no class file for.
 */
@DisplayName("a name in a rule that isn't a class leaves no lock object in the JDK's class loaders (#701)")
class PackageImportLockTest {

    /**
     * An application class loader of the application's own that is parallel-capable, as the JDK's is, and records
     * every name it makes a lock object for: every name it is asked to load, found or not.
     */
    private static final class LockRecordingLoader extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        private final List<String> locked = new CopyOnWriteArrayList<>();

        LockRecordingLoader() {
            super(PackageImportLockTest.class.getClassLoader());
        }

        @Override
        protected Object getClassLoadingLock(String className) {
            locked.add(className);
            return super.getClassLoadingLock(className);
        }
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    // The engine takes its class loader for the rules' classes from the thread that loads them.
    private static RulesEngine<Map<String, Object>> loaded(ClassLoader loader, Rule rule, String... imports) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports(imports).build();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            engine.load(List.of(rule));
        } finally {
            thread.setContextClassLoader(previous);
        }
        return engine;
    }

    private static Object loadAndRun(ClassLoader loader, Rule rule, String... imports) {
        return loaded(loader, rule, imports).run(new FactMap<>(new Fact<>("amountDue", 5)));
    }

    @Test
    @DisplayName("with the JDK's class loaders, a fact's name is never loaded as a class, with a package imported by"
            + " the engine or in the rule's own text")
    void jdkLoadersKeepNoLock(@TempDir Path dir) throws Exception {
        String output = ChildJvm.run(dir, PackageImportLockScenario.class,
                "--add-opens", "java.base/java.lang=ALL-UNNAMED");

        List<String> messages = output.lines().filter(line -> line.startsWith(PackageImportLockScenario.MESSAGE))
                .map(line -> line.substring(PackageImportLockScenario.MESSAGE.length()))
                .toList();
        assertEquals(List.of("engine {k=[5]} []", "inline []"), messages,
                "each rule's output, and the names locked that hold its fact's name; scenario output:\n" + output);
    }

    @Test
    @DisplayName("a class loader of the application's own is still asked for a name, as before")
    void otherLoaderAskedAsBefore() {
        LockRecordingLoader loader = new LockRecordingLoader();

        Object output = loadAndRun(loader, rule("amountDue > 1", "output.put('k', new ArrayList(List.of(amountDue)))"),
                "java.util");

        assertEquals(Map.of("k", List.of(5)), output);
        assertTrue(loader.locked.contains("java.util.amountDue"), loader.locked.toString());
    }

    @Test
    @DisplayName("a class in an imported package that a class loader of the application's own serves no class file"
            + " for is still found")
    void classWithoutClassFileStillFound() {
        ClassLoader servesNoClassFile = new ClassLoader(PackageImportLockTest.class.getClassLoader()) {
            @Override
            public URL getResource(String name) {
                return name.startsWith("java/util/") ? null : super.getResource(name);
            }
        };

        Object output = loadAndRun(servesNoClassFile, rule("true", "output.put('k', new ArrayList(List.of(1)))"),
                "java.util");

        assertEquals(Map.of("k", List.of(1)), output);
    }

    @Test
    @DisplayName("with the JDK's class loaders, a class nested in a class of an imported package is still found")
    void nestedClassInPackageFound() {
        Object output = loadAndRun(ClassLoader.getSystemClassLoader(),
                rule("true", "output.put('k', new AbstractMap.SimpleEntry('a', 1).getValue())"), "java.util");

        assertEquals(Map.of("k", 1), output);
    }

    @Test
    @DisplayName("with the JDK's class loaders, a name too long for the rule list's class loader still reads as"
            + " a fact")
    void longNameStillAFact() {
        String name = "z".repeat(ExactNameClassLoader.MAX_NAME_LENGTH);
        RulesEngine<Map<String, Object>> engine = loaded(ClassLoader.getSystemClassLoader(),
                rule(name + " == 1", "output.put('k', 1)"), "java.util");

        assertEquals(Map.of("k", 1), engine.run(new FactMap<>(new Fact<>(name, 1))));
    }

    @Test
    @DisplayName("with the JDK's class loaders, new of a chain with too many parts still fails when it runs, as before")
    void longChainStillNotAClass() {
        String chain = "a.".repeat(ExactNameClassLoader.MAX_NAME_PARTS - 1) + "a";
        RulesEngine<Map<String, Object>> engine = loaded(ClassLoader.getSystemClassLoader(),
                rule("true", "x = new " + chain + "();"), "java.util");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("could not resolve class: " + chain.substring(0, 100)), ex.getMessage());
    }
}
