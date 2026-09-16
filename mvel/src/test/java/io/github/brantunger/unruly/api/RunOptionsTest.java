package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RunOptions holds one run's settings, and changes nothing unless a setting is given")
class RunOptionsTest {

    @Test
    @DisplayName("the default options set no timeout, and are one shared instance")
    void defaults() {
        assertNull(RunOptions.defaults().timeout());
        assertSame(RunOptions.defaults(), RunOptions.defaults());
        assertEquals("RunOptions(timeout=the engine's)", RunOptions.defaults().toString());
    }

    @Test
    @DisplayName("a timeout is set on a copy, so shared options never change")
    void withTimeoutCopies() {
        RunOptions shared = RunOptions.defaults();

        RunOptions timed = shared.withTimeout(Duration.ofMillis(200));

        assertNotSame(shared, timed);
        assertNull(shared.timeout());
        assertEquals(Duration.ofMillis(200), timed.timeout());
        assertEquals(Duration.ofSeconds(1), RunOptions.withTimeoutOf(Duration.ofSeconds(1)).timeout());
        assertEquals("RunOptions(timeout=PT0.2S)", timed.toString());
    }

    @Test
    @DisplayName("a timeout must be a positive duration")
    void timeoutMustBePositive() {
        assertEquals("timeout must not be null",
                assertThrows(NullPointerException.class, () -> RunOptions.withTimeoutOf(null)).getMessage());
        assertEquals("timeout must be positive, but was PT0S", assertThrows(IllegalArgumentException.class,
                () -> RunOptions.withTimeoutOf(Duration.ZERO)).getMessage());
        assertEquals("timeout must be positive, but was PT-1S", assertThrows(IllegalArgumentException.class,
                () -> RunOptions.defaults().withTimeout(Duration.ofSeconds(-1))).getMessage());
    }

    @Test
    @DisplayName("RunOptions is a final class, so a later release can add settings")
    void finalClass() {
        assertTrue(Modifier.isFinal(RunOptions.class.getModifiers()));
        assertFalse(RunOptions.class.isRecord());
    }
}
