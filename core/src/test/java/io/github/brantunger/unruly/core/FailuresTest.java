package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
    @DisplayName("format characters a viewer shows, such as the Arabic number sign, stay escaped")
    void visibleFormatCharactersEscaped() {
        String text = "a" + (char) 0x0600 + "b" + (char) 0x0605 + "c" + (char) 0x06dd + "d" + (char) 0xfff9 + "e"
                + (char) 0xfffb + "f" + Character.toString(0x110BD);

        assertEquals("a\\u0600b\\u0605c\\u06ddd\\ufff9e\\ufffbf\\ud804\\udcbd", Failures.escape(text));
    }

    @Test
    @DisplayName("the other characters a viewer shows as nothing, such as fillers, variation selectors and unassigned "
            + "default-ignorable code points, are escaped")
    void defaultIgnorableCharactersEscaped() {
        String text = "a" + (char) 0x3164 + "b" + (char) 0xffa0 + "c" + (char) 0x115f + (char) 0x1160 + "d"
                + (char) 0x034f + "e" + (char) 0x17b4 + (char) 0x17b5 + "f" + (char) 0x180b + (char) 0x180d
                + (char) 0x180f + "g" + (char) 0x2065 + "h" + (char) 0xfff0 + (char) 0xfff8 + "i" + (char) 0x2764
                + (char) 0xfe0f + (char) 0xfe00;

        assertEquals("a\\u3164b\\uffa0c\\u115f\\u1160d\\u034fe\\u17b4\\u17b5f\\u180b\\u180d\\u180fg\\u2065h"
                + "\\ufff0\\ufff8i" + (char) 0x2764 + "\\ufe0f\\ufe00", Failures.escape(text));
    }

    @Test
    @DisplayName("the characters just outside each default-ignorable range, and past the last, are kept")
    void charactersBesideDefaultIgnorablesKept() {
        String text = "" + (char) 0x034e + (char) 0x0350 + (char) 0x115e + (char) 0x1161 + (char) 0x17b3
                + (char) 0x17b6 + (char) 0x180a + (char) 0x1810 + (char) 0x3163 + (char) 0x3165
                + (char) 0x4e00 + (char) 0xfdff + (char) 0xfe10 + (char) 0xff9f + (char) 0xffa1 + (char) 0xffef
                + Character.toString(0xDFFFF) + Character.toString(0xE1000) + Character.toString(0xF0000);

        assertEquals(text, Failures.escape(text));
    }

    @Test
    @DisplayName("a tag character or a variation selector, outside the Basic Multilingual Plane, is escaped as its two "
            + "UTF-16 units")
    void tagCharacterEscapedAsTwoUnits() {
        assertEquals("a\\udb40\\udc41b", Failures.escape("a" + TAG + "b"));
        assertEquals("a\\udb40\\udd00b\\udb40\\uddefc\\udb43\\udfff", Failures.escape("a" + Character.toString(0xE0100)
                + "b" + Character.toString(0xE01EF) + "c" + Character.toString(0xE0FFF)));
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

    @Test
    @DisplayName("a root cause is named when the first message has its message only past the 1,000 characters shown")
    void rootCauseNamedWhenShownOnlyPastTheCut() {
        RuntimeException root = new IllegalStateException("disk full");
        RuntimeException first = new RuntimeException("x".repeat(1500) + " disk full",
                new RuntimeException((String) null, root));

        assertEquals("x".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (510 more characters) (caused by "
                + "java.lang.IllegalStateException: disk full)", Failures.describe(first));
    }

    @Test
    @DisplayName("a root cause's message over 1,000 characters is named even when the first message has it whole, "
            + "because the description shows only part of it")
    void longRootCauseNamedEvenWhenQuotedWhole() {
        String rootMessage = "r".repeat(1200);
        RuntimeException root = new IllegalStateException(rootMessage);
        RuntimeException first = new RuntimeException("failed: " + rootMessage,
                new RuntimeException((String) null, root));

        assertEquals("failed: " + "r".repeat(992) + "... (208 more characters) (caused by "
                + "java.lang.IllegalStateException: " + "r".repeat(1000) + "... (200 more characters))",
                Failures.describe(first));
    }

    @Test
    @DisplayName("a root cause isn't named when the part of a long first message that is shown already has it")
    void rootCauseNotRepeatedWhenShownBeforeTheCut() {
        RuntimeException root = new IllegalStateException("no room");
        RuntimeException first = new RuntimeException("no room: " + "x".repeat(1500),
                new RuntimeException((String) null, root));

        assertEquals("no room: " + "x".repeat(991) + "... (509 more characters)", Failures.describe(first));
    }

    @Test
    @DisplayName("the note of what was left out of a long first message doesn't count as showing the root cause")
    void noteOfTheCutIsNotTheRootCause() {
        RuntimeException root = new IllegalStateException("more characters");
        RuntimeException first = new RuntimeException("x".repeat(1500), new RuntimeException((String) null, root));

        assertEquals("x".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (500 more characters) (caused by "
                + "java.lang.IllegalStateException: more characters)", Failures.describe(first));
    }

    @Test
    @DisplayName("a root cause is named when the half of a surrogate pair the cut leaves out would have shown it")
    void rootCauseNamedWhenOnlyTheCutHalfOfAPairHasIt() {
        RuntimeException root = new IllegalStateException("x\uD83D");
        RuntimeException first = new RuntimeException("a".repeat(998) + "x" + EMOJI + "tail",
                new RuntimeException((String) null, root));

        assertEquals("a".repeat(998) + "x... (6 more characters) (caused by java.lang.IllegalStateException: "
                + "x\\ud83d)", Failures.describe(first));
    }

    @Test
    @DisplayName("with its class, a root cause is named when the class name pushes its message past the cut")
    void rootCauseNamedWhenTheClassNamePushesItPastTheCut() {
        RuntimeException root = new IllegalStateException("disk full");
        RuntimeException first = new RuntimeException("x".repeat(990) + "disk full" + "x".repeat(500),
                new RuntimeException((String) null, root));

        assertEquals("java.lang.RuntimeException: " + "x".repeat(972) + "... (527 more characters) (caused by "
                + "java.lang.IllegalStateException: disk full)", Failures.describeWithClass(first));
    }

    @Test
    @DisplayName("with its class, a root cause is named when toString() leaves out the message that has it")
    void rootCauseNamedWhenToStringLeavesTheMessageOut() {
        RuntimeException root = new IllegalStateException("disk full");
        RuntimeException first = new RuntimeException("write failed: disk full",
                new RuntimeException((String) null, root)) {
            @Override
            public String toString() {
                return "write failed";
            }
        };

        assertEquals("write failed (caused by java.lang.IllegalStateException: disk full)",
                Failures.describeWithClass(first));
    }

    @Test
    @DisplayName("with its class, a root cause isn't named again when the toString() shown already has its message")
    void rootCauseNotRepeatedWhenToStringShowsIt() {
        RuntimeException root = new IllegalStateException("disk full");
        RuntimeException first = new RuntimeException("write failed: disk full",
                new RuntimeException((String) null, root));

        assertEquals("java.lang.RuntimeException: write failed: disk full", Failures.describeWithClass(first));
    }

    @Test
    @DisplayName("a root cause is named when a long first message of repeated text doesn't have its message")
    void rootCauseNamedBelowRepeatedText() {
        RuntimeException root = new IllegalStateException("a".repeat(600) + "b");
        RuntimeException first = new RuntimeException("a".repeat(1200), new RuntimeException((String) null, root));

        assertEquals("a".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (200 more characters) (caused by "
                + "java.lang.IllegalStateException: " + "a".repeat(600) + "b)", Failures.describe(first));
    }

    @Test
    @DisplayName("long messages of repeated text are described in bounded time, not in time that grows with their "
            + "square")
    void longRepeatedMessagesDescribedQuickly() {
        // Searching the whole first message for the root cause's took more than the limit at this length, and about
        // four times as long for each doubling; searching the part that is shown takes microseconds, so the limit
        // leaves room for a slow, busy machine.
        int length = 400_000;
        RuntimeException root = new IllegalStateException("a".repeat(length) + "b");
        RuntimeException first = new RuntimeException("a".repeat(2 * length),
                new RuntimeException((String) null, root));

        String description = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Failures.describe(first));

        assertEquals("a".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (799000 more characters) (caused by "
                + "java.lang.IllegalStateException: " + "a".repeat(1000) + "... (399001 more characters))",
                description);
    }
}
