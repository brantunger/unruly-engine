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
                        "\\n".repeat(MAX_NAME_LENGTH) + "... (3 more characters)"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("every case is escaped and shortened the same way")
    void bothCopiesAgree(String name, UnaryOperator<String> quote) {
        for (String[] expected : cases()) {
            assertEquals(expected[1], quote.apply(expected[0]), name + " quoted " + expected[0] + " differently");
        }
    }
}
