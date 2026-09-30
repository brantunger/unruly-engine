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
                new String[]{beforeLast + high + "y", beforeLast + "... (2 more characters)"});
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
    @DisplayName("every code point on its own is kept, or, if it is a control, separator, format, surrogate or "
            + "default-ignorable one, escaped as String.format writes each of its UTF-16 units")
    void everyCodePointAlone() {
        BitSet ignorable = defaultIgnorable();
        for (int point = 0; point <= Character.MAX_CODE_POINT; point++) {
            int type = Character.getType(point);
            boolean escaped = Character.isISOControl(point) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                    || type == Character.SURROGATE || ignorable.get(point);
            String expected = switch (point) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> escaped ? escapes(point) : Character.toString(point);
            };

            String alone = Character.toString(point);
            String quoted = MessageText.quote(alone);
            String written = MessageText.escape(alone);
            if (!expected.equals(quoted) || !expected.equals(written)) {
                fail(String.format("quoted U+%04X as %s and escaped it as %s, not %s", point, quoted, written,
                        expected));
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
