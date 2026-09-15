package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactNames caches class lookups within bounds")
class FactNamesTest {

    private static FactNames javaUtil(ClassLoader loader) {
        return new FactNames(new Imports(Set.of("java.util"), Set.of(), loader));
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
        // What a class directory on a case-insensitive file system does: pkg/date.class finds pkg/Date.class.
        URL found = dir.toUri().toURL();
        ClassLoader caseInsensitive = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
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
        FactNames names = new FactNames(new Imports(Set.of("pkg"), Set.of(), caseInsensitive));

        assertDoesNotThrow(() -> names.check("date"));
    }
}
