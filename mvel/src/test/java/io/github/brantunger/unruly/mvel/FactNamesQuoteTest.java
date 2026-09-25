package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactNames escapes and shortens a fact name in its messages, as the engine's messages do")
class FactNamesQuoteTest {

    @Test
    @DisplayName("line breaks, tabs, control characters and line or paragraph separators are escaped")
    void controlCharactersEscaped() {
        String name = "a\nb\rc\td" + (char) 0 + "e" + (char) 0x2028 + "f" + (char) 0x2029 + "g" + (char) 0x7f;

        assertEquals("a\\nb\\rc\\td\\u0000e\\u2028f\\u2029g\\u007f", FactNames.quote(name));
        assertEquals("plain_name é", FactNames.quote("plain_name é"));
    }

    @Test
    @DisplayName("format characters, such as bidi controls and zero-width and tag characters, are escaped")
    void formatCharactersEscaped() {
        String name = "a" + (char) 0x202e + "b" + (char) 0x200b + "c" + (char) 0x200d + "d" + (char) 0xfeff + "e"
                + Character.toString(0xE0041);

        assertEquals("a\\u202eb\\u200bc\\u200dd\\ufeffe\\udb40\\udc41", FactNames.quote(name));
    }

    @Test
    @DisplayName("a lone surrogate is escaped, also at the end of a short name, and a name of 200 is kept whole")
    void loneSurrogateEscaped() {
        String beforeLast = "x".repeat(199);

        assertEquals("a\\ud800", FactNames.quote("a" + (char) 0xd800));
        assertEquals("a\\udc00b", FactNames.quote("a" + (char) 0xdc00 + "b"));
        assertEquals(beforeLast + "\\ud800", FactNames.quote(beforeLast + (char) 0xd800));
        assertEquals(beforeLast + "... (2 more characters)", FactNames.quote(beforeLast + (char) 0xd800 + "y"));
    }

    @Test
    @DisplayName("an escape is written as String.format writes it, for every UTF-16 unit")
    void escapeWrittenAsFormatWrites() {
        for (int unit = 0; unit <= Character.MAX_VALUE; unit++) {
            StringBuilder written = new StringBuilder();

            FactNames.appendEscape(written, (char) unit);

            assertEquals(String.format("\\u%04x", unit), written.toString());
        }
    }

    @Test
    @DisplayName("a name is never shortened inside a surrogate pair")
    void longNameNotCutInsideSurrogatePair() {
        String kept = "x".repeat(199);

        assertEquals(kept + "... (6 more characters)", FactNames.quote(kept + Character.toString(0x1F600) + "tail"));
        assertEquals(kept + "... (2 more characters)", FactNames.quote(kept + Character.toString(0xE0041)));
    }

    @Test
    @DisplayName("a name over 200 characters is shortened, saying how many characters were left out")
    void longNameShortened() {
        String limit = "x".repeat(200);

        assertEquals(limit, FactNames.quote(limit));
        assertEquals(limit + "... (3 more characters)", FactNames.quote(limit + "yyy"));
    }

    @Test
    @DisplayName("a reserved name is quoted in its message too")
    void reservedNameQuoted() {
        FactNames names = new FactNames(new Imports(java.util.Set.of(), java.util.Set.of(),
                FactNamesQuoteTest.class.getClassLoader()));
        String reserved = "empty";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> names.check(reserved));

        assertTrue(ex.getMessage().startsWith("'empty' cannot be used as a fact name"), ex.getMessage());
    }
}
