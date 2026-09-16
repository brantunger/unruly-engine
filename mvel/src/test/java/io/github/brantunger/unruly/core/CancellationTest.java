package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a run's deadline is the earliest of its own and the deadline of the run it was started from")
class CancellationTest {

    private static final Instant SOON = Instant.now().plusSeconds(60);
    private static final Instant LATER = SOON.plusSeconds(60);

    @Test
    @DisplayName("the earlier of two deadlines wins, and a missing one never does")
    void earliest() {
        assertNull(Cancellation.earliest(null, null));
        assertEquals(SOON, Cancellation.earliest(SOON, null));
        assertEquals(SOON, Cancellation.earliest(null, SOON));
        assertEquals(SOON, Cancellation.earliest(SOON, LATER));
        assertEquals(SOON, Cancellation.earliest(LATER, SOON));
    }

    @Test
    @DisplayName("a run started inside another inherits its deadline until that run ends, and nothing is left behind")
    void enterAndLeave() {
        assertNull(Cancellation.deadlineFrom(null), "a thread that isn't running anything has no deadline");
        try {
            enterTwiceAndLeave();
        } finally {
            Cancellation.leave(null);
        }
    }

    private static void enterTwiceAndLeave() {
        Instant outside = Cancellation.enter(LATER);
        assertNull(outside);
        assertEquals(LATER, Cancellation.deadlineFrom(null));
        assertEquals(LATER, Cancellation.deadlineFrom(Duration.ofDays(1)), "a longer timeout doesn't extend it");

        Instant outer = Cancellation.enter(SOON);
        assertEquals(LATER, outer);
        assertEquals(SOON, Cancellation.deadlineFrom(null));
        Cancellation.leave(outer);

        assertEquals(LATER, Cancellation.deadlineFrom(null), "the outer run's deadline is back");
        Cancellation.leave(outside);
        assertNull(Cancellation.deadlineFrom(null), "the thread is back to no deadline");
    }

    @Test
    @DisplayName("a timeout too long for a date is capped at the latest instant rather than overflowing")
    void aHugeTimeoutDoesntOverflow() {
        Instant start = Instant.parse("2026-09-16T00:00:00Z");

        assertEquals(Instant.MAX, Cancellation.after(start, Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(Instant.MAX, Cancellation.after(start, Duration.between(start, Instant.MAX)));
        assertEquals(start.plusSeconds(1), Cancellation.after(start, Duration.ofSeconds(1)));
        assertEquals(Instant.MAX, Cancellation.deadlineFrom(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    @DisplayName("the time left before a deadline is negative once it has passed, and absent without one")
    void timeLeft() {
        assertNull(Cancellation.timeLeft(null));
        assertTrue(Cancellation.timeLeft(Instant.now().minusSeconds(1)).isNegative());
        assertTrue(Cancellation.timeLeft(LATER).isPositive());
    }
}
