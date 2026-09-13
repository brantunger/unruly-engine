package io.github.brantunger.unruly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MVEL is only used through its expression language")
class MvelIsolationTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "io", "github", "brantunger", "unruly");

    private static List<Path> sourceFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static boolean inMvelPackage(Path file) {
        return SOURCES.relativize(file).startsWith("mvel");
    }

    /** Lists the source files outside the mvel package that contain {@code text}, relative to the package root. */
    private static List<String> outsideMvelPackageContaining(String text) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : sourceFiles()) {
            if (!inMvelPackage(file) && Files.readString(file).contains(text)) {
                found.add(SOURCES.relativize(file).toString().replace('\\', '/'));
            }
        }
        return found;
    }

    @Test
    @DisplayName("only the mvel package uses the MVEL library")
    void mvelLibraryOnlyInMvelPackage() throws IOException {
        assertTrue(sourceFiles().stream().anyMatch(MvelIsolationTest::inMvelPackage), "no mvel package found");

        assertEquals(List.of(), outsideMvelPackageContaining("org.mvel2"));
    }

    @Test
    @DisplayName("only AbstractRulesEngine refers to the mvel package, to create the default language")
    void mvelPackageReferencedOnce() throws IOException {
        assertEquals(List.of("core/AbstractRulesEngine.java"),
                outsideMvelPackageContaining("io.github.brantunger.unruly.mvel"));
    }
}
