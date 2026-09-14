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
    @DisplayName("a class file that can't be loaded isn't a class")
    void unloadableClassFile(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("broken"));
        Files.write(dir.resolve("broken").resolve("Thing.class"), new byte[] {1, 2, 3});

        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            FactNames names = new FactNames(new Imports(Set.of("broken"), Set.of(), loader));

            assertNotNull(loader.getResource("broken/Thing.class"));
            assertDoesNotThrow(() -> names.check("Thing"));
        }
    }
}
