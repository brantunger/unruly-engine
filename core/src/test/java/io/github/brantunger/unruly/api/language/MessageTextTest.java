package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #728: the text helper a language shortens and escapes text with, as the engine does. What it returns is a contract
 * for 2.x, so every case here is written out rather than read from the engine.
 */
@DisplayName("MessageText: shortening and escaping text for a message, as the engine does")
class MessageTextTest {

    private static final int MAX_NAME_LENGTH = 200;

    private static final int MAX_TEXT_LENGTH = 1_000;

    // A backslash, u and four lowercase hex digits, written out so the source holds no escape javac reads.
    private static final String READS_AS_ESCAPE = "\\" + "u0041";

    /**
     * The code points with Unicode's {@code Default_Ignorable_Code_Point} property, which a viewer shows as nothing,
     * as {@code DerivedCoreProperties.txt} lists them (the same in Unicode 15.0 to 17.0), one line or range a line.
     */
    private static final String DEFAULT_IGNORABLE = """
            00AD
            034F
            061C
            115F..1160
            17B4..17B5
            180B..180D
            180E
            180F
            200B..200F
            202A..202E
            2060..2064
            2065
            2066..206F
            3164
            FE00..FE0F
            FEFF
            FFA0
            FFF0..FFF8
            1BCA0..1BCA3
            1D173..1D17A
            E0000
            E0001
            E0002..E001F
            E0020..E007F
            E0080..E00FF
            E0100..E01EF
            E01F0..E0FFF
            """;

    /** A name, and what {@link MessageText#quote} must make of it. */
    private static List<String[]> quoteCases() {
        String limit = "x".repeat(MAX_NAME_LENGTH);
        String beforeLast = "x".repeat(MAX_NAME_LENGTH - 1);
        String emoji = Character.toString(0x1F600);
        String tag = Character.toString(0xE0041);
        char high = (char) 0xd800;
        char low = (char) 0xdc00;
        return List.of(
                new String[]{"", ""},
                new String[]{"plain_name é", "plain_name é"},
                new String[]{"a\nb", "a\\nb"},
                new String[]{"a\r\nb", "a\\r\\nb"},
                new String[]{"a\tb", "a\\tb"},
                new String[]{"a" + (char) 0 + "b", "a\\u0000b"},
                new String[]{"a" + (char) 0x85 + "b", "a\\u0085b"},
                new String[]{"a" + (char) 0x2028 + "b", "a\\u2028b"},
                new String[]{"a" + (char) 0x2029 + "b", "a\\u2029b"},
                new String[]{"a" + (char) 0x7f + "b", "a\\u007fb"},
                new String[]{"a" + (char) 0xa0 + "b", "a\\u00a0b"},
                new String[]{"a" + (char) 0x3000 + "b", "a\\u3000b"},
                new String[]{limit, limit},
                new String[]{limit + "y", limit + "... (1 more characters)"},
                new String[]{"\n".repeat(MAX_NAME_LENGTH + 3),
                        "\\n".repeat(MAX_NAME_LENGTH) + "... (3 more characters)"},
                new String[]{"a" + (char) 0x202e + "b", "a\\u202eb"},
                new String[]{"a" + (char) 0x2066 + "b" + (char) 0x2069, "a\\u2066b\\u2069"},
                new String[]{"a" + (char) 0x200b + "b", "a\\u200bb"},
                new String[]{"a" + (char) 0x200d + "b", "a\\u200db"},
                new String[]{"a" + (char) 0xfeff + "b", "a\\ufeffb"},
                new String[]{"a" + tag + "b", "a\\udb40\\udc41b"},
                new String[]{"a" + (char) 0x3164, "a\\u3164"},
                new String[]{"" + (char) 0x2764 + (char) 0xfe0f, (char) 0x2764 + "\\ufe0f"},
                new String[]{"a" + Character.toString(0xE0100) + "b", "a\\udb40\\udd00b"},
                new String[]{"a" + emoji + "b", "a" + emoji + "b"},
                new String[]{"x".repeat(MAX_NAME_LENGTH - 2) + emoji, "x".repeat(MAX_NAME_LENGTH - 2) + emoji},
                new String[]{beforeLast + emoji + "tail", beforeLast + "... (6 more characters)"},
                new String[]{beforeLast + tag, beforeLast + "... (2 more characters)"},
                new String[]{"a" + high, "a\\ud800"},
                new String[]{"a" + low + "b", "a\\udc00b"},
                new String[]{"" + low + high, "\\udc00\\ud800"},
                new String[]{beforeLast + high, beforeLast + "\\ud800"},
                new String[]{beforeLast + high + "y", beforeLast + "... (2 more characters)"},
                new String[]{"x".repeat(MAX_NAME_LENGTH - 2) + READS_AS_ESCAPE + "y",
                        "x".repeat(MAX_NAME_LENGTH - 2) + "... (7 more characters)"});
    }

