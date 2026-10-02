package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.MessageText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #704: MVEL's messages are shortened once, by the MVEL module, to fit the room the rest of the message leaves, so the
 * engine never cuts them again inside an escape or with a second count.
 */
@DisplayName("FactNames shortens text to fit a room once it's escaped, counting the text's own characters")
class FactNamesEscapeWithinTest {

    // A backslash that doesn't start a whole escape: an escape cut in half, in text with no backslash of its own.
    private static final Pattern CUT_ESCAPE = Pattern.compile("\\\\(?!u[0-9a-f]{4}|[nrt])");

    // #910: the class loader's refusal of a 2,100-character name quotes the name, so it holds 199 escapes already, and
    // is 1,301 characters long. A compile error whose root cause it is shortens it to the room causeNote leaves for it
    // at line 1, column 1, and to the 5 rooms below, where a cut by code point fell inside the last escape kept.
    @Test
    @DisplayName("an escape the text already holds is kept whole or left out, and the count counts the text as given")
    void refusalNotCutInsideItsEscapes() {
        ExactNameClassLoader loader = new ExactNameClassLoader(FactNamesEscapeWithinTest.class.getClassLoader());
        String refusal = assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("a" + "\u0001".repeat(199) + "a".repeat(1900))).getMessage();
        int room = FactNames.MAX_DESCRIPTION_LENGTH - "failed to compile at line 1, column 1: null".length()
                - (" (caused by " + ClassNotFoundException.class.getName() + ": )").length();
        assertEquals(1301, refusal.length());
        assertEquals(910, room);

        for (int within = room; within > room - 6; within--) {
            String cut = FactNames.escapeWithin(refusal, within);

            assertTrue(cut.length() <= within, within + ": " + cut);
            assertFalse(CUT_ESCAPE.matcher(cut).find(), within + ": " + cut);
            String kept = cut.substring(0, cut.lastIndexOf("... ("));
            assertEquals(kept + FactNames.leftOut(refusal.length() - kept.length()), cut, within + ": " + cut);
            assertTrue(refusal.startsWith(kept), within + ": " + cut);
        }
    }

    @Test
    @DisplayName("a cut inside \\n, \\r, \\t or a \\u escape the text holds leaves the escape out whole")
    void ownEscapesLeftOutWhole() {
        String rest = "c".repeat(30);

        assertEquals("ab... (36 more characters)", FactNames.escapeWithin("ab\\u0001" + rest, 29));
        assertEquals("ab... (32 more characters)", FactNames.escapeWithin("ab\\n" + rest, 27));
        assertEquals("ab... (32 more characters)", FactNames.escapeWithin("ab\\r" + rest, 27));
        assertEquals("ab... (32 more characters)", FactNames.escapeWithin("ab\\t" + rest, 27));
        assertEquals("\\u00e9... (36 more characters)", FactNames.escapeWithin("\\u00e9\\u00e9" + rest, 34));
    }

    @Test
    @DisplayName("a backslash that doesn't start an escape, or an escape that ends before the cut, is cut as any text")
    void otherBackslashesCutAsText() {
        String rest = "c".repeat(30);

        assertEquals("ab\\... (31 more characters)", FactNames.escapeWithin("ab\\q" + rest, 27));
        assertEquals("ab\\u0... (33 more characters)", FactNames.escapeWithin("ab\\u00g1" + rest, 29));
        assertEquals("ab\\u0... (33 more characters)", FactNames.escapeWithin("ab\\u00AB" + rest, 29));
        assertEquals("ab\\n... (31 more characters)", FactNames.escapeWithin("ab\\nc" + rest, 28));
        assertEquals("ab\\u", FactNames.escapeWithin("ab\\u", 4));
    }

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

        assertEquals(MessageText.quote(name), FactNames.quoteWithin(name, 225));
        assertEquals("n".repeat(200) + "... (100 more characters)", FactNames.quoteWithin(name, 225));
        assertEquals("n".repeat(199) + "... (101 more characters)", FactNames.quoteWithin(name, 224));
    }

    // The whole name, 209 characters escaped as 879, fit in rooms of 879 to 892, where the quoted name, with its count
    // of the 9 left out, takes 893: more of the name was shown than quote() shows.
    @Test
    @DisplayName("a name escaped within a room shows no more of it than quoting it shows")
    void nameWithinShowsNoMoreThanQuoted() {
        String name = "\u200b".repeat(134) + ".".repeat(75);

        assertEquals(893, MessageText.quote(name).length());
        assertEquals("\\u200b".repeat(134) + ".".repeat(52) + "... (23 more characters)",
                FactNames.quoteWithin(name, 880));
        assertEquals("\\u200b".repeat(134) + ".".repeat(51) + "... (24 more characters)",
                FactNames.quoteWithin(name, 879));
    }

    // Quoting shows 199 characters of this name, so escaping it within a room shows no more, though the whole name, of
    // 201 characters, would fit in 210.
    @Test
    @DisplayName("a name escaped within a room leaves out a surrogate pair its limit falls inside, as quoting it does, "
            + "and a short name is escaped within the room like any text")
    void nameWithinAtLimitsOfQuote() {
        String name = "x".repeat(199) + Character.toString(0x1F600);

        assertEquals(MessageText.quote(name), FactNames.quoteWithin(name, 222));
        assertEquals("x".repeat(186) + "... (15 more characters)", FactNames.quoteWithin(name, 210));
        assertEquals("\\u200b... (9 more characters)", FactNames.quoteWithin("\u200b".repeat(10), 30));
    }

    // The room MVEL fits its messages in is only right while it is the length the engine shortens a message to.
    @Test
    @DisplayName("the MVEL module's limit is the length the engine shortens text to")
    void sameLimitAsEngine() {
        String limit = "a".repeat(FactNames.MAX_DESCRIPTION_LENGTH);

        assertEquals(limit, MessageText.truncate(limit));
        assertEquals(limit + "... (1 more characters)", MessageText.truncate(limit + "b"));
    }

    @Test
    @DisplayName("a room too small for the count gets the count alone")
    void roomForCountAlone() {
        assertEquals("... (3 more characters)", FactNames.escapeWithin("a\nb", 3));
    }

    @Test
    @DisplayName("a message quotes a name within what the rest leaves, and a name that doesn't fit as the count alone "
            + "when the rest leaves too little room")
    void quotedWithinTheRest() {
        assertEquals("<n\\nm>", FactNames.quotedWithin("<", "n\nm", ">", 6));
        assertEquals("<a... (25 more characters)>", FactNames.quotedWithin("<", "abcdefghijklmnopqrstuvwxyz", ">", 27));
        // #875: the rest can leave less room than the count takes, or none at all, as a long class name after the name
        // does; an empty name, of which nothing is left out, is still quoted as empty.
        assertEquals("<... (50 more characters)>", FactNames.quotedWithin("<", "a".repeat(50), ">", 12));
        String after = "x".repeat(FactNames.MAX_DESCRIPTION_LENGTH);
        assertEquals("<... (3 more characters)" + after, FactNames.quotedWithin("<", "n\nm", after));
        assertEquals("<" + after, FactNames.quotedWithin("<", "", after));
    }
}
