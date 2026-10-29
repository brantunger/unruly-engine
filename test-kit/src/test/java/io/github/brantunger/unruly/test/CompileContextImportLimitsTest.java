package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a compile context from the kit rejects an import an engine rejects, before a language sees it (#680,"
        + " #1271)")
class CompileContextImportLimitsTest {

    private final ClassLoader loader = getClass().getClassLoader();

    @Test
    @DisplayName("a package import with more than 64 dot-separated parts is rejected")
    void tooManyPartsRejected() {
        String name = "a.".repeat(64) + "a";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LanguageTestContexts.compile(Set.of(name), Set.of(), loader));

        assertEquals("Can't import '" + name + "': it has 65 dot-separated parts, and an import may have at most 64",
                ex.getMessage());
    }

    @Test
    @DisplayName("a package import longer than 1,000 characters is rejected, its name shortened")
    void tooLongRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LanguageTestContexts.compile(Set.of("a".repeat(1001)), Set.of(), loader));

        assertEquals("Can't import '" + "a".repeat(200) + "... (801 more characters)': it has 1001 characters, and an"
                + " import may have at most 1000", ex.getMessage());
    }

    // Each compile method that takes package imports, the one for a language included.
    private List<Executable> compiles(Set<String> packages) {
        return List.of(() -> LanguageTestContexts.compile(packages, Set.of(), loader),
                () -> LanguageTestContexts.compile(packages, Set.of(), loader, Object.class, Map.of()),
                () -> LanguageTestContexts.compile(packages, Set.of(), loader, Object.class, Map.of(), Map.of(), false),
                () -> LanguageTestContexts.compile(packages, Set.of(), loader, Object.class, Map.of(), Map.of(), false,
                        List.of()),
                () -> LanguageTestContexts.compile(new ToyExpressionLanguage(), packages, Set.of(), loader,
                        Object.class, Map.of(), Map.of(), false, List.of()));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"zq..b", ".zq", "zq.", " zq", "", "1zq", "zq-b", "zq/b", "zq\u00a7b", "java/util"})
    @DisplayName("a package import that isn't a valid package name is rejected by every compile method, with the"
            + " message build() gives and no cause")
    void notAPackageNameRejected(String name) {
        for (Executable compile : compiles(Set.of(name))) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, compile);

            assertEquals("'" + name + "' is neither a class nor a valid package name", ex.getMessage());
            assertNull(ex.getCause());
        }
    }

    @Test
    @DisplayName("a well-formed package import is kept by every compile method, whether or not the package exists")
    void wellFormedPackagesAccepted() {
        for (Executable compile : compiles(Set.of("com.zqnope", "java.util"))) {
            assertDoesNotThrow(compile);
        }
    }
}
