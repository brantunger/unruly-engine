package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.PropertyInputs;
import io.github.brantunger.unruly.internal.Whitespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.LongStream;

import static io.github.brantunger.unruly.PropertyInputs.points;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1332: {@link Whitespace#isBlank} answers as its Javadoc says, {@link String#isBlank()}'s answer, for random strings
 * of whitespace and the characters next to it, up to 40 characters long (see {@link PropertyInputs}). Beside
 * {@link WhitespaceTest}, which tries every code point alone and the edge characters up to three at a time, this
 * tries longer strings, with a lone surrogate, a pair or any other character anywhere in them.
 */
@DisplayName("Whitespace: any string is blank here exactly when String.isBlank() and strip() say it is (#1332)")
class WhitespacePropertyTest {

    private static final int CASES = 400;

    private static LongStream seeds() {
        return PropertyInputs.seeds(CASES);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("a string is blank here exactly when String.isBlank() says so, strip() leaves nothing, and every "
            + "code point is Character.isWhitespace")
    void agreesWithTheJdk(long seed) {
        PropertyInputs inputs = new PropertyInputs(seed);
        for (int i = 0; i < 4; i++) {
            String text = inputs.mostlySpaces(40);
            boolean blank = Whitespace.isBlank(text);

            assertEquals(text.isBlank(), blank, () -> "String.isBlank() disagrees about " + points(text));
            assertEquals(text.strip().isEmpty(), blank, () -> "strip() disagrees about " + points(text));
            assertEquals(text.codePoints().allMatch(Character::isWhitespace), blank,
                    () -> "Character.isWhitespace disagrees about " + points(text));
        }
    }
}
