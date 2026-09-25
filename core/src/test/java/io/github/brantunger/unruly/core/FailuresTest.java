package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Failures.truncate keeps a message of up to 1,000 characters and shortens a longer one, and escape and "
        + "quote make text safe for a log line")
class FailuresTest {

    private static final String EMOJI = Character.toString(0x1F600);
    private static final String TAG = Character.toString(0xE0041);

    @Test
    @DisplayName("a message of exactly 1,000 characters is returned unchanged")
    void messageAtTheLimitUnchanged() {
        String message = "a".repeat(Failures.MAX_DESCRIPTION_LENGTH);

        assertEquals(message, Failures.truncate(message));
    }

    @Test
    @DisplayName("a message of 1,001 characters keeps its first 1,000 and says one more was left out")
    void messageOneOverTheLimitShortened() {
        String kept = "a".repeat(Failures.MAX_DESCRIPTION_LENGTH);

        assertEquals(kept + "... (1 more characters)", Failures.truncate(kept + "b"));
    }

    @Test
    @DisplayName("a message is never shortened inside a surrogate pair, and the count says what was really left out")
    void messageNotCutInsideSurrogatePair() {
        String kept = "a".repeat(Failures.MAX_DESCRIPTION_LENGTH - 1);

        String fits = "a".repeat(Failures.MAX_DESCRIPTION_LENGTH - 2) + EMOJI;

        assertEquals(fits, Failures.truncate(fits));
        assertEquals(kept + "... (2 more characters)", Failures.truncate(kept + EMOJI));
        assertEquals(kept + "... (3 more characters)", Failures.truncate(kept + EMOJI + "b"));
        assertEquals(kept + "... (2 more characters)", Failures.truncate(kept + TAG));
    }

    @Test
    @DisplayName("line breaks, tabs, control characters and line or paragraph separators are escaped as before")
    void controlCharactersEscaped() {
        String text = "a\nb\rc\td" + (char) 0 + "e" + (char) 0x85 + "f" + (char) 0x2028 + "g" + (char) 0x2029 + "h"
                + (char) 0x7f;

        assertEquals("a\\nb\\rc\\td\\u0000e\\u0085f\\u2028g\\u2029h\\u007f", Failures.escape(text));
    }

    @Test
    @DisplayName("format characters, such as bidi controls and zero-width characters, are escaped")
    void formatCharactersEscaped() {
        String text = "a" + (char) 0x202e + "b" + (char) 0x2066 + "c" + (char) 0x2069 + "d" + (char) 0x200b + "e"
                + (char) 0x200d + "f" + (char) 0xfeff + "g" + (char) 0xad;

        assertEquals("a\\u202eb\\u2066c\\u2069d\\u200be\\u200df\\ufeffg\\u00ad", Failures.escape(text));
    }

    @Test
    @DisplayName("a tag character, outside the Basic Multilingual Plane, is escaped as its two UTF-16 units")
    void tagCharacterEscapedAsTwoUnits() {
        assertEquals("a\\udb40\\udc41b", Failures.escape("a" + TAG + "b"));
    }

    @Test
    @DisplayName("a character outside the Basic Multilingual Plane that isn't a format character is kept")
    void emojiKept() {
        assertEquals("a" + EMOJI + "b", Failures.escape("a" + EMOJI + "b"));
    }

    @Test
    @DisplayName("a lone surrogate is escaped, whether high or low, while a surrogate pair is kept whole")
    void loneSurrogatesEscaped() {
        String text = "a" + (char) 0xd800 + "b" + (char) 0xdc00 + "c" + (char) 0xdc00 + (char) 0xd800 + EMOJI;

        assertEquals("a\\ud800b\\udc00c\\udc00\\ud800" + EMOJI, Failures.escape(text));
        assertEquals("a\\udbff", Failures.quote("a" + (char) 0xdbff));
    }

    @Test
    @DisplayName("an escape is written as String.format writes it, for every UTF-16 unit")
    void escapeWrittenAsFormatWrites() {
        for (int unit = 0; unit <= Character.MAX_VALUE; unit++) {
            StringBuilder written = new StringBuilder();

            Failures.appendEscape(written, (char) unit);

            assertEquals(String.format("\\u%04x", unit), written.toString());
        }
    }

    @Test
    @DisplayName("escaping text that has already been escaped changes nothing")
    void escapingTwiceChangesNothing() {
        String escaped = Failures.escape("a\n" + (char) 0x202e + TAG + EMOJI + (char) 0xd800);

        assertEquals(escaped, Failures.escape(escaped));
    }

    @Test
    @DisplayName("a name is never shortened inside a surrogate pair")
    void nameNotCutInsideSurrogatePair() {
        String kept = "x".repeat(Failures.MAX_NAME_LENGTH - 1);

        assertEquals(kept + "... (6 more characters)", Failures.quote(kept + EMOJI + "tail"));
        assertEquals(kept + "... (2 more characters)", Failures.quote(kept + TAG));
        assertEquals("a\\u202eb", Failures.quote("a" + (char) 0x202e + "b"));
    }
}
