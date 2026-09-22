package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactNames looks class names up as MVEL does, within bounds")
class FactNamesTest {

    /** What GraalVM sets to tell code it is running in an image, which the check reads instead of the GraalVM SDK. */
    private static final String IMAGE_CODE = "org.graalvm.nativeimage.imagecode";

    private static FactNames javaUtil(ClassLoader loader) {
        return new FactNames(new Imports(Set.of("java.util"), Set.of(), loader));
    }

    /**
     * Runs {@code action} with the image-code property set to {@code value}. No JVM sets that property, so the
     * finally clears it rather than putting an earlier value back; leaving it set would follow every later test.
     */
    private static void withImageCode(String value, Runnable action) {
        System.setProperty(IMAGE_CODE, value);
        try {
            action.run();
        } finally {
            System.clearProperty(IMAGE_CODE);
        }
    }

    /**
     * A class loader that loads classes as the JDK's own do but serves no class file as a resource, which is what a
     * native image does with a class its resource configuration doesn't name. It records every name it is asked for.
     */
    private static ClassLoader servesNoClassFile(List<String> lookups) {
        return new ClassLoader(FactNamesTest.class.getClassLoader()) {
            @Override
            public URL getResource(String name) {
                lookups.add(name);
                return null;
            }
        };
    }

