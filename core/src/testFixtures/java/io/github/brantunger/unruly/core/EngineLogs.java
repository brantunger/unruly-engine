package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures and asserts what the engine logged while it failed, for the tests of both source sets that check that a
 * failure is logged before it is thrown.
 *
 * <p>
 * slf4j-simple writes to whatever {@link System#err} is at the time of each call, so the engine's log lines can be
 * captured with {@link io.github.brantunger.unruly.TestLogs#logsOf}.
 * </p>
 */
public final class EngineLogs {

    /** The prefix slf4j-simple gives every line the engine logs. */
    public static final String ENGINE_LOGGER = "io.github.brantunger.unruly.engine - ";

    private EngineLogs() {
    }

    /**
     * What an action threw, and what was logged while it ran.
     *
     * @param thrown What the action threw, or {@code null} if it threw nothing
     * @param logs   Everything written to {@code System.err} while it ran, as one string
     * @param <T>    The type of what it threw
     */
    public record Outcome<T extends Throwable>(T thrown, String logs) {

        /**
         * Returns the messages the engine logged at one level, in order.
         *
         * @param level {@code ERROR} or {@code WARN}
         * @return The messages, without the thread, level and logger
         */
        public List<String> lines(String level) {
            String prefix = level + " " + ENGINE_LOGGER;
            return logs.lines().filter(line -> line.contains(prefix))
                    .map(line -> line.substring(line.indexOf(prefix) + prefix.length())).toList();
        }
    }

    /**
     * Runs {@code action}, which must throw {@code type}, and captures what was logged while it ran.
     *
     * @param type   The exception the action is expected to throw
     * @param action The code that should fail
     * @param <T>    The exception's type
     * @return The exception, and what was logged
     */
    public static <T extends Throwable> Outcome<T> capture(Class<T> type, Executable action) {
        return outcome(() -> assertThrows(type, action));
    }

    /**
     * Runs {@code action}, and captures what it threw, if anything, and what was logged while it ran. Unlike
     * {@link #capture(Class, Executable)}, it asserts nothing, for the reason {@link #thrownBy(Executable)} gives.
     *
     * @param action The code to run
     * @return What the action threw, or {@code null} if it threw nothing, and what was logged
     */
    public static Outcome<Throwable> capture(Executable action) {
        return outcome(() -> thrownBy(action));
    }

    /**
     * Returns what {@code action} throws, or {@code null} if it throws nothing. Not {@code assertThrows()}, which
     * rethrows an {@link OutOfMemoryError} it didn't expect, and so would stop the test JVM rather than fail the test.
     *
     * @param action The code to run
     * @return What it threw, or {@code null}
     */
    public static Throwable thrownBy(Executable action) {
        try {
            action.execute();
        } catch (Throwable t) {
            return t;
        }
        return null;
    }

    private static <T extends Throwable> Outcome<T> outcome(Supplier<T> action) {
        AtomicReference<T> thrown = new AtomicReference<>();
        String logs = logsOf(() -> thrown.set(action.get()));
        return new Outcome<>(thrown.get(), logs);
    }

    /**
     * Asserts that {@code action} throws {@code type} and that the exception's message was logged at ERROR.
     *
     * @param type   The exception the action is expected to throw
     * @param action The code that should fail
     * @param <T>    The exception's type
     * @return The exception
     */
    public static <T extends Throwable> T assertLoggedAtError(Class<T> type, Executable action) {
        Outcome<T> outcome = capture(type, action);

        assertTrue(outcome.logs().contains("ERROR " + ENGINE_LOGGER + outcome.thrown().getMessage()), outcome.logs());
        return outcome.thrown();
    }

    /**
     * Asserts that {@code action} throws {@code error} itself, after logging {@code message} at ERROR.
     *
     * @param error   The error the action is expected to rethrow unchanged
     * @param message The message that should have been logged first
     * @param action  The code that should fail
     */
    public static void assertLoggedThenRethrown(Error error, String message, Executable action) {
        Outcome<Error> outcome = capture(Error.class, action);

        assertSame(error, outcome.thrown());
        assertTrue(outcome.logs().contains("ERROR " + ENGINE_LOGGER + message), outcome.logs());
    }
}
