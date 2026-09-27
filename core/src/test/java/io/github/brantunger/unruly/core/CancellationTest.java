package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a run's deadline is the earliest of its own and the deadline of the run it was started from")
class CancellationTest {

    private static final Deadline SOON = Deadline.from(Duration.ofSeconds(60));
    private static final Deadline LATER = Deadline.from(Duration.ofSeconds(120));

    @Test
    @DisplayName("the earlier of two deadlines wins, and a missing one never does")
    void earliest() {
        assertSame(Deadline.NONE, Deadline.earliest(Deadline.NONE, Deadline.NONE));
        assertSame(SOON, Deadline.earliest(SOON, Deadline.NONE));
        assertSame(SOON, Deadline.earliest(Deadline.NONE, SOON));
        assertSame(SOON, Deadline.earliest(SOON, LATER));
        assertSame(SOON, Deadline.earliest(LATER, SOON));
    }

    @Test
    @DisplayName("a run started inside another inherits its deadline until that run ends, and nothing is left behind")
    void enterAndLeave() {
        assertFalse(Cancellation.deadlineFrom(null).isSet(), "a thread that isn't running anything has no deadline");
        try {
            enterTwiceAndLeave();
        } finally {
            Cancellation.leave(null);
        }
    }

    private static void enterTwiceAndLeave() {
        Deadline outside = Cancellation.enter(LATER);
        assertNull(outside);
        assertSame(LATER, Cancellation.deadlineFrom(null));
        assertSame(LATER, Cancellation.deadlineFrom(Duration.ofDays(1)), "a longer timeout doesn't extend it");

        Deadline outer = Cancellation.enter(SOON);
        assertSame(LATER, outer);
        assertSame(SOON, Cancellation.deadlineFrom(null));
        Cancellation.leave(outer);

        assertSame(LATER, Cancellation.deadlineFrom(null), "the outer run's deadline is back");
        Cancellation.leave(outside);
        assertFalse(Cancellation.deadlineFrom(null).isSet(), "the thread is back to no deadline");
    }

    @Test
    @DisplayName("a run started inside one already past its deadline shares that deadline, however long its own")
    void aPassedOuterDeadlineIsShared() {
        Deadline passed = Deadline.from(Duration.ZERO);
        Deadline outside = Cancellation.enter(passed);
        try {
            assertSame(passed, Cancellation.deadlineFrom(Duration.ofSeconds(Long.MAX_VALUE)));
            assertSame(passed, Cancellation.deadlineFrom(Duration.ofSeconds(1)));
            assertSame(passed, Cancellation.deadlineFrom(null));
        } finally {
            Cancellation.leave(outside);
        }
        Deadline later = Deadline.from(Duration.ofSeconds(60));
        outside = Cancellation.enter(later);
        try {
            Deadline own = Cancellation.deadlineFrom(Duration.ofSeconds(1));
            assertNotSame(later, own, "a shorter timeout of its own comes first");
            assertTrue(own.nanosLeft() <= Duration.ofSeconds(1).toNanos());
        } finally {
            Cancellation.leave(outside);
        }
    }

    @Test
    @DisplayName("a run without a deadline, started on a thread running nothing, leaves nothing behind")
    void enteringNoDeadline() {
        assertNull(Cancellation.enter(Deadline.NONE));
        try {
            assertFalse(Cancellation.deadlineFrom(null).isSet());
        } finally {
            Cancellation.leave(null);
        }
    }

    @Test
    @DisplayName("a timeout too long for a date is shown as the latest instant rather than overflowing")
    void aHugeTimeoutDoesntOverflow() {
        Instant start = Instant.parse("2026-09-16T00:00:00Z");

        assertEquals(Instant.MAX, Cancellation.after(start, Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(Instant.MAX, Cancellation.after(start, Duration.between(start, Instant.MAX)));
        assertEquals(start.plusSeconds(1), Cancellation.after(start, Duration.ofSeconds(1)));
        assertEquals(Instant.MAX, Cancellation.deadlineFrom(Duration.ofSeconds(Long.MAX_VALUE)).instant());
    }

    @Test
    @DisplayName("guard: a timeout too long for a date gives a deadline that never passes")
    void aHugeTimeoutNeverPasses() {
        Deadline deadline = Cancellation.deadlineFrom(Duration.ofSeconds(Long.MAX_VALUE));

        assertFalse(deadline.hasPassed());
        assertFalse(Cancellation.isCancelled(deadline));
        Duration left = deadline.timeLeft();
        assertTrue(left.compareTo(Duration.ofDays(365L * 290)) > 0, left.toString());
        assertTrue(left.compareTo(Duration.ofNanos(Long.MAX_VALUE)) <= 0, left.toString());
    }

    @Test
    @DisplayName("an interrupted thread is cancelled whatever its deadline")
    void interruptCancels() {
        Thread.currentThread().interrupt();
        try {
            assertTrue(Cancellation.isCancelled(Deadline.NONE));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("the exception a run past its deadline is caused by shows the deadline's instant")
    void timedOutShowsTheInstant() {
        assertEquals("The run's deadline of 2001-02-03T04:05:06Z has passed",
                Cancellation.timedOut(Deadline.at(Instant.parse("2001-02-03T04:05:06Z"))).getMessage());
    }
}
