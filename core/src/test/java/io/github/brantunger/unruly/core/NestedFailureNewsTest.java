package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An exception wrapped around a nested run's failure is news when it has a message of its own, one that can be read,
 * isn't its cause's {@code toString()} and isn't the nested failure's text, even one with words of its own around that
 * text. The chain is then no nested run's failure, so it's logged, and it's described by that message with the nested
 * failure as a note. Any other wrapper adds nothing, nor does one the engine built itself, whatever its message reads,
 * and the chain is named by the nested failure alone, as before.
 */
@DisplayName("a wrapper around a nested run's failure with a message of its own is news, and one without adds nothing")
class NestedFailureNewsTest {

    private static final String NAMED = "a nested run() failed: inner failed";

    private final ReportedFailure inner = new ReportedFailure("inner failed", null);

    /** An exception whose {@code getMessage()} throws, as a language's own can. */
    private static final class UnreadableMessage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableMessage(Throwable cause) {
            super(cause);
        }

        @Override
        public String getMessage() {
            throw new IllegalStateException("no message");
        }
    }

    /**
     * Runs {@code what} inside a run, after a nested run on the same thread logged {@code nested} and threw it as is.
     *
     * @param nested What the nested run logged
     * @param what   What to run while the thread's record of it lasts
     * @param <T>    What {@code what} returns
     * @return What {@code what} returned
     */
    private static <T> T afterNestedRunLogged(Throwable nested, Supplier<T> what) {
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.loggedByRun(nested);
            } finally {
                LoggedFailures.leave();
            }
            return what.get();
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a wrapper with a message of its own keeps it, with the nested failure as a note, and isn't nested")
    void wrapperWithAMessageOfItsOwn() {
        IllegalStateException wrapper = new IllegalStateException("fallback pricing failed for order 42", inner);

        assertEquals("fallback pricing failed for order 42 (after " + NAMED + ")", Failures.describe(wrapper));
        assertNull(Failures.nestedRunFailure(wrapper));
    }

    @Test
    @DisplayName("a long message of a wrapper's own is shortened as any other is, ahead of the nested failure's note")
    void wrapperMessageShortened() {
        IllegalStateException wrapper = new IllegalStateException("x".repeat(1_500), inner);

        assertEquals("x".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (500 more characters) (after " + NAMED + ")",
                Failures.describe(wrapper));
    }

    @Test
    @DisplayName("a long message of a wrapper's own around a fatal Error a nested run logged is shortened as any other"
            + " is")
    void wrapperMessageAroundALoggedErrorShortened() {
        OutOfMemoryError oom = new OutOfMemoryError("nested oom");
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                assertTrue(LoggedFailures.unloggedFatal(oom));
            } finally {
                LoggedFailures.leave();
            }

            assertEquals("x".repeat(Failures.MAX_DESCRIPTION_LENGTH) + "... (500 more characters) (after a nested run()"
                    + " failed: java.lang.OutOfMemoryError: nested oom)",
                    Failures.describe(new IllegalStateException("x".repeat(1_500), oom)));
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a nested engine failure is noted by its message alone, which already names a root cause without a"
            + " message")
    void engineFailureNotedByItsMessage() {
        ReportedFailure nested = new ReportedFailure("Failed to execute action for rule 'inner': "
                + "java.lang.IllegalStateException", new IllegalStateException());

        assertEquals("audit failed (after a nested run() failed: " + nested.getMessage() + ")",
                Failures.describe(new IllegalStateException("audit failed", nested)));
    }

    @Test
    @DisplayName("a wrapper whose cause's toString() throws still has a message of its own")
    void causeWhoseTextCantBeRead() {
        IllegalStateException wrapper = new IllegalStateException("fallback", new UnreadableMessage(inner));

        assertEquals("fallback (after " + NAMED + ")", Failures.describe(wrapper));
    }

    @Test
    @DisplayName("the first wrapper with a message of its own is described, under one that adds nothing")
    void newsBelowATransparentWrapper() {
        RuntimeException wrapper = new RuntimeException(new IllegalStateException("fallback", inner));

        assertEquals("fallback (after " + NAMED + ")", Failures.describe(wrapper));
        assertNull(Failures.nestedRunFailure(wrapper));
    }

    @Test
    @DisplayName("a wrapper with no message, one that can't be read, its cause's toString() or the nested text adds "
            + "nothing")
    void wrappersThatAddNothing() {
        for (Throwable wrapper : List.of(new IllegalStateException((String) null, inner), new UnreadableMessage(inner),
                new IllegalStateException(inner), new IllegalStateException("inner failed", inner))) {
            assertEquals(NAMED, Failures.describe(wrapper), wrapper.getClass().getName());
            assertSame(inner, Failures.nestedRunFailure(wrapper));
        }
    }

    @Test
    @DisplayName("a wrapper that puts words of its own before or after the nested failure's text is news")
    void wordsAroundTheNestedText() {
        IllegalStateException prefix = new IllegalStateException("audit: inner failed", inner);
        IllegalStateException suffix = new IllegalStateException("inner failed (retried 3 times)", inner);

        assertEquals("audit: inner failed (after " + NAMED + ")", Failures.describe(prefix));
        assertNull(Failures.nestedRunFailure(prefix));
        assertEquals("inner failed (retried 3 times) (after " + NAMED + ")", Failures.describe(suffix));
        assertNull(Failures.nestedRunFailure(suffix));
    }

    @Test
    @DisplayName("a wrapper whose message is the nested failure's text as it is, or escaped as the engine logged it, "
            + "adds nothing, and one with words around it is news")
    void nestedTextRawOrEscaped() {
        IllegalArgumentException nested = new IllegalArgumentException("bad\nname");

        afterNestedRunLogged(nested, () -> {
            assertEquals("a nested run() failed: bad\\nname",
                    Failures.describe(new IllegalStateException("bad\nname", nested)));
            assertEquals("a nested run() failed: bad\\nname",
                    Failures.describe(new IllegalStateException("bad\\nname", nested)));
            // Not its cause's message: the nested failure's own, one link further down.
            assertEquals("a nested run() failed: bad\\nname",
                    Failures.describe(new IllegalStateException("bad\nname", new RuntimeException(nested))));
            assertEquals("audit: bad\\nname (after a nested run() failed: bad\\nname)",
                    Failures.describe(new IllegalStateException("audit: bad\nname", nested)));
            assertEquals("audit: bad\\nname (after a nested run() failed: bad\\nname)",
                    Failures.describe(new IllegalStateException("audit: bad\\nname", nested)));
            return null;
        });
    }

    @Test
    @DisplayName("a nested failure with no message, or an empty one, never counts as in a wrapper's message")
    void missingOrEmptyNestedText() {
        IllegalArgumentException none = new IllegalArgumentException();
        IllegalArgumentException empty = new IllegalArgumentException("");

        afterNestedRunLogged(none, () -> {
            assertEquals("oops (after a nested run() failed: java.lang.IllegalArgumentException)",
                    Failures.describe(new IllegalStateException("oops", none)));
            return null;
        });
        afterNestedRunLogged(empty, () -> {
            assertEquals("oops (after a nested run() failed: )",
                    Failures.describe(new IllegalStateException("oops", empty)));
            return null;
        });
    }

    @Test
    @DisplayName("the nested failure in a note is shortened, and escaped, and names a hidden root cause once")
    void noteShortened() {
        ReportedFailure longer = new ReportedFailure("m".repeat(1_100), null);
        IllegalArgumentException hidden = new IllegalArgumentException(null, new IOException("disk\nfull"));

        assertEquals("fallback (after a nested run() failed: " + "m".repeat(1_000) + "... (100 more characters))",
                Failures.describe(new IllegalStateException("fallback", longer)));
        afterNestedRunLogged(hidden, () -> {
            assertEquals("fall\\nback (after a nested run() failed: java.lang.IllegalArgumentException (caused by "
                    + "java.io.IOException: disk\\nfull))",
                    Failures.describe(new IllegalStateException("fall\nback", hidden)));
            return null;
        });
    }

    @Test
    @DisplayName("a failure whose cause has news names itself, so a run around it keeps the news too")
    void failureAroundNewsNamesItself() {
        ReportedFailure middle = new ReportedFailure("middle failed: fallback (after " + NAMED + ")",
                new IllegalStateException("fallback", inner));
        ReportedFailure plain = new ReportedFailure("middle failed", new IllegalStateException(inner));

        assertSame(middle, Failures.nestedRunFailure(middle));
        assertEquals("a nested run() failed: " + middle.getMessage(),
                Failures.describe(new IllegalStateException(middle)));
        assertSame(inner, Failures.nestedRunFailure(plain));
    }

    @Test
    @DisplayName("a fatal Error has news above it only where a wrapper has a message of its own")
    void newsAboveAFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("heap");
        OutOfMemoryError withCause = new OutOfMemoryError("heap");
        withCause.initCause(new IOException("below"));

        IllegalStateException cleanup = new IllegalStateException("cleanup failed", oom);
        assertSame(cleanup, Failures.newsAbove(new RuntimeException(cleanup), oom));
        assertNull(Failures.newsAbove(new IllegalStateException(oom), oom));
        assertNull(Failures.newsAbove(oom, oom));
        assertNull(Failures.newsAbove(withCause, withCause));
    }

    @Test
    @DisplayName("a fatal Error is logged already only while a run in progress logged it, and nothing news is above it")
    void fatalLoggedAlready() {
        OutOfMemoryError oom = new OutOfMemoryError("heap");

        assertFalse(LoggedFailures.logged(oom), "no run in progress");
        LoggedFailures.enter();
        try {
            assertFalse(LoggedFailures.logged(oom), "not logged yet");
            assertTrue(LoggedFailures.unloggedFatal(oom));
            assertTrue(LoggedFailures.logged(oom));
            assertTrue(LoggedFailures.logged(new IllegalStateException(oom)));
            assertFalse(LoggedFailures.logged(new IllegalStateException("cleanup failed", oom)));
            assertTrue(LoggedFailures.logged(new IllegalStateException(inner)));
            assertFalse(LoggedFailures.logged(new IllegalStateException("cleanup failed", inner)));
            OutOfMemoryError another = new OutOfMemoryError("another");
            assertFalse(LoggedFailures.logged(another));
            assertFalse(LoggedFailures.unloggedFatal(oom), "logged() lost the one logged");
            assertTrue(LoggedFailures.unloggedFatal(another), "logged() recorded it");
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("the note is shortened before it's escaped, counting the characters as written, and keeps a note of a"
            + " hidden root cause the nested message already has only once")
    void noteShortenedBeforeEscaping() {
        IllegalArgumentException breaks = new IllegalArgumentException("\n".repeat(1_100));
        IllegalArgumentException named = new IllegalArgumentException("failed (caused by java.io.IOException: "
                + "disk\\nfull)", new IllegalStateException((String) null, new IOException("disk\nfull")));

        afterNestedRunLogged(breaks, () -> {
            assertEquals("oops (after a nested run() failed: " + "\\n".repeat(1_000) + "... (100 more characters))",
                    Failures.describe(new IllegalStateException("oops", breaks)));
            return null;
        });
        afterNestedRunLogged(named, () -> {
            assertEquals("oops (after a nested run() failed: failed (caused by java.io.IOException: disk\\nfull))",
                    Failures.describe(new IllegalStateException("oops", named)));
            return null;
        });
    }

    @Test
    @DisplayName("a hidden root cause's note is shortened with the message before either is escaped, so a cut never "
            + "falls inside an escape")
    void noteOfAHiddenCauseShortenedBeforeEscaping() {
        IllegalArgumentException nested = new IllegalArgumentException("m".repeat(960),
                new IllegalStateException((String) null, new IOException("\n".repeat(50))));

        afterNestedRunLogged(nested, () -> {
            assertEquals("oops (after a nested run() failed: " + "m".repeat(960) + " (caused by java.io.IOException: "
                    + "\\n".repeat(7) + "... (44 more characters))",
                    Failures.describe(new IllegalStateException("oops", nested)));
            return null;
        });
    }
}
