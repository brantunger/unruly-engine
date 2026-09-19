package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings for one run, given to {@link RulesEngine#runWithResult(FactStore, RunOptions)}. Anything left unset uses
 * what the engine was built with.
 *
 * {@snippet :
 * RunResult<Decision> result = engine.runWithResult(facts, RunOptions.withTimeoutOf(Duration.ofMillis(200)));
 * RunResult<Decision> eu = engine.runWithResult(facts, RunOptions.defaults().withTags(Set.of("eu")));
 * }
 *
 * <p>
 * It's immutable: each {@code with} method returns a copy with one setting changed, so a shared instance can't be
 * changed by another caller. It's a final class rather than a record, so a later 2.x release can add settings without
 * breaking code compiled against this one.
 * </p>
 */
public final class RunOptions {

    private static final RunOptions NO_SETTINGS = new RunOptions(null, Set.of());

    private final @Nullable Duration runTimeout;

    private final Set<String> runTags;

    private RunOptions(@Nullable Duration runTimeout, Set<String> runTags) {
        this.runTimeout = runTimeout;
        this.runTags = runTags;
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
    public static RunOptions withTimeoutOf(Duration timeout) {
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
        return new RunOptions(timeout, runTags);
    }

    /**
     * Returns a copy of these options that has the run use only the rules that carry at least one of {@code tags}
     * ({@link Rule#getTags()}). The run skips every other rule, including rules with no tags: it doesn't evaluate
     * them, and reports them as {@link RuleEvaluation.Outcome#SKIPPED SKIPPED}. Tags are compared exactly, case
     * included. The copy's tags replace any these options had.
     *
     * @param tags The tags; not empty, and none null or blank; copied
     * @return The copy
     * @throws NullPointerException     if {@code tags} is {@code null}
     * @throws IllegalArgumentException if {@code tags} is empty, or one of them is {@code null} or blank
     */
    public RunOptions withTags(Collection<String> tags) {
        Objects.requireNonNull(tags, "tags must not be null");
        if (tags.isEmpty()) {
            throw new IllegalArgumentException("tags must not be empty; leave them unset to use every rule");
        }
        Set<String> sorted = new TreeSet<>();
        for (String tag : tags) {
            if (tag == null) {
                throw new IllegalArgumentException("tags must not contain null, but were " + tags);
            }
            if (tag.isBlank()) {
                throw new IllegalArgumentException("tags must not contain a blank tag, but were " + tags);
            }
            sorted.add(tag);
        }
        return new RunOptions(runTimeout, Collections.unmodifiableSet(sorted));
    }

    /**
     * Returns the timeout these options give the run.
     *
     * @return The timeout, or {@code null} if the run uses the engine's
     */
    public @Nullable Duration timeout() {
        return runTimeout;
    }

    /**
     * Returns the tags that choose the rules the run uses.
     *
     * @return The tags, in {@link String} order; unmodifiable, and empty if the run uses every rule
     */
    public Set<String> tags() {
        return runTags;
    }

    @Override
    public String toString() {
        return "RunOptions(timeout=" + (runTimeout == null ? "the engine's" : runTimeout) + ", tags="
                + (runTags.isEmpty() ? "any" : runTags) + ")";
    }
}
