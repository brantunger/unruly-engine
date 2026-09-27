package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.ParserConfiguration;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With the JDK's own class loaders, a name in a rule is looked up in the imported packages as a class file before MVEL
 * loads it (#701). These tests ask a rule list's configuration directly, with a class loader in place of the rule
 * list's own whose parent is the JDK's application class loader, so it is looked up as for the JDK's loaders, and
 * which records the class files it is asked for.
 */
@DisplayName("a name is looked up as a class file only as often, and in only as many forms, as it may be a class")
class ClassFileLookupTest {

    /** A class loader that records the resources it is asked for, whose parent is the JDK's application loader. */
    private static final class ResourceRecordingLoader extends ClassLoader {

        private final List<String> resources = new CopyOnWriteArrayList<>();

        ResourceRecordingLoader() {
            super(ClassLoader.getSystemClassLoader());
        }

        @Override
        public URL getResource(String name) {
            resources.add(name);
            return super.getResource(name);
        }
    }

    private static ParserConfiguration configuration(ResourceRecordingLoader loader, Set<String> packages) {
        return new Imports(packages, Set.of(), loader, ConcurrentHashMap.newKeySet(), Map.of()).newConfiguration();
    }

    // Review of #701: MVEL asks for a name more than once, and only a rule list's own imports remembered a miss.
    @Test
    @DisplayName("once an expression imports a package of its own, a name that isn't a class is looked up once")
    void ownPackageMissLookedUpOnce() {
        ResourceRecordingLoader loader = new ResourceRecordingLoader();
        ParserConfiguration configuration = configuration(loader, Set.of());
        configuration.addPackageImport("java.util");

        assertFalse(configuration.hasImport("amountDue"));
        assertFalse(configuration.hasImport("amountDue"));

        assertEquals(1, Collections.frequency(loader.resources, "java/util/amountDue.class"));
    }

    @Test
    @DisplayName("a class in a package the expression imports is still found")
    void ownPackageClassFound() {
        ParserConfiguration configuration = configuration(new ResourceRecordingLoader(), Set.of());
        configuration.addPackageImport("java.util");

        assertTrue(configuration.hasImport("ArrayList"));
    }

    // Review of #701: MVEL asks for the name of an array type, such as [Ljava.util.Foo; for new Foo[3].
    @Test
    @DisplayName("a name MVEL rejects outright, such as an array type's, isn't looked up as a class file")
    void arrayTypeNameNotLookedUp() {
        ResourceRecordingLoader loader = new ResourceRecordingLoader();

        assertFalse(configuration(loader, Set.of("java.util")).hasImport("[Ljava.util.Foo;"));

        assertEquals(List.of(), loader.resources.stream().filter(name -> name.contains("[L")).toList());
    }

    // Review of #701: the check had its own copy of the rule list's class loader's limits, which counted a '$' as no
    // part, so a name the loader refuses was still looked up as a class file.
    @Test
    @DisplayName("a name with a '$' the rule list's class loader refuses isn't looked up as a class file")
    void refusedNameWithDollarNotLookedUp() {
        ResourceRecordingLoader loader = new ResourceRecordingLoader();
        // With the package, 81 parts and a '$', which the loader refuses; with one dot turned into '$', 80.
        String name = "a.".repeat(78) + "a$B";

        assertFalse(configuration(loader, Set.of("java.util")).hasImport(name));

        assertAll(
                () -> assertFalse(loader.resources.contains("java/util/" + "a/".repeat(78) + "a$B.class"),
                        "the name the loader refuses"),
                () -> assertTrue(loader.resources.contains("java/util/" + "a/".repeat(77) + "a$a$B.class"),
                        "the next name MVEL looks up, which the loader doesn't refuse"));
    }

    @Test
    @DisplayName("once the loader refuses a name with a '$', MVEL's later names aren't looked up either")
    void refusedNestedNameEndsTheLookup() {
        ResourceRecordingLoader loader = new ResourceRecordingLoader();
        // With the package, 82 parts, and 81 with the last dot turned into '$': both refused.
        String name = "a.".repeat(79) + "a";

        assertFalse(configuration(loader, Set.of("java.util")).hasImport(name));

        assertEquals(List.of(), loader.resources.stream().filter(resource -> resource.contains("a/a")).toList());
    }

    @Test
    @DisplayName("the rule list's class loader refuses a name as it looks it up")
    void refusalAsTheLoaderLooksUp() {
        assertAll(
                () -> assertFalse(ExactNameClassLoader.refuses("a.".repeat(80) + "a"), "81 parts"),
                () -> assertTrue(ExactNameClassLoader.refuses("a.".repeat(81) + "a"), "82 parts"),
                () -> assertFalse(ExactNameClassLoader.refuses("a.".repeat(79) + "a$B"), "80 parts and a '$'"),
                () -> assertTrue(ExactNameClassLoader.refuses("a.".repeat(80) + "a$B"), "81 parts and a '$'"),
                () -> assertFalse(ExactNameClassLoader.refuses("a".repeat(2_000)), "2,000 characters"),
                () -> assertTrue(ExactNameClassLoader.refuses("a".repeat(2_001)), "2,001 characters"));
    }
}
