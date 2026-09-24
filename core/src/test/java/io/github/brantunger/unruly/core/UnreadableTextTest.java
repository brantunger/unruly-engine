package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #579: how {@link Failures} reads an exception's message, text, cause and issues, and how the engine logs a stack
 * trace, without letting anything those throw escape, a fatal {@link Error} too: a message becomes a note that it's
 * unavailable, a cause or issues become none, and a stack trace is left out.
 */
@DisplayName("Failures reads what an exception says without letting a failure to read it escape")
class UnreadableTextTest {

    /** An exception whose {@code getMessage()}, {@code toString()} and {@code getCause()} all throw what it's given. */
    private static final class Throwing extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Throwable failure;

        Throwing(Throwable failure) {
            this.failure = failure;
        }

        private String fail() {
            PlainThrowableTest.<RuntimeException>sneakyThrow(failure);
            return null;
        }

        @Override
        public String getMessage() {
            return fail();
        }

        @Override
        public String toString() {
            return fail();
        }

        @Override
        public synchronized Throwable getCause() {
            fail();
            return null;
        }
    }

    /** An exception whose {@code getCause()} throws a new one of its kind every time. */
    private static final class EndlessCause extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            throw new EndlessCause();
        }
    }

    /** An exception whose {@code getCause()} returns {@code next}, or throws {@code thrown} if that's set. */
    private static final class LoopingCause extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private transient Throwable next;
        private transient RuntimeException thrown;

        @Override
        public synchronized Throwable getCause() {
            if (thrown != null) {
                throw thrown;
            }
            return next;
        }
    }

    /** An exception whose {@code getCause()} returns a new one of its kind, one level deeper, every time. */
    private static final class EndlessCauses extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int depth;

        EndlessCauses(int depth) {
            super("level " + depth, null, false, false);
            this.depth = depth;
        }

        @Override
        public synchronized Throwable getCause() {
            return new EndlessCauses(depth + 1);
        }
    }

    /** An exception whose {@code getMessage()} alone throws what it's given. */
    private static final class ThrowingMessage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Throwable failure;

        ThrowingMessage(Throwable failure, Throwable cause) {
            super(null, cause);
            this.failure = failure;
        }

        @Override
        public String getMessage() {
            PlainThrowableTest.<RuntimeException>sneakyThrow(failure);
            return null;
        }
    }

    /** A language's own {@link InvalidExpressionException}, whose {@code issues()} throws what it's given. */
    private static final class ThrowingIssues extends InvalidExpressionException {
        private static final long serialVersionUID = 1L;

        private final Throwable failure;

        ThrowingIssues(Throwable failure) {
            super("bad", List.of());
            this.failure = failure;
        }

        @Override
        public List<Issue> issues() {
            PlainThrowableTest.<RuntimeException>sneakyThrow(failure);
            return List.of();
        }
    }

    @Test
    @DisplayName("messageOf returns the message, or null if there is none")
    void messageOfReadable() {
        assertEquals("boom", Failures.messageOf(new IllegalStateException("boom")));
        assertNull(Failures.messageOf(new IllegalStateException()));
    }

    @Test
    @DisplayName("messageOf returns a note naming the class of what getMessage() threw")
    void messageOfUnreadable() {
        assertEquals("(message unavailable: java.lang.NullPointerException)",
                Failures.messageOf(new Throwing(new NullPointerException())));
    }

    @Test
    @DisplayName("messageOf returns the note for a StackOverflowError from getMessage()")
    void messageOfStackOverflow() {
        assertEquals("(message unavailable: java.lang.StackOverflowError)",
                Failures.messageOf(new Throwing(new StackOverflowError())));
    }

    @Test
    @DisplayName("messageOf returns the note for a fatal error from getMessage(), and one that what it threw wraps")
    void messageOfFatal() {
        assertEquals("(message unavailable: java.lang.OutOfMemoryError)",
                Failures.messageOf(new Throwing(new OutOfMemoryError("reading"))));
        assertEquals("(message unavailable: java.lang.IllegalStateException)",
                Failures.messageOf(new Throwing(new IllegalStateException("reading", new OutOfMemoryError()))));
    }

    @Test
    @DisplayName("messageOr returns the message, what stands in for a missing one, or that followed by a note")
    void messageOr() {
        assertEquals("boom", Failures.messageOr(new IllegalStateException("boom"), "none"));
        assertEquals("none", Failures.messageOr(new IllegalStateException(), "none"));
        assertEquals("none (message unavailable: java.lang.IllegalArgumentException)",
                Failures.messageOr(new Throwing(new IllegalArgumentException()), "none"));
    }

    @Test
    @DisplayName("textOf returns toString(), or the class name and a note if it throws, a fatal error too")
    void textOf() {
        assertEquals("java.lang.IllegalStateException: boom", Failures.textOf(new IllegalStateException("boom")));
        assertEquals(Throwing.class.getName() + " (message unavailable: java.lang.NullPointerException)",
                Failures.textOf(new Throwing(new NullPointerException())));
        assertEquals(Throwing.class.getName() + " (message unavailable: java.lang.InternalError)",
                Failures.textOf(new Throwing(new InternalError("printing"))));
    }

    @Test
    @DisplayName("causeOf returns the cause, or null if getCause() throws, a fatal error too")
    void causeOf() {
        IllegalStateException cause = new IllegalStateException("cause");

        assertSame(cause, Failures.causeOf(new RuntimeException(cause)));
        assertNull(Failures.causeOf(new Throwing(new NullPointerException())));
        assertNull(Failures.causeOf(new Throwing(new OutOfMemoryError("cause"))));
    }

    @Test
    @DisplayName("issuesOf returns a copy of the issues, or none if issues() throws, a fatal error too")
    void issuesOf() {
        InvalidExpressionException.Issue issue = new InvalidExpressionException.Issue(
                InvalidExpressionException.Issue.Severity.ERROR, 1, 2, "bad");

        assertEquals(List.of(issue), Failures.issuesOf(new InvalidExpressionException("bad", List.of(issue))));
        assertEquals(List.of(), Failures.issuesOf(new ThrowingIssues(new IllegalStateException("issues"))));
        assertEquals(List.of(), Failures.issuesOf(new ThrowingIssues(new OutOfMemoryError("issues"))));
    }

    @Test
    @DisplayName("describe names the root cause past a link in the middle of a chain whose getMessage() throws a fatal"
            + " error")
    void describeFatalInTheMiddle() {
        RuntimeException top = new RuntimeException("top",
                new ThrowingMessage(new OutOfMemoryError("reading"), new IllegalStateException("boom")));

        assertEquals("top (caused by java.lang.IllegalStateException: boom)", Failures.describe(top));
    }

    @Test
    @DisplayName("guard: describe ends for a getCause() that throws a new exception whose own getCause() throws too")
    void describeEndsForCausesThatThrowForEver() {
        assertEquals(EndlessCause.class.getName(), Failures.describe(new EndlessCause()));
    }

    @Test
    @DisplayName("guard: describe ends for a getCause() that throws an exception whose causes lead back to it")
    void describeEndsForACauseThatThrowsItsWayRound() {
        LoopingCause top = new LoopingCause();
        LoopingCause next = new LoopingCause();
        top.next = next;
        next.thrown = top;

        assertEquals(LoopingCause.class.getName() + " (caused by " + LoopingCause.class.getName() + ")",
                Failures.describe(top));
    }

    @Test
    @DisplayName("a getCause() that returns a new exception every time is read for the first "
            + Failures.MAX_CAUSE_CHAIN_LENGTH + " links of the chain")
    void causesThatNeverEnd() {
        EndlessCauses top = new EndlessCauses(0);

        Throwable root = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Failures.rootCause(top));

        assertEquals(Failures.MAX_CAUSE_CHAIN_LENGTH - 1, assertInstanceOf(EndlessCauses.class, root).depth);
        assertEquals("level 0", assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Failures.describe(top)));
    }

    @Test
    @DisplayName("logStackTrace makes the log call")
    void logStackTraceLogs() {
        boolean[] logged = {false};

        AbstractRulesEngine.logStackTrace(() -> logged[0] = true);

        assertTrue(logged[0]);
    }

    @Test
    @DisplayName("logStackTrace leaves the stack trace out when the log call throws")
    void logStackTraceLeftOut() {
        assertDoesNotThrow(() -> AbstractRulesEngine.logStackTrace(() -> {
            throw new NullPointerException("printing");
        }));
    }

    @Test
    @DisplayName("logStackTrace leaves the stack trace out when the log call throws a fatal error")
    void logStackTraceFatal() {
        assertDoesNotThrow(() -> AbstractRulesEngine.logStackTrace(() -> {
            throw new OutOfMemoryError("printing");
        }));
    }
}
