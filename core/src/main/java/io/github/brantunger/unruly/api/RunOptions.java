package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings for one run, given to {@link RulesEngine#runWithResult(FactStore, RunOptions)}. Anything left unset uses
 * what the engine was built with.
 *
 * <pre>{@code
 * RunResult<Decision> result = engine.runWithResult(facts, RunOptions.timeout(Duration.ofMillis(200)));
 * }</pre>
 *
 * <p>
 * It's immutable: each {@code with} method returns a copy with one setting changed, so a shared instance can't be
 * changed by another caller. It's a final class rather than a record, so a later 2.x release can add settings without
 * breaking code compiled against this one.
 * </p>
 */
public final class RunOptions {

    private static final RunOptions NO_SETTINGS = new RunOptions(null);

    private final @Nullable Duration runTimeout;

    private RunOptions(@Nullable Duration runTimeout) {
        this.runTimeout = runTimeout;
    }

    /**
     * Returns the options that change nothing: the run uses what the engine was built with.
     *
     * @return The default options
     */
    public static RunOptions defaults() {
        return NO_SETTINGS;
    }

    /**
     * Returns options that give the run {@code timeout} instead of the engine's
     * {@link RulesEngineBuilder#runTimeout(Duration) run timeout}.
     *
     * @param timeout How long the run may take; positive
     * @return The options
     * @throws NullPointerException     if {@code timeout} is {@code null}
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     */
    public static RunOptions timeout(Duration timeout) {
        return NO_SETTINGS.withTimeout(timeout);
    }

    /**
     * Returns a copy of these options that gives the run {@code timeout} instead of the engine's
     * {@link RulesEngineBuilder#runTimeout(Duration) run timeout}. There is no way to take the engine's timeout away
     * from one run: a run can be given a longer one.
     *
     * <p>
     * The deadline is taken from when the run is called, so waiting for a compiled copy of the rules counts towards
     * it, and a run started inside another run's action stops at whichever deadline comes first; see
     * {@link RulesEngineBuilder#runTimeout(Duration)}.
     * </p>
     *
     * @param timeout How long the run may take; positive
     * @return The copy
     * @throws NullPointerException     if {@code timeout} is {@code null}
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     */
    public RunOptions withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("timeout must be positive, but was " + timeout);
        }
        return new RunOptions(timeout);
    }

    /**
     * Returns the timeout these options give the run.
     *
     * @return The timeout, or {@code null} if the run uses the engine's
     */
    public @Nullable Duration timeout() {
        return runTimeout;
    }

    @Override
    public String toString() {
        return "RunOptions(timeout=" + (runTimeout == null ? "the engine's" : runTimeout) + ")";
    }
}
