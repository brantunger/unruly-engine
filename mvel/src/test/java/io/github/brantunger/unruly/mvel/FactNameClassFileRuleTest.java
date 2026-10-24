package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact's name is looked up in each imported package as a class file first only when the rule list's class loader
 * looks it up so, as it does around the JDK's own class loaders and a plain {@code URLClassLoader}. Around any other,
 * which may define a class it serves no class file for, the check loads the name, as MVEL does, so a fact named like
 * such a class is rejected rather than hidden by it (#1255).
 */
@DisplayName("a fact's name is a class to the check whenever MVEL would read it as one (#1255)")
class FactNameClassFileRuleTest {

    /** A package outside java., whose names the rule list's class loader asks a loader of the application's own for. */
    private static final String OWN_PACKAGE = FactNameClassFileRuleTest.class.getPackageName();

    private static final String REJECTED = "'ArrayList' cannot be used as a fact name: MVEL reads it as a keyword or"
            + " class name, so rules would never see the fact";

    /**
     * A class loader of the application's own that serves no class file for java.util, as one that defines a class
     * there at run time would, but loads its classes, as PackageImportLockTest.classWithoutClassFileStillFound has.
     */
    private static ClassLoader servesNoClassFile() {
        return new ClassLoader(FactNameClassFileRuleTest.class.getClassLoader()) {
            @Override
            public URL getResource(String name) {
                return name.startsWith("java/util/") ? null : super.getResource(name);
            }
        };
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    // The engine takes the rules' class loader from the thread that loads them.
    private static RulesEngine<Map<String, Object>> loaded(ClassLoader loader, Rule rule, String... imports) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports(imports).build();
        withContextClassLoader(loader, () -> engine.load(List.of(rule)));
        return engine;
    }

    @Test
    @DisplayName("a fact named like a class the loader serves no class file for is rejected, as MVEL reads the class")
    void classWithoutClassFileRejected() {
        Imports imports = new Imports(Set.of("java.util"), Set.of(), servesNoClassFile());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MvelFactNames(imports).check("ArrayList"));

        assertEquals(REJECTED, ex.getMessage());
        assertTrue(imports.newConfiguration().hasImport("ArrayList"), "MVEL reads ArrayList as the class");
    }

    @Test
    @DisplayName("a run with such a fact is rejected, rather than MVEL reading the class in the fact's place")
    void runWithClassWithoutClassFileRejected() {
        RulesEngine<Map<String, Object>> engine = loaded(servesNoClassFile(), rule("true",
                "output.put('k', ArrayList)"), "java.util");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("ArrayList", 5))));

        assertEquals(REJECTED, ex.getMessage());
    }

    @Test
    @DisplayName("with the JDK's own class loader, which serves the class file, the same fact is rejected")
    void runWithJdkLoaderRejected() {
        RulesEngine<Map<String, Object>> engine = loaded(FactNameClassFileRuleTest.class.getClassLoader(),
                rule("true", "output.put('k', ArrayList)"), "java.util");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>("ArrayList", 5))));

        assertEquals(REJECTED, ex.getMessage());
    }

    @Test
    @DisplayName("a class loader of the application's own is asked for a name that isn't a class once, with no class"
            + " file looked up")
    void otherLoaderAskedForANameOnce() {
        RecordingClassLoader loader = new RecordingClassLoader();
        MvelFactNames names = new MvelFactNames(new Imports(Set.of(OWN_PACKAGE), Set.of(), loader));

        names.check("amount");
        names.check("amount");

        assertEquals(1, Collections.frequency(loader.loadedClasses, OWN_PACKAGE + ".amount"));
        assertEquals(List.of(), loader.resources);
    }

    @Test
    @DisplayName("a class loader of the application's own is asked for a class once, with no class file looked up")
    void otherLoaderAskedForAClassOnce() {
        RecordingClassLoader loader = new RecordingClassLoader();
        MvelFactNames names = new MvelFactNames(new Imports(Set.of(OWN_PACKAGE), Set.of(), loader));
        String className = FactNameClassFileRuleTest.class.getSimpleName();

        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalArgumentException.class, () -> names.check(className));
        }

        assertEquals(1, Collections.frequency(loader.loadedClasses, OWN_PACKAGE + "." + className));
        assertEquals(List.of(), loader.resources);
    }

    @Test
    @DisplayName("around a loader whose class files the rule list's class loader looks up first, a name that isn't a"
            + " class is looked up as a class file, and never loaded")
    void classFileFirstNameNeverLoaded() {
        RecordingClassLoader loader = new RecordingClassLoader();
        MvelFactNames names = new MvelFactNames(new Imports(Set.of(OWN_PACKAGE), Set.of(), new ExactNameClassLoader(
                loader, ExactNameClassLoader.ClassLookup.MAX_REMEMBERED_NAMES, true), ConcurrentHashMap.newKeySet(),
                Map.of()));

        names.check("line_1");

        assertEquals(List.of(OWN_PACKAGE.replace('.', '/') + "/line_1.class"), loader.resources);
        assertEquals(List.of(), loader.loadedClasses);
    }

    @Test
    @DisplayName("a fact the rules don't read is never asked of a class loader of the application's own")
    void unreadFactNeverAsked() {
        RecordingClassLoader loader = new RecordingClassLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("amount > 1", "output.put('k', amount)"),
                OWN_PACKAGE);

        Object output = engine.run(new FactMap<>(new Fact<>("amount", 5), new Fact<>("unread", 1)));

        assertEquals(Map.of("k", 5), output);
        assertTrue(loader.loadedClasses.contains(OWN_PACKAGE + ".amount"), loader.loadedClasses.toString());
        assertFalse(loader.loadedClasses.contains(OWN_PACKAGE + ".unread"), loader.loadedClasses.toString());
    }
}
