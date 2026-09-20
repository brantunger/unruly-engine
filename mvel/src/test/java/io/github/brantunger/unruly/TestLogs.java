package io.github.brantunger.unruly;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures what the library logs while a piece of code runs, for tests in any package of this source set.
 *
 * <p>
 * slf4j-simple writes to whatever {@link System#err} is at the time of each call, so replacing it around the code
 * under test collects that code's log lines and nothing else.
 * </p>
 */
public final class TestLogs {

    private TestLogs() {
    }

    /**
     * Runs {@code action} with {@link System#err} replaced, and returns what was written to it.
     *
     * @param action The code whose log lines to capture
     * @return Everything written to {@code System.err} while {@code action} ran, as one string
     */
    public static String logsOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
