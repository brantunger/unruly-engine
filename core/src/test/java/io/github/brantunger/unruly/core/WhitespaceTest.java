package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.internal.Whitespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1292: the engine's own blank check, {@link Whitespace#isBlank}, which a rule's name and tags, a run's tags, a
 * language's name and a fact's name are checked with, so that a name that starts with a character above U+00FF reads
 * none of the JDK's character tables before any engine is built. Its answer must be {@link String#isBlank()}'s for
 * every string, so every code point on its own, and the combinations of the characters at the edges of the rule, are
 * compared with the running JDK's: a JDK whose Unicode version counts a new character as whitespace fails here, until
 * the comparisons in {@code Whitespace} are updated.
 */
@DisplayName("Whitespace: the engine's blank check answers as String.isBlank() does (#1292)")
class WhitespaceTest {

    // The characters at the edges of the rule: the two the JDK's code tests first, the no-break spaces, which aren't
    // whitespace, a zero-width space and a byte order mark, which are format characters, a Greek and a Latin-1 letter,
    // ASCII, the whitespace above U+00FF at the ends of its ranges, and each kind of lone surrogate.
    private static final List<String> EDGES = List.of("", " ", "\t", "\u00a0", "\u2007", "\u200b", "\u202f", "\ufeff",
            "\u03c0", "\u00e9", "a", "\ud800", "\udc00", "\u1680", "\u2000", "\u2006", "\u2008", "\u200a", "\u2028",
            "\u2029", "\u205f", "\u3000");

    @Test
    @DisplayName("every code point on its own is blank here exactly when String.isBlank() says it is")
    void everyCodePointAlone() {
        for (int point = 0; point <= Character.MAX_CODE_POINT; point++) {
            String alone = Character.toString(point);
            boolean expected = alone.isBlank();
            if (Whitespace.isBlank(alone) != expected) {
                fail(String.format("U+%04X is %s to String.isBlank() and not here (if this JDK's Unicode version is"
                        + " newer than 17.0, the comparisons in Whitespace.isWideWhitespace need updating, #1292)",
                        point, expected ? "blank" : "not blank"));
            }
        }
    }

    @Test
    @DisplayName("every pair and triple of the characters at the edges of the rule is blank here exactly when "
            + "String.isBlank() says it is")
    void edgeCombinations() {
        for (String first : EDGES) {
            for (String second : EDGES) {
                check(first + second);
                for (String third : EDGES) {
                    check(first + second + third);
                }
            }
        }
    }

    private static void check(String text) {
        boolean expected = text.isBlank();
        if (Whitespace.isBlank(text) != expected) {
            StringBuilder points = new StringBuilder();
            text.codePoints().forEach(point -> points.append(String.format(" U+%04X", point)));
            fail(String.format("'%s' is %s to String.isBlank() and not here (if this JDK's Unicode version is newer"
                    + " than 17.0, the comparisons in Whitespace.isWideWhitespace need updating, #1292)",
                    points.toString().trim(), expected ? "blank" : "not blank"));
        }
    }
}
