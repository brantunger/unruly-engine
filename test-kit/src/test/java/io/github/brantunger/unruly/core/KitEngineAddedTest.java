package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.test.KitFailuresAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1241: the test kit finds the fatal error a language threw, and the interrupt that stopped a run, with the engine's
 * own search ({@code api.language.Throwables}), so a suppressed exception the engine itself added to a failure it
 * reported (see {@link ReportedFailure#addSuppressedByEngine}) isn't read, as the engine doesn't read it. Through
 * 2.33.0 the kit kept a copy of the search that read it, and so rethrew a fatal error the engine had only kept beside
 * the one it rethrows. In this package because only the engine makes a {@link ReportedFailure}.
 */
@DisplayName("the test kit skips what the engine itself kept on a failure it reported, as the engine does")
class KitEngineAddedTest {

    @Test
    @DisplayName("a fatal error the engine added as suppressed isn't rethrown, direct or wrapped")
    void fatalErrorTheEngineAddedIsSkipped() {
        ReportedFailure byEngine = NestedFailures.report("reported", null);
        byEngine.addSuppressedByEngine(new OutOfMemoryError("kept by the engine"));
        IllegalStateException wrapped = new IllegalStateException("around", byEngine);

        assertNull(KitFailuresAccess.fatalError(byEngine));
        assertFalse(KitFailuresAccess.isFatal(byEngine));
        assertNull(KitFailuresAccess.fatalError(wrapped));
        assertFalse(KitFailuresAccess.isFatal(wrapped));
    }

    @Test
    @DisplayName("of a fatal error the engine added and one a try-with-resources added after it, the second is"
            + " rethrown")
    void fatalErrorAnythingElseAddedIsFound() {
        ReportedFailure failure = NestedFailures.report("reported", null);
        failure.addSuppressedByEngine(new OutOfMemoryError("kept by the engine"));
        OutOfMemoryError added = new OutOfMemoryError("added by a try-with-resources");
        failure.addSuppressed(added);

        assertSame(added, KitFailuresAccess.fatalError(failure));
        assertSame(added, KitFailuresAccess.fatalError(new IllegalStateException("around", failure)));
    }

    @Test
    @DisplayName("an interrupt the engine added as suppressed isn't taken for one that stopped the run")
    void interruptTheEngineAddedIsSkipped() {
        ReportedFailure byEngine = NestedFailures.report("reported", null);
        byEngine.addSuppressedByEngine(new InterruptedException("kept by the engine"));

        assertFalse(KitFailuresAccess.isInterrupt(byEngine));
        assertFalse(KitFailuresAccess.isInterrupt(new IllegalStateException("around", byEngine)));

        ReportedFailure byOthers = NestedFailures.report("reported", null);
        byOthers.addSuppressed(new InterruptedException("added by a try-with-resources"));
        assertTrue(KitFailuresAccess.isInterrupt(byOthers));
    }
}
