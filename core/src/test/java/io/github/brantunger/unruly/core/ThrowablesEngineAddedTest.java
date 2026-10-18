package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.Throwables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1241: {@link Throwables#fatalError} and {@link Throwables#isInterrupt} search as the engine searches, so a
 * suppressed exception the engine itself added to a failure it reported (see
 * {@link ReportedFailure#addSuppressedByEngine}) isn't read, while one anything else added is. In this package
 * because only the engine makes a {@link ReportedFailure}.
 */
@DisplayName("Throwables: the engine's search skips what the engine itself kept on a failure it reported")
class ThrowablesEngineAddedTest {

    @Test
    @DisplayName("fatalError skips a fatal error the engine added as suppressed, and finds one anything else added")
    void fatalErrorSkipsWhatTheEngineAdded() {
        OutOfMemoryError kept = new OutOfMemoryError("kept by the engine");
        ReportedFailure byEngine = NestedFailures.report("reported", null);
        byEngine.addSuppressedByEngine(kept);
        assertNull(Throwables.fatalError(byEngine));
        assertNull(Throwables.fatalError(new IllegalStateException("around", byEngine)));

        OutOfMemoryError added = new OutOfMemoryError("added by a try-with-resources");
        ReportedFailure byOthers = NestedFailures.report("reported", null);
        byOthers.addSuppressed(added);
        assertSame(added, Throwables.fatalError(byOthers));
        assertSame(added, Throwables.fatalError(new IllegalStateException("around", byOthers)));
    }

    @Test
    @DisplayName("isInterrupt skips an interrupt the engine added as suppressed, and finds one anything else added")
    void isInterruptSkipsWhatTheEngineAdded() {
        ReportedFailure byEngine = NestedFailures.report("reported", null);
        byEngine.addSuppressedByEngine(new InterruptedException("kept by the engine"));
        assertFalse(Throwables.isInterrupt(byEngine));
        assertFalse(Throwables.isInterrupt(new IllegalStateException("around", byEngine)));

        ReportedFailure byOthers = NestedFailures.report("reported", null);
        byOthers.addSuppressed(new InterruptedException("added by a try-with-resources"));
        assertTrue(Throwables.isInterrupt(byOthers));
        assertTrue(Throwables.isInterrupt(new IllegalStateException("around", byOthers)));
    }
}