    /**
     * What a class directory on a case-insensitive file system does: pkg/date.class finds pkg/Date.class. It records
     * every name it is asked for, so a test can say whether the class file was looked up at all.
     */
    private static ClassLoader caseInsensitiveClassDirectory(URL found, List<String> lookups) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                lookups.add(name);
                return name.equals("pkg/date.class") ? found : null;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("pkg.date")) {
                    throw new NoClassDefFoundError("pkg/date (wrong name: pkg/Date)");
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test
    @DisplayName("a class name is looked up once and then answered from the cache")
    void classNameCached() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtil(loader);

        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalArgumentException.class, () -> names.check("Date"));
        }

        assertEquals(1, Collections.frequency(loader.resources, "java/util/Date.class"));
    }

    @Test
    @DisplayName("names that aren't classes are cached, and the cache is cleared when it is full")
    void missesBounded() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtil(loader);

        names.check("name0");
        names.check("name0");
        assertEquals(1, Collections.frequency(loader.resources, "java/util/name0.class"), "a miss is cached");

        for (int i = 1; i <= FactNames.MAX_CACHED_MISSES; i++) {
            names.check("name" + i);
        }
        names.check("name0");

        assertEquals(2, Collections.frequency(loader.resources, "java/util/name0.class"),
                "the full cache was cleared, so name0 is looked up again");
    }

    @Test
    @DisplayName("a class file that can't be loaded isn't a class, as MVEL's own lookup in an imported package treats it")
    void unloadableClassFile(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("broken"));
        Files.write(dir.resolve("broken").resolve("Thing.class"), new byte[] {1, 2, 3});

        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            FactNames names = new FactNames(new Imports(Set.of("broken"), Set.of(), loader));

            assertNotNull(loader.getResource("broken/Thing.class"));
            assertDoesNotThrow(() -> names.check("Thing"));
        }
    }

    @Test
    @DisplayName("a class file the loader lists but then can't find isn't a class")
    void listedButNotLoadable(@TempDir Path dir) throws IOException {
        URL found = dir.toUri().toURL();
        ClassLoader resourcesOnly = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals("pkg/Ghost.class") ? found : null;
            }
        };
        FactNames names = new FactNames(new Imports(Set.of("pkg"), Set.of(), resourcesOnly));

        assertDoesNotThrow(() -> names.check("Ghost"));
    }

    @Test
    @DisplayName("a class file found for a name that differs in case isn't a class")
    void classFileWithWrongName(@TempDir Path dir) throws IOException {
        List<String> lookups = new ArrayList<>();
        FactNames names = new FactNames(
                new Imports(Set.of("pkg"), Set.of(), caseInsensitiveClassDirectory(dir.toUri().toURL(), lookups)));

        assertDoesNotThrow(() -> names.check("date"));

        assertEquals(List.of("pkg/date.class"), lookups, "the class file is what the lookup found, wrong name and all");
    }

    @Test
    @DisplayName("in a native image a name is loaded without the class file being looked up, as MVEL loads it")
    void imageLoadsWithoutLookingTheClassFileUp() {
        List<String> lookups = new ArrayList<>();
        FactNames names = javaUtil(servesNoClassFile(lookups));

        withImageCode("runtime", () -> assertThrows(IllegalArgumentException.class, () -> names.check("Date"),
                "an image loads java.util.Date as MVEL does, so a fact named Date is rejected there too"));

        assertEquals(List.of(), lookups, "an image serves no class file, so a lookup would answer for every name");
    }

    @Test
    @DisplayName("while an image is being built the class file is looked up first, as on any other JVM")
    void imageBuildTimeLooksTheClassFileUpFirst() {
        List<String> lookups = new ArrayList<>();
        FactNames names = javaUtil(servesNoClassFile(lookups));

        // "buildtime" is an ordinary JVM loading the classes an image is built from, where the lock object the
        // lookup exists to avoid is as real as it is anywhere else.
        withImageCode("buildtime", () -> assertDoesNotThrow(() -> names.check("Date")));

        assertEquals(List.of("java/util/Date.class"), lookups, "only a run in an image skips the lookup");
    }

    @Test
    @DisplayName("in a native image a name that differs in case is ruled out by the load, with no class file looked up")
    void classFileWithWrongNameInImage(@TempDir Path dir) throws IOException {
        // The false match is ruled out by the load, not by the lookup, which is what lets an image skip the lookup
        // and stay protected from it. The same loader as the test above, so the two answers are comparable.
        List<String> lookups = new ArrayList<>();
        FactNames names = new FactNames(
                new Imports(Set.of("pkg"), Set.of(), caseInsensitiveClassDirectory(dir.toUri().toURL(), lookups)));

        withImageCode("runtime", () -> assertDoesNotThrow(() -> names.check("date")));

        assertEquals(List.of(), lookups, "an image asks for no class file, so its false match can't come from one");
    }

    @Test
    @DisplayName("a name the loader refuses with an error that isn't a linkage error isn't a class")
    void unregisteredClassIsntAClass(@TempDir Path dir) throws IOException {
        URL found = dir.toUri().toURL();
        ClassLoader unregistered = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals("pkg/Thing.class") ? found : null;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new NotRegisteredError(name + " isn't registered for reflection");
            }
        };
        FactNames names = new FactNames(new Imports(Set.of("pkg"), Set.of(), unregistered));

        assertDoesNotThrow(() -> names.check("Thing"),
                "the name reads as the fact, as MVEL's own lookup reads it, instead of failing the run");
    }

    @Test
    @DisplayName("the check doesn't read an error that means the JVM itself is failing as a name that isn't a class")
    void virtualMachineErrorIsRethrown(@TempDir Path dir) throws IOException {
        // What the check itself does, not what a caller of the engine sees: AbstractRulesEngine catches what escapes
        // a fact-name check, and only Failures.fatalError — which passes a StackOverflowError over — is rethrown, so
        // a caller reads an IllegalArgumentException naming the fact instead.
        URL found = dir.toUri().toURL();
        ClassLoader failing = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals("pkg/Thing.class") ? found : null;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new StackOverflowError("simulated");
            }
        };
        FactNames names = new FactNames(new Imports(Set.of("pkg"), Set.of(), failing));

        assertThrows(StackOverflowError.class, () -> names.check("Thing"));
    }

    /**
     * Stands in for a native image's {@code MissingReflectionRegistrationError}, which is an {@link Error} but not a
     * {@link LinkageError}, so the catch that reads a name it can't load as the fact has to name {@code Error} to
     * hold it.
     */
    private static final class NotRegisteredError extends Error {
        private static final long serialVersionUID = 1L;

        NotRegisteredError(String message) {
            super(message);
        }
    }
}
