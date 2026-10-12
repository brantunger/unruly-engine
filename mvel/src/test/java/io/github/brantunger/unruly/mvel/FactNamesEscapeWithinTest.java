package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.MessageText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #704: MVEL's messages are shortened once, by the MVEL module, to fit the room the rest of the message leaves, so the
 * engine never cuts them again inside an escape or with a second count. The cut itself is MessageText's (#1152), and
 * MessageTextTest tests it; these cases are MVEL's own.
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
        int room = MessageText.MAX_TEXT_LENGTH - "failed to compile at line 1, column 1: null".length()
                - (" (caused by " + ClassNotFoundException.class.getName() + ": )").length();
        assertEquals(1301, refusal.length());
        assertEquals(910, room);

        for (int within = room; within > room - 6; within--) {
            String cut = MessageText.escapeWithin(refusal, within);

            assertTrue(cut.length() <= within, within + ": " + cut);
            assertFalse(CUT_ESCAPE.matcher(cut).find(), within + ": " + cut);
            String kept = cut.substring(0, cut.lastIndexOf("... ("));
            assertEquals(kept + FactNames.leftOut(refusal.length() - kept.length()), cut, within + ": " + cut);
            assertTrue(refusal.startsWith(kept), within + ": " + cut);
        }
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
        String after = "x".repeat(MessageText.MAX_TEXT_LENGTH);
        assertEquals("<... (3 more characters)" + after, FactNames.quotedWithin("<", "n\nm", after));
        assertEquals("<" + after, FactNames.quotedWithin("<", "", after));
    }
}
