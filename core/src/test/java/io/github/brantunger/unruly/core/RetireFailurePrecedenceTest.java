package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestLogs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #893: what a failed {@code load()} throws when retiring the rules it won't swap in fails with something that isn't
 * fatal, as running out of stack is. A fatal error in the load's own failure comes first, wherever it is in that
 * failure, as it does against a fatal error from closing; with none, the retire failure is thrown, carrying the load's.
 * A load failure that holds its fatal error only as a suppressed exception can't come from a {@code load()} today, as
 * a language's fatal error is rethrown unchanged, so {@link Failures#laterInsteadOf} is checked directly.
 */
@DisplayName("#893: a load's own fatal error comes before a retire failure that isn't fatal")
class RetireFailurePrecedenceTest {

    @Test
    @DisplayName("a fatal error only suppressed on the load's failure is thrown itself, carrying the retire failure")
    void suppressedFatalErrorIsThrownItself() {
        OutOfMemoryError fatal = new OutOfMemoryError("closing a resource");
        IllegalStateException failure = new IllegalStateException("making the copies");
        failure.addSuppressed(fatal);
        StackOverflowError retiring = new StackOverflowError("retiring");
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        String logs = TestLogs.logsOf(() -> thrown.set(Failures.laterInsteadOf(failure, retiring)));

        assertSame(fatal, thrown.get());
        assertArrayEquals(new Throwable[] {retiring}, fatal.getSuppressed(), "the retire failure isn't kept");
        assertTrue(logs.contains("WARN " + ENGINE_LOGGER + "A failure was replaced by the fatal error"), logs);
    }

    @Test
    @DisplayName("a fatal error in the load's failure's cause chain leaves that failure to be thrown, carrying the"
            + " retire failure")
    void fatalErrorInTheChainKeepsTheFailure() {
        RuntimeException failure = new RuntimeException("making the copies", new OutOfMemoryError("a session"));
        StackOverflowError retiring = new StackOverflowError("retiring");

        assertNull(Failures.laterInsteadOf(failure, retiring));
        assertArrayEquals(new Throwable[] {retiring}, failure.getSuppressed(), "the retire failure isn't kept");
    }

    @Test
    @DisplayName("with no fatal error, the retire failure is thrown, carrying the load's failure; with none, nothing")
    void otherwiseTheRetireFailureIsThrown() {
        IllegalStateException failure = new IllegalStateException("making the copies");
        StackOverflowError retiring = new StackOverflowError("retiring");

        assertSame(retiring, Failures.laterInsteadOf(failure, retiring));
        assertArrayEquals(new Throwable[] {failure}, retiring.getSuppressed(), "the load's failure isn't kept");
        assertNull(Failures.laterInsteadOf(failure, null));
    }

    @Test
    @DisplayName("a load's failure caused by an interrupt that the retire failure replaces sets the interrupt status"
            + " again")
    void replacedFailureKeepsTheInterrupt() throws InterruptedException {
        IllegalStateException failure = new IllegalStateException("making the copies", new InterruptedException());
        AtomicBoolean status = new AtomicBoolean();
        AtomicReference<Throwable> threw = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                Failures.laterInsteadOf(failure, new StackOverflowError("retiring"));
                status.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                threw.set(t);
            }
        });

        thread.start();
        thread.join();

        assertNull(threw.get(), () -> "the thread threw " + threw.get());
        assertTrue(status.get(), "the interrupt the replaced failure carried is lost");
    }
}
