package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a compile context rejects an import too long, with too many parts or not a package name, as build() does"
        + " (#680, #1271)")
class CompileContextImportLimitsTest {

    private final ClassLoader loader = getClass().getClassLoader();

    @Test
    @DisplayName("a package import with more than 64 dot-separated parts is rejected")
    void tooManyPartsRejected() {
        String name = "a.".repeat(64) + "a";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new EngineCompileContext(Set.of(name), Set.of(), loader));

        assertEquals("Can't import '" + "a.".repeat(64) + "a': it has 65 dot-separated parts, and an import may have"
                + " at most 64", ex.getMessage());
    }

    @Test
    @DisplayName("a package import longer than 1,000 characters is rejected, its name shortened")
    void tooLongRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new EngineCompileContext(Set.of("a".repeat(1001)), Set.of(), loader, Object.class, Map.of(),
                        Map.of(), false));

        assertEquals("Can't import '" + "a".repeat(200) + "... (801 more characters)': it has 1001 characters, and an"
                + " import may have at most 1000", ex.getMessage());
    }

    @Test
    @DisplayName("a package import of exactly 64 parts and one of exactly 1,000 characters are kept")
    void limitsAccepted() {
        Set<String> names = Set.of("a.".repeat(63) + "a", "a".repeat(1000));

        assertEquals(names, new EngineCompileContext(names, Set.of(), loader).packageImports());
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"zq..b", ".zq", "zq.", " zq", "", "1zq", "zq-b", "zq/b", "zq\u00a7b", "java/util"})
    @DisplayName("a package import that isn't a valid package name is rejected with build()'s message and no cause")
    void notAPackageNameRejected(String name) {
        IllegalArgumentException built = assertThrows(IllegalArgumentException.class,
                () -> RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                        .language(new ToyExpressionLanguage()).imports(name).build());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new EngineCompileContext(Set.of(name), Set.of(), loader));

        assertEquals("'" + name + "' is neither a class nor a valid package name", ex.getMessage());
        assertEquals(built.getMessage(), ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("a well-formed package import is kept, whether or not the package exists")
    void wellFormedPackagesAccepted() {
        Set<String> names = Set.of("com.zqnope", "java.util");

        assertEquals(names, new EngineCompileContext(names, Set.of(), loader).packageImports());
    }
}
