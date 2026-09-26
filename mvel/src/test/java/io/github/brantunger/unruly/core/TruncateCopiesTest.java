package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.mvel.FactNamesCopies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mvel.FactNames} keeps its own copy of {@code core.Failures.truncate}, which shortens MVEL's issues, because
 * the {@code mvel} package may not use {@code core.Failures}, whose {@code truncate} isn't public either: this test is
 * in {@code core}'s package to reach it. The copies shorten text for the same messages, so a change to one that misses
 * the other makes the engine and MVEL shorten the same text differently. Every case here runs on both.
 */
@DisplayName("the engine's truncate and the copy in FactNames shorten text identically")
class TruncateCopiesTest {

    private static Stream<Arguments> copies() {
        return Stream.of(
                Arguments.of("core.Failures", (UnaryOperator<String>) Failures::truncate),
                Arguments.of("mvel.FactNames", (UnaryOperator<String>) FactNamesCopies::truncate));
    }

    /** A text, and what both copies must make of it. */
    private static List<String[]> cases() {
        int limit = Failures.MAX_DESCRIPTION_LENGTH;
        String full = "a".repeat(limit);
        String beforeLast = "a".repeat(limit - 1);
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
                new String[]{"\n".repeat(limit + 3), "\n".repeat(limit) + "... (3 more characters)"},
                new String[]{"a".repeat(limit - 2) + emoji, "a".repeat(limit - 2) + emoji},
                new String[]{beforeLast + emoji, beforeLast + "... (2 more characters)"},
                new String[]{beforeLast + emoji + "b", beforeLast + "... (3 more characters)"},
                new String[]{beforeLast + tag, beforeLast + "... (2 more characters)"},
                new String[]{beforeLast + high, beforeLast + high},
                new String[]{beforeLast + high + "b", beforeLast + "... (2 more characters)"},
                new String[]{full + low, full + "... (1 more characters)"},
                new String[]{beforeLast + low + "b", beforeLast + low + "... (1 more characters)"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("every case is shortened the same way")
    void bothCopiesAgree(String name, UnaryOperator<String> truncate) {
        for (String[] expected : cases()) {
            assertEquals(expected[1], truncate.apply(expected[0]), name + " shortened a text of "
                    + expected[0].length() + " characters differently");
        }
    }
}
