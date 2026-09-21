package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.function.Executable;

import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts what the engine logged while it failed, for the tests of both source sets that check that a failure is
 * logged before it is thrown.
 *
 * <p>
 * slf4j-simple writes to whatever {@link System#err} is at the time of each call, so the engine's log lines can be
 * captured with {@link io.github.brantunger.unruly.TestLogs#logsOf}.
 * </p>
 */
final class EngineLogs {

    /** The prefix slf4j-simple gives every line the engine logs. */
    static final String ENGINE_LOGGER = "io.github.brantunger.unruly.engine - ";

    private EngineLogs() {
    }

    /**
     * Asserts that {@code action} throws {@code type} and that the exception's message was logged at ERROR.
     *
     * @param type   The exception the action is expected to throw
     * @param action The code that should fail
     * @param <T>    The exception's type
     * @return The exception
     */
    static <T extends Throwable> T assertLoggedAtError(Class<T> type, Executable action) {
        AtomicReference<T> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(type, action)));

        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + thrown.get().getMessage()), logs);
        return thrown.get();
    }

    /**
     * Asserts that {@code action} throws {@code error} itself, after logging {@code message} at ERROR.
     *
     * @param error   The error the action is expected to rethrow unchanged
     * @param message The message that should have been logged first
     * @param action  The code that should fail
     */
    static void assertLoggedThenRethrown(Error error, String message, Executable action) {
        AtomicReference<Error> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(assertThrows(Error.class, action)));

        assertSame(error, thrown.get());
        assertTrue(logs.contains("ERROR " + ENGINE_LOGGER + message), logs);
    }
}
