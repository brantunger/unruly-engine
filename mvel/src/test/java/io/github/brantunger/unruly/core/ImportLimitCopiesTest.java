package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.mvel.ImportsCopies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mvel.Imports} keeps its own copy of {@code core.ImportResolver.checkSize}, which bounds an import in a rule's
 * own text as the engine bounds its own imports, because the {@code mvel} package may not use
 * {@code core.ImportResolver}, which isn't public either: this test is in {@code core}'s package to reach it. The
 * copies reject the same imports with the same message, so a change to one that misses the other makes an engine
 * import and an inline one bounded differently. Every case here runs on both.
 */
@DisplayName("the engine's import size check and the copy in Imports accept and reject identically")
class ImportLimitCopiesTest {

    private static Stream<Arguments> copies() {
        return Stream.of(
                Arguments.of("core.ImportResolver", (Consumer<String>) ImportResolver::checkSize),
                Arguments.of("mvel.Imports", (Consumer<String>) ImportsCopies::checkSize));
    }

    /** An import, and the message both copies reject it with, or {@code null} if both accept it. */
    private static List<String[]> cases() {
        int length = ImportResolver.MAX_IMPORT_LENGTH;
        int parts = ImportResolver.MAX_IMPORT_PARTS;
        String emoji = Character.toString(0x1F600);
        return List.of(
                new String[]{"java.util", null},
                new String[]{"", null},
                new String[]{"a".repeat(length), null},
                new String[]{"a.".repeat(parts - 1) + "a", null},
                new String[]{".".repeat(parts - 1), null},
                new String[]{"a".repeat(length + 1), "Can't import '" + "a".repeat(200) + "... (801 more characters)':"
                        + " it has 1001 characters, and an import may have at most 1000"},
                new String[]{"a.".repeat(parts) + "a", "Can't import '" + "a.".repeat(parts) + "a': it has 65"
                        + " dot-separated parts, and an import may have at most 64"},
                new String[]{".".repeat(parts), "Can't import '" + ".".repeat(parts) + "': it has 65 dot-separated"
                        + " parts, and an import may have at most 64"},
                new String[]{"a.".repeat(1999) + "a", "Can't import '" + "a.".repeat(100) + "... (3799 more"
                        + " characters)': it has 3999 characters, and an import may have at most 1000"},
                new String[]{"a\n.".repeat(parts), "Can't import '" + "a\\n.".repeat(parts) + "': it has 65"
                        + " dot-separated parts, and an import may have at most 64"},
                new String[]{"a".repeat(199) + emoji + "a".repeat(801), "Can't import '" + "a".repeat(199)
                        + "... (803 more characters)': it has 1002 characters, and an import may have at most 1000"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("every case is accepted, or rejected with the same message")
    void bothCopiesAgree(String name, Consumer<String> checkSize) {
        for (String[] expected : cases()) {
            String message;
            try {
                checkSize.accept(expected[0]);
                message = null;
            } catch (IllegalArgumentException e) {
                message = e.getMessage();
            }
            assertEquals(expected[1], message, name + " checked an import of " + expected[0].length()
                    + " characters differently");
        }
    }
}
