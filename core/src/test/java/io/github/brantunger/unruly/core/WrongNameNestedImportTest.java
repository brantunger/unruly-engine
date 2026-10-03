package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An import's name, and each of its {@code $} forms, is looked up in a class directory where class
 * {@code com.acme.Rules} sits beside package {@code com.acme.rules}, which differ only in case. The class loader here
 * finds a class file as a case-insensitive file system does, ignoring case, and defines it under the name it was asked
 * for, so the JVM itself throws its {@code NoClassDefFoundError: ... (wrong name: ...)} when the file's class has a
 * name in another case. That happens on every operating system, unlike in
 * {@code mvel.RealCaseInsensitiveClassDirectoryTest}, which needs a case-insensitive file system.
 */
@DisplayName("a class file an import's name finds in another case doesn't stop the lookup of the class it names (#973)")
class WrongNameNestedImportTest {

    private static final String RULES = """
            package com.acme;

            public class Rules {
                public static class Limit {
                    public static class Kind {
                    }
                }
            }
            """;

    private static final String OTHER_CASE_LIMIT = """
            package com.acme.rules;

            public class Limit {
                public static class Kind {
                }
            }
            """;

    private static final String BASE = """
            package com.acme;

            public class Base {
            }
            """;

    private static final String OUTER = """
            package com.acme;

            public class Outer {
                public static class Sub extends Base {
                }
            }
            """;

    @TempDir
    static Path classes;

    /**
     * Compiles the classes once. {@code com.acme.Base} is then deleted, so {@code Outer.Sub} exists but can't be
     * loaded, and {@code com/acme/rules/Limit-1.class}, a copy of {@code Limit}'s class file, is a file whose name is
     * no class's.
     */
    @BeforeAll
    static void compileClasses() throws IOException {
        assertCompiles(List.of("-proc:none", "-d", classes.toString()), List.of(source("com/acme/Rules", RULES),
                source("com/acme/rules/Limit", OTHER_CASE_LIMIT), source("com/acme/Base", BASE),
                source("com/acme/Outer", OUTER)));
        Files.delete(classes.resolve("com/acme/Base.class"));
        Path limit = classes.resolve("com/acme/rules/Limit.class");
        Files.copy(limit, limit.resolveSibling("Limit-1.class"));
    }

    /**
     * A class directory on a case-insensitive file system: a class file is found by its path with case ignored, and
     * defined under the name the lookup asked for, whatever the class in it is named.
     */
    private static final class CaseInsensitiveClassDirectory extends ClassLoader {

        CaseInsensitiveClassDirectory() {
            super(WrongNameNestedImportTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (Stream<Path> files = Files.walk(classes)) {
                Path file = files.filter(found -> classes.relativize(found).toString().replace('\\', '/')
                        .equalsIgnoreCase(path)).findFirst().orElseThrow(() -> new ClassNotFoundException(name));
                byte[] bytes = Files.readAllBytes(file);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static Class<?> resolve(ClassLoader loader, String name) {
        return withContextClassLoader(loader, () -> ImportResolver.resolve(name));
    }

    @Test
    @DisplayName("the class loader gives the JVM's own \"wrong name\" error for a class file in another case")
    void jvmReportsWrongName() {
        ClassLoader loader = new CaseInsensitiveClassDirectory();

        NoClassDefFoundError error = assertThrows(NoClassDefFoundError.class,
                () -> loader.loadClass("com.acme.Rules.Limit$Kind"));

        assertEquals("com/acme/Rules/Limit$Kind (wrong name: com/acme/rules/Limit$Kind)", error.getMessage());
    }

    @Test
    @DisplayName("a nested class whose name, as written, finds a class file in another case is imported")
    void wrongNameOnTheName() throws ClassNotFoundException {
        ClassLoader loader = new CaseInsensitiveClassDirectory();

        Class<?> imported = resolve(loader, "com.acme.Rules.Limit");

        assertSame(loader.loadClass("com.acme.Rules$Limit"), imported);
    }

    @Test
    @DisplayName("a nested class one of whose $ forms finds a class file in another case is imported")
    void wrongNameOnADollarForm() throws ClassNotFoundException {
        ClassLoader loader = new CaseInsensitiveClassDirectory();

        Class<?> imported = resolve(loader, "com.acme.Rules.Limit.Kind");

        assertSame(loader.loadClass("com.acme.Rules$Limit$Kind"), imported);
    }

    @Test
    @DisplayName("a name whose every form finds no class, or a class file in another case, is a package import")
    void wrongNameWithNoClassIsAPackage() {
        assertNull(resolve(new CaseInsensitiveClassDirectory(), "com.acme.RULES.limit"));
    }

    @Test
    @DisplayName("a name that finds a class file in another case and isn't a package name is rejected, with the JVM's"
            + " error among the causes")
    void wrongNameWithInvalidPackageNameRejected() {
        ClassLoader loader = new CaseInsensitiveClassDirectory();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolve(loader, "com.acme.Rules.Limit-1"));

        assertEquals("'com.acme.Rules.Limit-1' is neither a class nor a valid package name", ex.getMessage());
        Throwable cause = ex.getCause();
        while (cause != null && !(cause instanceof NoClassDefFoundError)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "no NoClassDefFoundError among the causes");
        assertEquals("com/acme/Rules/Limit-1 (wrong name: com/acme/rules/Limit)", cause.getMessage());
    }

    @Test
    @DisplayName("a nested class whose $ form exists but can't be loaded is still rejected")
    void unloadableNestedClassRejected() {
        ClassLoader loader = new CaseInsensitiveClassDirectory();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolve(loader, "com.acme.Outer.Sub"));

        assertTrue(ex.getMessage().startsWith("Can't import 'com.acme.Outer.Sub': the class exists but can't be"
                + " loaded: java.lang.NoClassDefFoundError: com/acme/Base"), ex.getMessage());
        assertInstanceOf(NoClassDefFoundError.class, ex.getCause());
    }
}
