package io.github.brantunger.unruly.core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures what is logged on any thread from when an action starts until a line has been logged, for a test whose
 * line is logged on a thread it can't wait for, such as the thread a cancel action runs on. Like
 * {@link io.github.brantunger.unruly.TestLogs#logsOf}, it replaces {@link System#err}, which slf4j-simple reads at
 * each call.
 */
final class LogsUntil {

    private LogsUntil() {
    }

    /**
     * Runs {@code action} with {@link System#err} replaced, and waits up to 10 seconds for {@code expected} to be
     * written to it, by any thread.
     *
     * @param expected What must be written
     * @param action   What leads to it
     * @return Everything written to {@code System.err} until it was
     * @throws InterruptedException if the thread is interrupted while it waits
     */
    static String logsUntil(String expected, Runnable action) throws InterruptedException {
        return logsUntil(expected, action, () -> {
        });
    }

    /**
     * Runs {@code action} with {@link System#err} replaced, waits up to 10 seconds for {@code expected} to be written
     * to it, by any thread, and then runs {@code afterwards} with it still replaced, so what is logged meanwhile is
     * captured too.
     *
     * @param expected   What must be written
     * @param action     What leads to it
     * @param afterwards What to run once it has been written
     * @return Everything written to {@code System.err} until {@code afterwards} returned
     * @throws InterruptedException if the thread is interrupted while it waits
     */
    static String logsUntil(String expected, Runnable action, Runnable afterwards) throws InterruptedException {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
            long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!buffer.toString(StandardCharsets.UTF_8).contains(expected)) {
                assertTrue(System.nanoTime() < giveUp, "never logged: " + expected);
                Thread.sleep(5);
            }
            afterwards.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
