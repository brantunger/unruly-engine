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
 * {@code mvel.RealCaseInsensitiveClassDirectoryTest}, which needs a case-insensitive file system. The error the JVM
 * throws for a class the class looked up depends on, such as its superclass, names that class, so it isn't one about
 * the import's name (#993).
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

    private static final String DEP = """
            package com.acme;

            public class Dep {
            }
            """;

    private static final String NEEDS_DEP = """
            package com.acme;

            public class NeedsDep extends Dep {
            }
            """;

    private static final String HOLDER = """
            package com.acme;

            public class Holder {
                public static class In extends Dep {
                }
            }
            """;

    private static final String OTHER_CASE_DEP = """
            package com.acme;

            public class DEP {
            }
            """;

    @TempDir
    static Path classes;

    @TempDir
    static Path otherCaseClasses;

    /**
     * Compiles the classes once. {@code com.acme.Base} is then deleted, so {@code Outer.Sub} exists but can't be
     * loaded, and {@code com/acme/rules/Limit-1.class}, a copy of {@code Limit}'s class file, is a file whose name is
     * no class's. {@code com.acme.Dep} is replaced by {@code com/acme/DEP.class}, compiled apart, as the two files
     * would be one on a case-insensitive file system, so {@code NeedsDep} and {@code Holder.In} exist but their
     * superclass's lookup finds a class file in another case.
     */
    @BeforeAll
    static void compileClasses() throws IOException {
        assertCompiles(List.of("-proc:none", "-d", classes.toString()), List.of(source("com/acme/Rules", RULES),
                source("com/acme/rules/Limit", OTHER_CASE_LIMIT), source("com/acme/Base", BASE),
                source("com/acme/Outer", OUTER), source("com/acme/Dep", DEP), source("com/acme/NeedsDep", NEEDS_DEP),
                source("com/acme/Holder", HOLDER)));
        assertCompiles(List.of("-proc:none", "-d", otherCaseClasses.toString()),
                List.of(source("com/acme/DEP", OTHER_CASE_DEP)));
        Files.delete(classes.resolve("com/acme/Base.class"));
        Path limit = classes.resolve("com/acme/rules/Limit.class");
        Files.copy(limit, limit.resolveSibling("Limit-1.class"));
        Files.delete(classes.resolve("com/acme/Dep.class"));
        Files.copy(otherCaseClasses.resolve("com/acme/DEP.class"), classes.resolve("com/acme/DEP.class"));
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

    @Test
    @DisplayName("a class whose superclass's lookup finds a class file in another case is rejected, not taken for a"
            + " package (#993)")
    void wrongNameOfADependencyRejected() {
        ClassLoader loader = new CaseInsensitiveClassDirectory();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolve(loader, "com.acme.NeedsDep"));

        assertEquals("Can't import 'com.acme.NeedsDep': the class exists but can't be loaded:"
                + " java.lang.NoClassDefFoundError: com/acme/Dep (wrong name: com/acme/DEP)", ex.getMessage());
        assertInstanceOf(NoClassDefFoundError.class, ex.getCause());
    }

    @Test
    @DisplayName("a nested class whose $ form's superclass finds a class file in another case is rejected, not taken"
            + " for a package (#993)")
    void wrongNameOfANestedClassDependencyRejected() {
        ClassLoader loader = new CaseInsensitiveClassDirectory();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolve(loader, "com.acme.Holder.In"));

        assertEquals("Can't import 'com.acme.Holder.In': the class exists but can't be loaded:"
                + " java.lang.NoClassDefFoundError: com/acme/Dep (wrong name: com/acme/DEP)", ex.getMessage());
        assertInstanceOf(NoClassDefFoundError.class, ex.getCause());
    }
}
