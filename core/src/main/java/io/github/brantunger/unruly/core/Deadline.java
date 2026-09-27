package io.github.brantunger.unruly.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * When a run must stop, kept twice: as a {@link System#nanoTime()} value, which decides whether it has passed, and as
 * the {@link Instant} it was on the system clock when the run started, which is only ever shown.
 *
 * <p>
 * Both are read once, when the run starts. Every check after that reads {@code nanoTime()} alone, so a run is never
 * told to stop before its timeout has gone by, and a step of the system clock while it runs doesn't move when it
 * stops. The instant is the run's start on the system clock plus its timeout, so it compares with
 * {@link Instant#now()} as a deadline should, however long the JVM has been up.
 * </p>
 *
 * <p>
 * A run started from inside another inherits that run's deadline itself, so the engine tells a stop for the same
 * deadline by identity. {@link #equals(Object)} is value equality only for deadlines created {@link #at(Instant) at an
 * instant}, which are equal when their instants are: two contexts a test creates from one instant are equal, as they
 * were when a context held the instant itself. No decision uses it.
 * </p>
 */
final class Deadline {

    /** No deadline: it never passes, and there is always time left. */
    static final Deadline NONE = new Deadline(false, 0, null, null, false);

    // The most time left there can be: the most that converts to nanoseconds without overflowing.
    private static final Duration LONGEST = Duration.ofNanos(Long.MAX_VALUE);

    private final boolean set;
    // The nanoTime() value at which the deadline passes. Only ever compared with a nanoTime() reading, by subtracting
    // the two, which stays right when nanoTime() wraps. Two of these values are never subtracted from each other: a
    // far deadline and one long past can be more than Long.MAX_VALUE nanoseconds apart.
    private final long nanos;
    // When the run started on the system clock, and its timeout, from which instant() is worked out the first time
    // it's asked for. A deadline created at an instant keeps that instant here, with a timeout of zero.
    private final Instant start;
    private final Duration timeout;
    // Whether it was created at an instant, which makes it equal to another created at the same one.
    private final boolean atInstant;
    // The instant, once worked out. Not volatile: an Instant is immutable, and a thread that reads null works out the
    // same one again from the final fields above.
    private Instant shown;

    private Deadline(boolean set, long nanos, Instant start, Duration timeout, boolean atInstant) {
        this.set = set;
        this.nanos = nanos;
        this.start = start;
        this.timeout = timeout;
        this.atInstant = atInstant;
    }

    /**
     * Returns the deadline of a run that starts now and may take {@code timeout}.
     *
     * @param timeout How long the run may take; zero or positive
     * @return The deadline
     */
    static Deadline from(Duration timeout) {
        // Each clock read once, the system clock first, so the instant shown is never later than when the deadline
        // passes. The instant is worked out only if it's shown, so a run allocates no more for it.
        Instant start = Instant.now();
        return new Deadline(true, System.nanoTime() + nanosOf(timeout), start, timeout, false);
    }

    /**
     * Returns the deadline that passes at {@code instant} on the system clock, as it is now. What passes then is
     * read once, here: a step of the system clock after that doesn't move it.
     *
     * @param instant When the run must stop, or {@code null} if it has none
     * @return The deadline, or {@link #NONE} if {@code instant} is {@code null}
     */
    static Deadline at(Instant instant) {
        if (instant == null) {
            return NONE;
        }
        // The system clock first, as in from(), so the deadline passes no earlier than the instant.
        long left = nanosOf(Duration.between(Instant.now(), instant));
        return new Deadline(true, System.nanoTime() + left, instant, Duration.ZERO, true);
    }

    /**
     * Returns the earlier of two deadlines.
     *
     * @param own   A run's own deadline
     * @param outer The deadline of the run it was started from
     * @return The earlier one, or {@code outer} if they pass together, so a run that has no earlier deadline of its
     *         own shares its outer run's
     */
    static Deadline earliest(Deadline own, Deadline outer) {
        if (!own.set) {
            return outer;
        }
        if (!outer.set) {
            return own;
        }
        // The time each has left, from one reading: an outer deadline already past wins at once, however far away a
        // huge timeout puts the run's own, where subtracting the two nanoTime() values could overflow.
        long now = System.nanoTime();
        long outerLeft = outer.nanos - now;
        return outerLeft > 0 && own.nanos - now < outerLeft ? own : outer;
    }

    /**
     * Converts a timeout to nanoseconds without overflowing, and never negative: a deadline already past passes at
     * once, and one too far away to count in nanoseconds, such as the one a huge timeout gives, is as far away as they
     * reach.
     *
     * @param timeout The timeout
     * @return The nanoseconds, from zero to {@link Long#MAX_VALUE}
     */
    static long nanosOf(Duration timeout) {
        return Math.max(0, TimeUnit.NANOSECONDS.convert(timeout));
    }

    /**
     * Returns whether there is a deadline.
     *
     * @return {@code false} for {@link #NONE}
     */
    boolean isSet() {
        return set;
    }

    /**
     * Returns whether the deadline has passed.
     *
     * @return {@code true} if there is a deadline and it is not in the future
     */
    boolean hasPassed() {
        return set && System.nanoTime() - nanos >= 0;
    }

    /**
     * Returns how many nanoseconds are left before the deadline, without allocating.
     *
     * @return The nanoseconds left, zero or negative once it has passed, and {@link Long#MAX_VALUE} without a deadline
     */
    long nanosLeft() {
        return set ? nanos - System.nanoTime() : Long.MAX_VALUE;
    }

    /**
     * Returns how long is left before the deadline.
     *
     * @return The time left: {@link Duration#ZERO} once it has passed, and at most
     *         {@code Duration.ofNanos(Long.MAX_VALUE)}, which is what a run without a deadline has
     */
    Duration timeLeft() {
        if (!set) {
            return LONGEST;
        }
        long left = nanos - System.nanoTime();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    /**
     * Returns the deadline on the system clock, to show: the run's start on it plus its timeout, or the latest
     * instant there is when that would be later.
     *
     * @return The instant, or {@code null} if there is no deadline
     */
    Instant instant() {
        Instant instant = shown;
        if (instant == null && set) {
            instant = Cancellation.after(start, timeout);
            shown = instant;
        }
        return instant;
    }

    /**
     * Tells whether two deadlines were both created at the same instant; any other deadline equals only itself.
     *
     * @param other The other deadline
     * @return {@code true} if they are equal
     */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Deadline deadline && atInstant && deadline.atInstant
                && start.equals(deadline.start);
    }

    @Override
    public int hashCode() {
        return atInstant ? start.hashCode() : System.identityHashCode(this);
    }
}