    /** A text, and what {@link MessageText#truncate} must make of it. */
    private static List<String[]> truncateCases() {
        String full = "a".repeat(MAX_TEXT_LENGTH);
        String beforeLast = "a".repeat(MAX_TEXT_LENGTH - 1);
        String emoji = Character.toString(0x1F600);
        String tag = Character.toString(0xE0041);
        char high = (char) 0xd800;
        char low = (char) 0xdc00;
        return List.of(
                new String[]{"", ""},
                new String[]{"a\nb", "a\nb"},
                new String[]{full, full},
                new String[]{full + "b", full + "... (1 more characters)"},
                new String[]{full + "b".repeat(99_000), full + "... (99000 more characters)"},
                new String[]{"\n".repeat(MAX_TEXT_LENGTH + 3),
                        "\n".repeat(MAX_TEXT_LENGTH) + "... (3 more characters)"},
                new String[]{"a".repeat(MAX_TEXT_LENGTH - 2) + emoji, "a".repeat(MAX_TEXT_LENGTH - 2) + emoji},
                new String[]{beforeLast + emoji, beforeLast + "... (2 more characters)"},
                new String[]{beforeLast + emoji + "b", beforeLast + "... (3 more characters)"},
                new String[]{beforeLast + tag, beforeLast + "... (2 more characters)"},
                new String[]{beforeLast + high, beforeLast + high},
                new String[]{beforeLast + high + "b", beforeLast + "... (2 more characters)"},
                new String[]{full + low, full + "... (1 more characters)"},
                new String[]{beforeLast + low + "b", beforeLast + low + "... (1 more characters)"});
    }

    @Test
    @DisplayName("quote shortens a name to 200 characters, then escapes it, in every case")
    void quoteCasesHold() {
        for (String[] expected : quoteCases()) {
            assertEquals(expected[1], MessageText.quote(expected[0]), "quoted " + expected[0] + " differently");
            assertEquals(expected[1], MessageText.escapeWithin(expected[0], MessageText.MAX_NAME_LENGTH,
                    Integer.MAX_VALUE), "escaped " + expected[0] + " to 200 characters differently from quote");
        }
    }

    @Test
    @DisplayName("truncate shortens text to 1,000 characters, never inside a surrogate pair, and doesn't escape it")
    void truncateCasesHold() {
        for (String[] expected : truncateCases()) {
            assertEquals(expected[1], MessageText.truncate(expected[0]), "shortened a text of "
                    + expected[0].length() + " characters differently");
        }
    }

    @Test
    @DisplayName("leftOut writes the note that follows shortened text, as truncate and quote end it (#1241)")
    void leftOutWritesTheNote() {
        assertEquals("... (1 more characters)", MessageText.leftOut(1));
        assertEquals("... (801 more characters)", MessageText.leftOut(801));
        assertEquals("... (0 more characters)", MessageText.leftOut(0));
        assertEquals("... (-1 more characters)", MessageText.leftOut(-1));
        String text = "x".repeat(MAX_TEXT_LENGTH + 801);
        assertEquals("x".repeat(MAX_TEXT_LENGTH) + MessageText.leftOut(801), MessageText.truncate(text));
        assertEquals("x".repeat(MAX_NAME_LENGTH) + MessageText.leftOut(1), MessageText.quote("x".repeat(201)));
    }

