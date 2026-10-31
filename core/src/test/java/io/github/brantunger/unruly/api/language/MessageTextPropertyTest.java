package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.PropertyInputs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import static io.github.brantunger.unruly.PropertyInputs.points;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1332: what {@link MessageText#quote}, {@link MessageText#escapeWithin(String, int, int)} and
 * {@link MessageText#quoteWithin} promise for any text, checked on random text at the edges of each limit (see
 * {@link PropertyInputs}). Whatever is shown is a prefix of the text: escaped, it reads back as that prefix, it is cut
 * between code points and never inside raw text that reads as an escape, and its note counts the rest. It keeps within
 * the limit, and text that fits is shown whole. The checks are the Javadoc's, not a copy of what the code returns; they
 * don't ask that the most text that would fit is shown, as a cut just after a lone high surrogate shows one character
 * fewer (#1306).
 */
@DisplayName("MessageText: any text is shown as an escaped prefix of it, within the limit (#1332)")
class MessageTextPropertyTest {

    private static final int CASES = 400;

    private static final int MAX_NAME_LENGTH = 200;

    // What every cut text ends with; no generated text holds a '(', so none ends in what reads as one.
    private static final Pattern NOTE = Pattern.compile("\\.\\.\\. \\((\\d+) more characters\\)$");

    private static LongStream seeds() {
        return PropertyInputs.seeds(CASES);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("quote, escapeWithin and quoteWithin show an escaped prefix of the text, cut between code points and "
            + "outside escapes, within their limits")
    void shownAsAnEscapedPrefix(long seed) {
        PropertyInputs inputs = new PropertyInputs(seed);
        String name = inputs.text(inputs.lengthAround(MAX_NAME_LENGTH));
        int quoted = check("quote", name, MessageText.quote(name), MAX_NAME_LENGTH, Integer.MAX_VALUE);
        if (name.length() <= MAX_NAME_LENGTH) {
            assertEquals(name.length(), quoted, () -> "quote shortened a name of at most 200: " + points(name));
        } else {
            assertTrue(quoted >= MAX_NAME_LENGTH - 5, () -> "quote kept " + quoted + " characters, more than 5 fewer "
                    + "than 200, of " + points(name));
        }

        int most = inputs.lengthAround(inputs.oneOf(1, 2, 5, 6, 7, 20));
        int room = inputs.below(10) == 0 ? -inputs.below(3) : inputs.lengthAround(inputs.oneOf(10, 23, 30, 60));
        String text = inputs.text(inputs.below(2) == 0 ? inputs.lengthAround(most) : inputs.below(40));
        int within = check("escapeWithin", text, MessageText.escapeWithin(text, most, room), most, room);
        String escaped = MessageText.escape(text);
        if (text.length() <= most && escaped.length() <= room) {
            assertEquals(escaped, MessageText.escapeWithin(text, most, room),
                    () -> "escapeWithin shortened text that fits: " + points(text));
        }
        assertEquals(text.length(), check("escapeWithin", text, MessageText.escapeWithin(text, Integer.MAX_VALUE,
                Integer.MAX_VALUE), Integer.MAX_VALUE, Integer.MAX_VALUE), () -> "escapeWithin shortened text with"
                + " no limit: " + points(text));
        assertTrue(within <= text.length());
        int alone = check("escapeWithin", text, MessageText.escapeWithin(text, most, Integer.MAX_VALUE), most,
                Integer.MAX_VALUE);
        assertTrue(alone >= Math.min(text.length(), most) - 5, () -> "escapeWithin with no room limit kept " + alone
                + " characters, more than 5 fewer than " + most + ", of " + points(text));

        int nameRoom = inputs.lengthAround(inputs.oneOf(30, 220, 400));
        int shownWithin = check("quoteWithin", name, MessageText.quoteWithin(name, nameRoom), MAX_NAME_LENGTH,
                nameRoom);
        assertTrue(shownWithin <= quoted, () -> "quoteWithin showed more than quote: " + points(name));
    }

    /**
     * Checks what a method showed of a text, and returns how many of its characters it showed.
     *
     * @param method What showed it
     * @param text   The text, raw
     * @param shown  What the method returned
     * @param most   The most characters of the text it may show
     * @param room   The most characters it may return, unless that can't hold the note alone
     * @return How many characters of the text it showed
     */
    private static int check(String method, String text, String shown, int most, int room) {
        String about = method + " of " + points(text) + " (most " + most + ", room " + room + ") gave '"
                + points(shown) + "': ";
        Matcher note = NOTE.matcher(shown);
        int left = note.find() ? Integer.parseInt(note.group(1)) : 0;
        String escaped = left == 0 ? shown : shown.substring(0, note.start());
        assertTrue(left <= text.length(), about + "the note counts more characters than the text has");
        int kept = text.length() - left;
        String prefix = text.substring(0, kept);

        assertTrue(kept <= most, about + "more characters than the most shown");
        assertTrue(shown.length() <= Math.max(room, 0) || escaped.isEmpty(), about + "longer than the room");
        assertFalse(kept > 0 && kept < text.length() && Character.isHighSurrogate(text.charAt(kept - 1))
                && Character.isLowSurrogate(text.charAt(kept)), about + "cut inside a surrogate pair");
        assertFalse(insideEscape(text, kept), about + "cut inside raw text that reads as an escape");
        assertSafe(escaped, about);
        assertEquals(MessageText.escape(prefix), escaped, about + "the shown text isn't the escaped prefix");
        if (text.indexOf('\\') < 0) {
            assertEquals(prefix, unescape(escaped), about + "the shown text doesn't read back as the prefix");
        }
        return kept;
    }

    // What the Javadoc says the escaped text never holds raw: a control, a line or paragraph separator, or a lone
    // surrogate.
    private static void assertSafe(String escaped, String about) {
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            assertFalse(Character.isISOControl(c) || c == '\u2028' || c == '\u2029', about + "a raw control at " + i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < escaped.length() && Character.isLowSurrogate(escaped.charAt(i + 1)),
                        about + "a lone high surrogate at " + i);
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c), about + "a lone low surrogate at " + i);
            }
        }
    }

    // Whether a cut at 'at' falls inside raw text that reads as an escape, as the Javadoc lists them: a backslash and
    // n, r or t, or a backslash, u and four lowercase hex digits.
    private static boolean insideEscape(String text, int at) {
        for (int start = Math.max(0, at - 5); start < at; start++) {
            if (text.charAt(start) == '\\' && start + escapeLength(text, start) > at) {
                return true;
            }
        }
        return false;
    }

    private static int escapeLength(String text, int at) {
        if (at + 1 < text.length() && "nrt".indexOf(text.charAt(at + 1)) >= 0) {
            return 2;
        }
        if (at + 6 > text.length() || text.charAt(at + 1) != 'u') {
            return 0;
        }
        for (int i = at + 2; i < at + 6; i++) {
            char digit = text.charAt(i);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) {
                return 0;
            }
        }
        return 6;
    }

    // Reads escaped text back, for text that held no backslash, so every one in it starts an escape.
    private static String unescape(String escaped) {
        StringBuilder text = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c != '\\') {
                text.append(c);
                continue;
            }
            char kind = escaped.charAt(i + 1);
            switch (kind) {
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                default -> {
                    assertEquals('u', kind, "an escape that is none of \\n, \\r, \\t or \\u: " + points(escaped));
                    text.append((char) Integer.parseInt(escaped.substring(i + 2, i + 6), 16));
                    i += 4;
                }
            }
            i++;
        }
        return text.toString();
    }
}
