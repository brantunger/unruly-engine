package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #704: MVEL's messages are shortened once, by the MVEL module, to fit the room the rest of the message leaves, so the
 * engine never cuts them again inside an escape or with a second count.
 */
@DisplayName("FactNames shortens text to fit a room once it's escaped, counting the text's own characters")
class FactNamesEscapeWithinTest {

    @Test
    @DisplayName("text that fits once escaped is escaped whole")
    void fitsWhole() {
        assertEquals("a\\nb", FactNames.escapeWithin("a\nb", 4));
        assertEquals("", FactNames.escapeWithin("", 0));
    }

    @Test
    @DisplayName("an escape is kept whole or left out, and the count is of the characters left out, not escaped ones")
    void escapeNotCut() {
        assertEquals("\\u200b... (9 more characters)", FactNames.escapeWithin("\u200b".repeat(10), 30));
    }

    @Test
    @DisplayName("a surrogate pair is kept whole or left out")
    void surrogatePairNotCut() {
        String text = "x".repeat(10) + Character.toString(0x1F600) + "y".repeat(30);

        assertEquals("x".repeat(10) + "... (32 more characters)", FactNames.escapeWithin(text, 35));
        assertEquals("x".repeat(10) + Character.toString(0x1F600) + "... (30 more characters)",
                FactNames.escapeWithin(text, 36));
    }

    @Test
    @DisplayName("a count one digit shorter leaves room for one more character")
    void shorterCountKeepsMore() {
        assertEquals("a".repeat(101) + "... (99 more characters)", FactNames.escapeWithin("a".repeat(200), 125));
    }

    @Test
    @DisplayName("a name is quoted as a name when that fits the room, and escaped within the room when it doesn't")
    void nameQuotedWithin() {
        String name = "n".repeat(300);

        assertEquals(FactNames.quote(name), FactNames.quoteWithin(name, 225));
        assertEquals("n".repeat(200) + "... (100 more characters)", FactNames.quoteWithin(name, 225));
        assertEquals("n".repeat(199) + "... (101 more characters)", FactNames.quoteWithin(name, 224));
    }

    // The whole name, 209 characters escaped as 879, fit in rooms of 879 to 892, where the quoted name, with its count
    // of the 9 left out, takes 893: more of the name was shown than quote() shows.
    @Test
    @DisplayName("a name escaped within a room shows no more of it than quoting it shows")
    void nameWithinShowsNoMoreThanQuoted() {
        String name = "\u200b".repeat(134) + ".".repeat(75);

        assertEquals(893, FactNames.quote(name).length());
        assertEquals("\\u200b".repeat(134) + ".".repeat(52) + "... (23 more characters)",
                FactNames.quoteWithin(name, 880));
        assertEquals("\\u200b".repeat(134) + ".".repeat(51) + "... (24 more characters)",
                FactNames.quoteWithin(name, 879));
    }

    @Test
    @DisplayName("a room too small for the count gets the count alone")
    void roomForCountAlone() {
        assertEquals("... (3 more characters)", FactNames.escapeWithin("a\nb", 3));
    }
}
