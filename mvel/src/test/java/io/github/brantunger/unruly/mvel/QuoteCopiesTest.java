package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.core.Failures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    @DisplayName("every UTF-16 unit on its own is kept, or escaped as String.format writes it")
    void everyUnitAlone(String name, UnaryOperator<String> quote) {
        for (int unit = 0; unit <= Character.MAX_VALUE; unit++) {
            int type = Character.getType(unit);
            boolean escaped = Character.isISOControl(unit) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                    || type == Character.SURROGATE;
            String expected = switch (unit) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> escaped ? String.format("\\u%04x", unit) : String.valueOf((char) unit);
            };

            assertEquals(expected, quote.apply(String.valueOf((char) unit)),
                    String.format("%s quoted U+%04X", name, unit));
        }
    }
}
