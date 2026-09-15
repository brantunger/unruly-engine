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
