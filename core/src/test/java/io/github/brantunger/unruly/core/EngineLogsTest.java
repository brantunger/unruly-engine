package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the shared log capture keeps what an action threw, and what the engine logged, by level")
class EngineLogsTest {

    @Test
    @DisplayName("the type-free capture returns an OutOfMemoryError rather than rethrowing it, with the log")
    void keepsAFatalError() {
        OutOfMemoryError fatal = new OutOfMemoryError("fatal");

        Outcome<Throwable> outcome = capture(() -> {
            System.err.println("ERROR " + ENGINE_LOGGER + "before");
            throw fatal;
        });

        assertSame(fatal, outcome.thrown());
        assertEquals(List.of("before"), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("the type-free capture returns null when nothing is thrown")
    void nothingThrown() {
        Outcome<Throwable> outcome = capture(() -> System.err.println("WARN " + ENGINE_LOGGER + "only a warning"));

        assertNull(outcome.thrown());
        assertEquals(List.of("only a warning"), outcome.lines("WARN"), outcome.logs());
        assertEquals(List.of(), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("the typed capture fails when nothing is thrown, as assertThrows does")
    void typedCaptureNeedsAThrow() {
        assertThrows(AssertionError.class, () -> capture(IllegalStateException.class, () -> {
        }));
    }
}
