package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KitFailures describes what a language threw, and attaches what closing threw to a check's failure")
class KitFailuresTest {

    /** How many different exceptions the engine reads to find a fatal error, as core's Failures has it. */
    private static final int MAX_EXCEPTIONS_READ = 10_000;

    /** An exception whose getCause() throws, as a broken language's may. */
    private static final class UnreadableCause extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException("unreadable");
        }
    }

    /** What a try-with-resources throws when its body fails and its resource's close() then hits {@code error}. */
    private static IllegalStateException bodyFailed(Error error) {
        IllegalStateException body = new IllegalStateException("body failed");
        body.addSuppressed(error);
        return body;
    }

    /** Returns what rethrowIfFatal threw for {@code thrown}, or {@code null} if it returned. */
    private static Throwable rethrown(Throwable thrown) {
        try {
            KitFailures.rethrowIfFatal(thrown);
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /** An exception whose getMessage() throws, as a broken language's may. */
    private static final class UnreadableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("unreadable");
        }
    }

    @Test
    @DisplayName("an exception's message is its getMessage()")
    void message() {
        assertEquals("boom", KitFailures.message(new IllegalStateException("boom")));
        assertEquals("null", KitFailures.message(new IllegalStateException()));
    }

    @Test
    @DisplayName("an exception whose getMessage() throws is described by its class and the class of what it threw")
    void unreadableMessage() {
        assertEquals(UnreadableException.class.getName() + " (message unavailable: java.lang.IllegalStateException)",
                KitFailures.message(new UnreadableException()));
    }

    @Test
    @DisplayName("what closing threw is attached to a check's failure once, and never to itself")
    void attach() {
        IllegalStateException failure = new IllegalStateException("check");
        IllegalStateException closeFailure = new IllegalStateException("close");

        KitFailures.suppressAll(failure, List.of(failure, closeFailure, closeFailure));
        KitFailures.attach(failure, closeFailure);

        assertArrayEquals(new Throwable[] {closeFailure}, failure.getSuppressed());
    }

    @Test
    @DisplayName("a run stopped by an interrupt is told by the InterruptedException it threw or carries as a cause, as"
            + " the engine's stop carries one, or suppressed on it, as the engine throws what ending the stopped run"
            + " threw with the stop suppressed (#1223)")
    void isInterrupt() {
        // What ending a stopped run leaves when ending it throws too: the stop suppressed on what the ending threw.
        IllegalStateException ending = new IllegalStateException("ending the run failed");
        ending.addSuppressed(new IllegalStateException("run() was interrupted during rule 'r'",
                new InterruptedException()));
        IllegalStateException suppressedItself = new IllegalStateException("top");
        suppressedItself.addSuppressed(new InterruptedException());

        assertTrue(KitFailures.isInterrupt(new InterruptedException()));
        assertTrue(KitFailures.isInterrupt(new IllegalStateException("stopped", new InterruptedException())));
        assertTrue(KitFailures.isInterrupt(new IllegalStateException("top",
                new IllegalStateException("one", new IllegalStateException("two", new InterruptedException())))));
        assertTrue(KitFailures.isInterrupt(ending));
        assertTrue(KitFailures.isInterrupt(suppressedItself));
        assertTrue(KitFailures.isInterrupt(new IllegalStateException("wrapped", ending)));
        assertFalse(KitFailures.isInterrupt(new IllegalStateException("rule 'r' failed")));
        assertFalse(KitFailures.isInterrupt(new UnreadableCause()));
    }

    @Test
    @DisplayName("exceptions that lead back to each other through causes and suppressed exceptions, with no"
            + " InterruptedException among them, end the search for one (#1223 guard)")
    void interruptSearchEndsOnLoops() {
        IllegalStateException top = new IllegalStateException("top");
        IllegalStateException cause = new IllegalStateException("cause", top);
        top.initCause(cause);
        IllegalStateException back = new IllegalStateException("back", cause);
        top.addSuppressed(back);
        cause.addSuppressed(back);
        back.addSuppressed(top);

        assertFalse(KitFailures.isInterrupt(top));
    }

    @Test
    @DisplayName("only the first hundred links of a run's failure are read for the InterruptedException that stopped it"
            + " (#1223 guard)")
    void interruptReadToAHundredLinks() {
        Throwable hundredth = new InterruptedException();
        for (int i = 0; i < 99; i++) {
            hundredth = new IllegalStateException("link " + i, hundredth);
        }

        assertTrue(KitFailures.isInterrupt(hundredth));
        assertFalse(KitFailures.isInterrupt(new IllegalStateException("one too many", hundredth)));
    }

    @Test
    @DisplayName("a fatal error a language throws itself is thrown on, and a StackOverflowError isn't (#894 guard)")
    void fatalErrorItself() {
        InternalError crash = new InternalError("crashed");
        StackOverflowError overflow = new StackOverflowError("recursed");

        assertSame(crash, rethrown(crash));
        assertNull(rethrown(overflow));
        assertTrue(KitFailures.isFatal(crash));
        assertFalse(KitFailures.isFatal(overflow));
        assertFalse(KitFailures.isFatal(new IllegalStateException("plain")));
    }

    @Test
    @DisplayName("a fatal error that is the cause of what a language threw is thrown on, not the wrapper (#894)")
    void fatalErrorAsCause() {
        OutOfMemoryError oom = new OutOfMemoryError("in a getter");
        IllegalStateException wrapper = new IllegalStateException("evaluation failed", oom);

        assertSame(oom, rethrown(wrapper));
        assertTrue(KitFailures.isFatal(wrapper));
    }

    @Test
    @DisplayName("a fatal error three causes down is thrown on (#894)")
    void fatalErrorDeepInTheCauseChain() {
        OutOfMemoryError oom = new OutOfMemoryError("three down");
        IllegalStateException top = new IllegalStateException("top",
                new IllegalStateException("one", new IllegalStateException("two", oom)));

        assertSame(oom, rethrown(top));
    }

    @Test
    @DisplayName("a fatal error suppressed on what a language threw, as a try-with-resources leaves it, is thrown on"
            + " (#894, the shape of #845)")
    void fatalErrorSuppressed() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");

        assertSame(oom, rethrown(bodyFailed(oom)));
        assertTrue(KitFailures.isFatal(bodyFailed(oom)));
    }

    @Test
    @DisplayName("#1019: of two fatal errors suppressed on what a language threw, the first is thrown on, as the engine"
            + " throws it")
    void firstOfTwoFatalErrorsSuppressed() {
        OutOfMemoryError first = new OutOfMemoryError("first");
        InternalError second = new InternalError("second");
        IllegalStateException body = bodyFailed(first);
        body.addSuppressed(second);

        assertSame(first, rethrown(body));
    }

    @Test
    @DisplayName("a fatal error suppressed on a cause of what a language threw is thrown on (#894)")
    void fatalErrorSuppressedOnACause() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        IllegalStateException top = new IllegalStateException("wrapped", bodyFailed(oom));

        assertSame(oom, rethrown(top));
    }

    @Test
    @DisplayName("a fatal error that is the cause of an exception suppressed on what a language threw is thrown on"
            + " (#894)")
    void fatalErrorCausingASuppressedException() {
        OutOfMemoryError oom = new OutOfMemoryError("on the cause");
        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(new IllegalStateException("close failed", oom));

        assertSame(oom, rethrown(top));
    }

    @Test
    @DisplayName("a fatal error suppressed on the cause of an exception suppressed on a cause is thrown on (#894)")
    void fatalErrorSuppressedAtDepth() {
        OutOfMemoryError oom = new OutOfMemoryError("deep");
        IllegalStateException innerCause = new IllegalStateException("inner cause");
        innerCause.addSuppressed(new IllegalStateException("x", new IllegalStateException("y")));
        innerCause.addSuppressed(bodyFailed(oom));
        IllegalStateException cause = new IllegalStateException("cause");
        cause.addSuppressed(new IllegalStateException("suppressed", innerCause));
        IllegalStateException top = new IllegalStateException("top", cause);

        assertSame(oom, rethrown(top));
    }

    @Test
    @DisplayName("the first fatal error of the cause chain is thrown on before one suppressed on it, as the engine"
            + " picks it (#894)")
    void causeChainBeforeSuppressed() {
        OutOfMemoryError suppressed = new OutOfMemoryError("suppressed");
        InternalError cause = new InternalError("cause");
        IllegalStateException top = new IllegalStateException("top", cause);
        top.addSuppressed(suppressed);

        assertSame(cause, rethrown(top));
    }

    @Test
    @DisplayName("of fatal errors suppressed on a cause and causing a suppressed exception, the one suppressed on the"
            + " cause is thrown on, as the engine picks it: each link's direct suppressed exceptions are read before"
            + " what any leads to (#894)")
    void directSuppressedOfEachLinkBeforeWhatTheyLeadTo() {
        OutOfMemoryError onTheCause = new OutOfMemoryError("suppressed on the cause");
        IllegalStateException cause = new IllegalStateException("cause");
        cause.addSuppressed(onTheCause);
        IllegalStateException top = new IllegalStateException("top", cause);
        top.addSuppressed(new IllegalStateException("a", new OutOfMemoryError("causing a suppressed exception")));

        assertSame(onTheCause, rethrown(top));
    }

    @Test
    @DisplayName("a StackOverflowError carried as a cause or suppressed isn't fatal (#894 guard)")
    void carriedStackOverflowNotFatal() {
        IllegalStateException top = new IllegalStateException("top", new StackOverflowError("as a cause"));
        top.addSuppressed(new StackOverflowError("suppressed"));

        assertNull(rethrown(top));
        assertFalse(KitFailures.isFatal(top));
    }

    @Test
    @DisplayName("#1141: an InternalError around an overflow, as the JDK wraps one, isn't fatal, as the engine reads"
            + " it, but one around a class an overflow left unusable, or with no overflow, is")
    void internalErrorAroundOverflowNotFatal() {
        InternalError overflow = new InternalError(new InvocationTargetException(new StackOverflowError()));
        IllegalStateException carried = new IllegalStateException("carried", overflow);
        carried.addSuppressed(new InternalError(new StackOverflowError()));
        NoClassDefFoundError dead = new NoClassDefFoundError("Could not initialize class X");
        dead.initCause(new StackOverflowError());
        InternalError aroundDead = new InternalError(dead);
        InternalError crash = new InternalError("crashed", new IllegalStateException("no overflow"));

        assertNull(rethrown(overflow));
        assertNull(rethrown(carried));
        assertFalse(KitFailures.isFatal(carried));
        assertSame(aroundDead, rethrown(aroundDead));
        assertSame(crash, rethrown(crash));
        InternalError looped = new InternalError("looped");
        looped.initCause(new IllegalStateException("back", looped));
        assertSame(looped, rethrown(looped));
    }

    @Test
    @DisplayName("exceptions that lead back to each other through causes and suppressed exceptions end the search"
            + " (#894 guard)")
    void loopsEndTheSearch() {
        IllegalStateException top = new IllegalStateException("top");
        IllegalStateException cause = new IllegalStateException("cause", top);
        top.initCause(cause);
        IllegalStateException back = new IllegalStateException("back", cause);
        top.addSuppressed(back);
        cause.addSuppressed(back);
        back.addSuppressed(top);

        assertNull(rethrown(top));
    }

    @Test
    @DisplayName("a getCause() that throws ends that chain, and a fatal error suppressed on it is still thrown on"
            + " (#894)")
    void unreadableCauseEndsTheChain() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        UnreadableCause top = new UnreadableCause();
        top.addSuppressed(new IllegalStateException("suppressed", new UnreadableCause()));
        top.addSuppressed(oom);

        assertSame(oom, rethrown(top));
    }

    @Test
    @DisplayName("a fatal error past a hundred causes of an exception suppressed on what a language threw is thrown"
            + " on (#894)")
    void longCauseChainOfASuppressedException() {
        OutOfMemoryError oom = new OutOfMemoryError("deep");
        IllegalStateException link = new IllegalStateException("last", oom);
        for (int i = 0; i < 150; i++) {
            link = new IllegalStateException("link " + i, link);
        }
        IllegalStateException top = new IllegalStateException("top");
        top.addSuppressed(link);

        assertSame(oom, rethrown(top));
    }

    @Test
    @DisplayName("only the first hundred links of what a language threw are read as its cause chain (#894 guard)")
    void causeChainReadToAHundredLinks() {
        IllegalStateException link = new IllegalStateException("last", new OutOfMemoryError("too deep"));
        for (int i = 0; i < 99; i++) {
            link = new IllegalStateException("link " + i, link);
        }

        assertNull(rethrown(link));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"found as the last exception read", "missed one past the last exception read"})
    @DisplayName("a fatal error is found as the last exception the engine reads, and not past it (#894 cap)")
    void foundUpToTheLastExceptionRead(String where) {
        int fewer = where.startsWith("found") ? 2 : 1;
        OutOfMemoryError oom = new OutOfMemoryError("last read");
        IllegalStateException top = new IllegalStateException("top");
        for (int i = 0; i < MAX_EXCEPTIONS_READ - fewer; i++) {
            top.addSuppressed(new IllegalStateException("before " + i));
        }
        top.addSuppressed(oom);

        assertSame(fewer == 2 ? oom : null, rethrown(top));
    }

    @Test
    @DisplayName("among more exceptions than the engine reads, a fatal error read first is found, and one exception"
            + " suppressed often counts once (#894 cap)")
    void searchedUpToTheCap() {
        OutOfMemoryError oom = new OutOfMemoryError("OOM in close()");
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException shared = new IllegalStateException("shared");
        IllegalStateException often = new IllegalStateException("often");
        first.addSuppressed(oom);
        for (int i = 0; i < MAX_EXCEPTIONS_READ; i++) {
            first.addSuppressed(new IllegalStateException("suppressed " + i));
            often.addSuppressed(shared);
            often.addSuppressed(shared);
        }
        often.addSuppressed(oom);

        assertSame(oom, rethrown(first));
        assertSame(oom, rethrown(often));
    }

    @ParameterizedTest(name = "{0} suppressed exceptions with a cause")
    @ValueSource(ints = {MAX_EXCEPTIONS_READ / 2 - 2, MAX_EXCEPTIONS_READ / 2 - 1})
    @DisplayName("the causes of suppressed exceptions count toward the exceptions the engine reads: a fatal error that"
            + " is the cause of the last of so many suppressed exceptions, each with a cause, is found up to the last"
            + " exception read (#894 cap)")
    void causesCountTowardTheCap(int withCauses) {
        OutOfMemoryError oom = new OutOfMemoryError("last read");
        IllegalStateException top = new IllegalStateException("top");
        for (int i = 0; i < withCauses; i++) {
            top.addSuppressed(new IllegalStateException("before " + i, new IllegalStateException("cause " + i)));
        }
        top.addSuppressed(new IllegalStateException("carrier", oom));

        // The top, the suppressed exceptions and the carrier, then the causes of each: the error is read 2n + 3rd.
        assertSame(2 * withCauses + 3 <= MAX_EXCEPTIONS_READ ? oom : null, rethrown(top));
    }
}
