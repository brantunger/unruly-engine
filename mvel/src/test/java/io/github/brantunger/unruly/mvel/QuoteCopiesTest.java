package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.core.Failures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.BitSet;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mvel.FactNames} keeps its own copy of {@code core.Failures.quote}, because the {@code mvel} package may use
 * only {@code api.exception} and {@code api.language}. The copies escape names for the same log files, so a change to
 * one that misses the other makes the engine and MVEL report the same name differently. Every case here runs on both.
 */
@DisplayName("the engine's quote and the copy in FactNames escape and shorten names identically")
class QuoteCopiesTest {

    private static final int MAX_NAME_LENGTH = 200;

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

    private static Stream<Arguments> copies() {
        return Stream.of(
                Arguments.of("core.Failures", (UnaryOperator<String>) Failures::quote),
                Arguments.of("mvel.FactNames", (UnaryOperator<String>) FactNames::quote));
    }

    /** A name, and what both copies must make of it. */
    private static List<String[]> cases() {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("every case is escaped and shortened the same way")
    void bothCopiesAgree(String name, UnaryOperator<String> quote) {
        for (String[] expected : cases()) {
            assertEquals(expected[1], quote.apply(expected[0]), name + " quoted " + expected[0] + " differently");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("every code point on its own is kept, or, if it is a control, separator, format, surrogate or "
            + "default-ignorable one, escaped as String.format writes each of its UTF-16 units")
    void everyCodePointAlone(String name, UnaryOperator<String> quote) {
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

            String quoted = quote.apply(Character.toString(point));
            if (!expected.equals(quoted)) {
                fail(String.format("%s quoted U+%04X as %s, not %s", name, point, quoted, expected));
            }
        }
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
