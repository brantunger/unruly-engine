package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a package name is read by code point, so a letter outside the BMP is a letter (#679)")
class SupplementaryPackageNameTest {

    private static RulesEngineBuilder<Map<String, Object>> importing(String name) {
        return RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).imports(name);
    }

    // U+1D49C MATHEMATICAL SCRIPT CAPITAL A, a letter Java allows anywhere in an identifier.
    @ParameterizedTest(name = "letter {index}: {0}")
    @ValueSource(strings = {"\uD835\uDC9C", "a.\uD835\uDC9Cb", "\uD835\uDC9C.x", "a\uD835\uDC9C.b\uD835\uDC9C"})
    @DisplayName("a package name with a supplementary letter is imported")
    void supplementaryLetterAccepted(String name) {
        assertDoesNotThrow(() -> importing(name).build().close());
    }

    // A lone high surrogate is half a character, and U+1F600 GRINNING FACE is a character but not a letter.
    @ParameterizedTest(name = "not a letter {index}: {0}")
    @ValueSource(strings = {"\uD835", "a.\uD835", "\uD835.b", "\uD83D\uDE00", "a.\uD83D\uDE00"})
    @DisplayName("half a surrogate pair, or a supplementary character that isn't a letter, is still rejected")
    void nonLetterRejected(String name) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> importing(name).build());

        assertTrue(ex.getMessage().endsWith("' is neither a class nor a valid package name"), ex.getMessage());
    }
}