    @Test
    @DisplayName("every code point on its own is kept, or, if it is a control, line or paragraph separator, space "
            + "separator other than U+0020, format, surrogate or default-ignorable one, escaped as String.format "
            + "writes each of its UTF-16 units")
    void everyCodePointAlone() {
        BitSet ignorable = defaultIgnorable();
        for (int point = 0; point <= Character.MAX_CODE_POINT; point++) {
            int type = Character.getType(point);
            boolean escaped = Character.isISOControl(point) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                    || type == Character.SURROGATE || ignorable.get(point)
                    || (type == Character.SPACE_SEPARATOR && point != ' ');
            String expected = switch (point) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> escaped ? escapes(point) : Character.toString(point);
            };

            String alone = Character.toString(point);
            String quoted = MessageText.quote(alone);
            String written = MessageText.escape(alone);
            String fitted = MessageText.escapeWithin(alone, Integer.MAX_VALUE);
            String quotedWithin = MessageText.quoteWithin(alone, Integer.MAX_VALUE);
            if (!expected.equals(quoted) || !expected.equals(written) || !expected.equals(fitted)
                    || !expected.equals(quotedWithin)) {
                fail(String.format("quoted U+%04X as %s, escaped it as %s, within a room as %s and quoted it within "
                        + "a room as %s, not %s", point, quoted, written, fitted, quotedWithin, expected));
            }
        }
    }

    @Test
    @DisplayName("line breaks, tabs, control characters and line or paragraph separators are escaped")
    void controlCharactersEscaped() {
        String name = "a\nb\rc\td" + (char) 0 + "e" + (char) 0x2028 + "f" + (char) 0x2029 + "g" + (char) 0x7f;

        assertEquals("a\\nb\\rc\\td\\u0000e\\u2028f\\u2029g\\u007f", MessageText.quote(name));
        assertEquals("plain_name é", MessageText.quote("plain_name é"));
    }

    @Test
    @DisplayName("format characters, such as bidi controls and zero-width and tag characters, and the other characters "
            + "a viewer shows as nothing, such as fillers and variation selectors, are escaped")
    void formatCharactersEscaped() {
        String name = "a" + (char) 0x202e + "b" + (char) 0x200b + "c" + (char) 0x200d + "d" + (char) 0xfeff + "e"
                + Character.toString(0xE0041);
        String invisible = "a" + (char) 0x3164 + "b" + (char) 0x034f + "c" + (char) 0xfe0f + "d" + (char) 0x2065 + "e"
                + (char) 0xfff0 + "f" + Character.toString(0xE0100);

        assertEquals("a\\u202eb\\u200bc\\u200dd\\ufeffe\\udb40\\udc41", MessageText.quote(name));
        assertEquals("a\\u3164b\\u034fc\\ufe0fd\\u2065e\\ufff0f\\udb40\\udd00", MessageText.quote(invisible));
    }

    @Test
    @DisplayName("a lone surrogate is escaped, also at the end of a short name, and a name of 200 is kept whole")
    void loneSurrogateEscaped() {
        String beforeLast = "x".repeat(199);

        assertEquals("a\\ud800", MessageText.quote("a" + (char) 0xd800));
        assertEquals("a\\udc00b", MessageText.quote("a" + (char) 0xdc00 + "b"));
        assertEquals(beforeLast + "\\ud800", MessageText.quote(beforeLast + (char) 0xd800));
        assertEquals(beforeLast + "... (2 more characters)", MessageText.quote(beforeLast + (char) 0xd800 + "y"));
    }

    @Test
    @DisplayName("a name is never shortened inside a surrogate pair")
    void longNameNotCutInsideSurrogatePair() {
        String kept = "x".repeat(199);

        assertEquals(kept + "... (6 more characters)", MessageText.quote(kept + Character.toString(0x1F600) + "tail"));
        assertEquals(kept + "... (2 more characters)", MessageText.quote(kept + Character.toString(0xE0041)));
    }

    @Test
    @DisplayName("a name over 200 characters is shortened, saying how many characters were left out")
    void longNameShortened() {
        String limit = "x".repeat(200);

        assertEquals(limit, MessageText.quote(limit));
        assertEquals(limit + "... (3 more characters)", MessageText.quote(limit + "yyy"));
    }

    @Test
    @DisplayName("escape doesn't shorten, and escaping text again changes nothing")
    void escapeDoesNotShortenAndIsIdempotent() {
        String longText = "a\nb".repeat(2_000);

        assertEquals("a\\nb".repeat(2_000), MessageText.escape(longText));
        for (String[] expected : quoteCases()) {
            String once = MessageText.escape(expected[0]);

            assertEquals(once, MessageText.escape(once), "escaping " + expected[0] + " again changed it");
        }
    }

    @Test
    @DisplayName("null is rejected with a NullPointerException naming the argument")
    void nullRejected() {
        assertEquals("text must not be null",
                assertThrows(NullPointerException.class, () -> MessageText.escape(null)).getMessage());
        assertEquals("text must not be null",
                assertThrows(NullPointerException.class, () -> MessageText.truncate(null)).getMessage());
        assertEquals("name must not be null",
                assertThrows(NullPointerException.class, () -> MessageText.quote(null)).getMessage());
        assertEquals("text must not be null",
                assertThrows(NullPointerException.class, () -> MessageText.escapeWithin(null, 1)).getMessage());
        assertEquals("text must not be null",
                assertThrows(NullPointerException.class, () -> MessageText.escapeWithin(null, 1, 1)).getMessage());
        assertEquals("name must not be null",
                assertThrows(NullPointerException.class, () -> MessageText.quoteWithin(null, 1)).getMessage());
    }

    // #1152: the limits a language works out its room from. javac inlines them, so they can't change in 2.x.
    @Test
    @DisplayName("the published limits are 200 characters for a name and 1,000 for other text, as quote and truncate "
            + "keep")
    void limitsPublished() {
        String limit = "a".repeat(MessageText.MAX_TEXT_LENGTH);

        assertEquals(MAX_NAME_LENGTH, MessageText.MAX_NAME_LENGTH);
        assertEquals(MAX_TEXT_LENGTH, MessageText.MAX_TEXT_LENGTH);
        assertEquals(limit, MessageText.truncate(limit));
        assertEquals(limit + "... (1 more characters)", MessageText.truncate(limit + "b"));
        assertEquals("x".repeat(MessageText.MAX_NAME_LENGTH) + "... (1 more characters)",
                MessageText.quote("x".repeat(MessageText.MAX_NAME_LENGTH + 1)));
    }

    @Test
    @DisplayName("a cut inside \\n, \\r, \\t or a \\u escape the text holds leaves the escape out whole")
    void ownEscapesLeftOutWhole() {
        String rest = "c".repeat(30);

        assertEquals("ab... (36 more characters)", MessageText.escapeWithin("ab\\u0001" + rest, 29));
        assertEquals("ab... (36 more characters)", MessageText.escapeWithin("ab\\u0001" + rest, 31));
        assertEquals("ab... (32 more characters)", MessageText.escapeWithin("ab\\n" + rest, 27));
        assertEquals("ab... (32 more characters)", MessageText.escapeWithin("ab\\r" + rest, 27));
        assertEquals("ab... (32 more characters)", MessageText.escapeWithin("ab\\t" + rest, 27));
        assertEquals("\\u00e9... (36 more characters)",
                MessageText.escapeWithin("\\u00e9\\u00e9" + rest, 34));
    }

    @Test
    @DisplayName("a backslash that doesn't start an escape, or an escape that ends before the cut, is cut as any text")
    void otherBackslashesCutAsText() {
        String rest = "c".repeat(30);

        assertEquals("ab\\... (31 more characters)", MessageText.escapeWithin("ab\\q" + rest, 27));
        assertEquals("ab\\u0... (33 more characters)", MessageText.escapeWithin("ab\\u00g1" + rest, 29));
        assertEquals("ab\\u0... (33 more characters)", MessageText.escapeWithin("ab\\u00AB" + rest, 29));
        assertEquals("ab\\n... (31 more characters)", MessageText.escapeWithin("ab\\nc" + rest, 28));
        assertEquals("ab\\u", MessageText.escapeWithin("ab\\u", 4));
    }

    @Test
    @DisplayName("a backslash that ends the text is kept, whether the text fits or is cut after it")
    void backslashAtTheEnd() {
        assertEquals("ab\\", MessageText.escapeWithin("ab\\", 100));
        assertEquals("ab\\", MessageText.escapeWithin("ab\\", 3));
        assertEquals("ab\\... (30 more characters)", MessageText.escapeWithin("ab\\" + "c".repeat(30), 27));
    }

    @Test
    @DisplayName("text that fits once escaped is escaped whole")
    void fitsWhole() {
        assertEquals("a\\nb", MessageText.escapeWithin("a\nb", 4));
        assertEquals("", MessageText.escapeWithin("", 0));
    }

    @Test
    @DisplayName("an escape is kept whole or left out, and the count is of the characters left out, not escaped ones")
    void escapeNotCut() {
        assertEquals("\\u200b... (9 more characters)", MessageText.escapeWithin("\u200b".repeat(10), 30));
    }

    @Test
    @DisplayName("a surrogate pair is kept whole or left out")
    void surrogatePairNotCut() {
        String text = "x".repeat(10) + Character.toString(0x1F600) + "y".repeat(30);

        assertEquals("x".repeat(10) + "... (32 more characters)", MessageText.escapeWithin(text, 35));
        assertEquals("x".repeat(10) + Character.toString(0x1F600) + "... (30 more characters)",
                MessageText.escapeWithin(text, 36));
    }

    @Test
    @DisplayName("a count one digit shorter leaves room for one more character")
    void shorterCountKeepsMore() {
        assertEquals("a".repeat(101) + "... (99 more characters)", MessageText.escapeWithin("a".repeat(200), 125));
    }

    @Test
    @DisplayName("a room too small for the count gets the count alone")
    void roomForCountAlone() {
        assertEquals("... (3 more characters)", MessageText.escapeWithin("a\nb", 3));
    }

    @Test
    @DisplayName("a negative room is taken as no room, and empty text is escaped as empty whatever the room")
    void negativeRoom() {
        assertEquals("... (3 more characters)", MessageText.escapeWithin("abc", -5));
        assertEquals("", MessageText.escapeWithin("", -1));
        assertEquals("", MessageText.escapeWithin("", 5, -1));
        assertEquals("", MessageText.quoteWithin("", -1));
        assertEquals("... (3 more characters)", MessageText.quoteWithin("abc", -1));
    }

    @Test
    @DisplayName("text escaped within a room shows at most the first characters asked for, never inside a surrogate "
            + "pair or text that reads as an escape, and none when none are")
    void mostCharactersShown() {
        String emoji = Character.toString(0x1F600);

        assertEquals("abc... (3 more characters)", MessageText.escapeWithin("abcdef", 3, Integer.MAX_VALUE));
        assertEquals("abcdef", MessageText.escapeWithin("abcdef", 6, Integer.MAX_VALUE));
        assertEquals("abcdef", MessageText.escapeWithin("abcdef", 10, Integer.MAX_VALUE));
        assertEquals("a\\nb", MessageText.escapeWithin("a\nb", 3, 4));
        assertEquals("c".repeat(100) + "... (50 more characters)",
                MessageText.escapeWithin("c".repeat(150), 100, Integer.MAX_VALUE));
        assertEquals("ab... (8 more characters)",
                MessageText.escapeWithin("ab" + READS_AS_ESCAPE + "cd", 4, Integer.MAX_VALUE));
        assertEquals("x... (3 more characters)", MessageText.escapeWithin("x" + emoji + "y", 2, Integer.MAX_VALUE));
        assertEquals("... (3 more characters)", MessageText.escapeWithin("abc", 0, Integer.MAX_VALUE));
        assertEquals("... (3 more characters)", MessageText.escapeWithin("abc", -1, Integer.MAX_VALUE));
        // The room cuts it shorter than the most asked for: 6 characters and a count of 44 take 30.
        assertEquals("a".repeat(6) + "... (44 more characters)", MessageText.escapeWithin("a".repeat(50), 40, 30));
    }

    @Test
    @DisplayName("a name is quoted as a name when that fits the room, and escaped within the room when it doesn't")
    void nameQuotedWithin() {
        String name = "n".repeat(300);

        assertEquals(MessageText.quote(name), MessageText.quoteWithin(name, 225));
        assertEquals("n".repeat(200) + "... (100 more characters)", MessageText.quoteWithin(name, 225));
        assertEquals("n".repeat(199) + "... (101 more characters)", MessageText.quoteWithin(name, 224));
    }

    // The whole name, 209 characters escaped as 879, fit in rooms of 879 to 892, where the quoted name, with its count
    // of the 9 left out, takes 893: more of the name was shown than quote() shows.
    @Test
    @DisplayName("a name escaped within a room shows no more of it than quoting it shows")
    void nameWithinShowsNoMoreThanQuoted() {
        String name = "\u200b".repeat(134) + ".".repeat(75);

        assertEquals(893, MessageText.quote(name).length());
        assertEquals("\\u200b".repeat(134) + ".".repeat(52) + "... (23 more characters)",
                MessageText.quoteWithin(name, 880));
        assertEquals("\\u200b".repeat(134) + ".".repeat(51) + "... (24 more characters)",
                MessageText.quoteWithin(name, 879));
    }

    // Quoting shows 199 characters of this name, so escaping it within a room shows no more, though the whole name, of
    // 201 characters, would fit in 210.
    @Test
    @DisplayName("a name escaped within a room leaves out a surrogate pair its limit falls inside, as quoting it does, "
            + "and a short name is escaped within the room like any text")
    void nameWithinAtLimitsOfQuote() {
        String name = "x".repeat(199) + Character.toString(0x1F600);

        assertEquals(MessageText.quote(name), MessageText.quoteWithin(name, 222));
        assertEquals("x".repeat(186) + "... (15 more characters)", MessageText.quoteWithin(name, 210));
        assertEquals("\\u200b... (9 more characters)", MessageText.quoteWithin("\u200b".repeat(10), 30));
    }

    // #1121 with #1152: quoteWithin is quote wherever quote fits, and shows a first part of what quote shows where it
    // doesn't, also where the 200-character limit falls inside raw text that reads as an escape.
    @Test
    @DisplayName("a name quoted within a room is quoted as quote quotes it when that fits, and shows no more of it in "
            + "every smaller room")
    void quoteWithinAgreesWithQuote() {
        List<String> names = List.of("", "short\n", "n".repeat(300), "x".repeat(199) + Character.toString(0x1F600),
                "a".repeat(198) + READS_AS_ESCAPE + "b".repeat(10), "a".repeat(199) + "\\nb",
                "a".repeat(194) + READS_AS_ESCAPE + "b", "\u200b".repeat(134) + ".".repeat(75));
        for (String name : names) {
            String quoted = MessageText.quote(name);

            assertEquals(quoted, MessageText.quoteWithin(name, Integer.MAX_VALUE), name);
            assertEquals(quoted, MessageText.quoteWithin(name, quoted.length()), name);
            for (int room = quoted.length() - 1; room >= -1; room--) {
                String within = MessageText.quoteWithin(name, room);

                assertTrue(within.length() <= Math.max(room, 0) || within.startsWith("... ("), room + ": " + within);
                assertTrue(shown(quoted).startsWith(shown(within)), room + ": " + within + " shows more than "
                        + quoted);
            }
        }
    }

    /** The part of a quoted name before its count of what was left out, or all of it if it has none. */
    private static String shown(String quoted) {
        int note = quoted.lastIndexOf("... (");
        return note < 0 ? quoted : quoted.substring(0, note);
    }

    /** Each UTF-16 unit of a code point, as {@code String.format} writes its escape. */
    private static String escapes(int point) {
        StringBuilder written = new StringBuilder();
        for (char unit : Character.toChars(point)) {
            written.append(String.format("\\u%04x", (int) unit));
        }
        return written.toString();
    }

    /** The code points of {@link #DEFAULT_IGNORABLE}. */
    private static BitSet defaultIgnorable() {
        BitSet points = new BitSet(Character.MAX_CODE_POINT + 1);
        for (String line : DEFAULT_IGNORABLE.strip().split("\n")) {
            String[] range = line.split("\\.\\.");
            points.set(Integer.parseInt(range[0], 16), Integer.parseInt(range[range.length - 1], 16) + 1);
        }
        return points;
    }
}
