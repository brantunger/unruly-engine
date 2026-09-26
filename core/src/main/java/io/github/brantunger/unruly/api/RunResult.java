package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What one run did: the output object, the rules that fired, what each rule's condition evaluated to, the checksum
 * of the rules it used, and the tags and start time that chose which of them it used. Returned by
 * {@link RulesEngine#runWithResult(FactStore)}, and given to {@link RuleListener#afterRun(RunContext, RunResult)}.
 *
 * <p>
 * A run of an engine's own rules returns a result whose {@link #output()} is {@code null} exactly when
 * {@link #firedRules()} is empty, which happens when the rule list is empty or no rule matched. A failed run throws
 * instead of returning a partial result.
 * </p>
 *
 * <p>
 * It's a final class rather than a record, so a later 2.x release can add accessors without breaking code compiled
 * against this one.
 * </p>
 *
 * @param <O> The type of the output object
 */
public final class RunResult<O> {

    private final @Nullable O outputValue;
    private final List<Rule> fired;
    private final List<RuleEvaluation> evaluated;
    private final String checksum;
    private final Set<String> runTags;
    private final @Nullable Instant start;

    private RunResult(@Nullable O output, List<Rule> firedRules, List<RuleEvaluation> evaluations,
                      String ruleSetChecksum, Set<String> tags, @Nullable Instant startedAt) {
        Objects.requireNonNull(firedRules, "firedRules must not be null");
        for (Rule rule : firedRules) {
            Objects.requireNonNull(rule, "firedRules must not contain null");
        }
        Objects.requireNonNull(evaluations, "evaluations must not be null");
        for (RuleEvaluation evaluation : evaluations) {
            Objects.requireNonNull(evaluation, "evaluations must not contain null");
        }
        this.outputValue = output;
        this.fired = List.copyOf(firedRules);
        this.evaluated = List.copyOf(evaluations);
        this.checksum = Objects.requireNonNull(ruleSetChecksum, "ruleSetChecksum must not be null");
        this.runTags = tags;
        this.start = startedAt;
    }

    /**
     * Creates a result without evaluations. The engine creates its own; this is for a class that implements
     * {@link RulesEngine}, such as a decorator or a test double. Its {@link #evaluations()} and {@link #tags()} are
     * empty and its {@link #startedAt()} is {@code null}; use {@link #of(Object, List, List, String)} to report the
     * evaluations. {@link RunContext} is sealed, its one implementation is internal to the engine, and the engine
     * creates the contexts it passes to listeners, so an implementation carries a run's tags and start with
     * {@link #withRun(RunContext)} and a context an engine gave it, such as one its listener received.
     *
     * @param output           The output object, or {@code null} if no rule fired
     * @param firedRules       The rules whose actions ran, in firing order; copied
     * @param ruleSetChecksum  The {@link RuleSetInfo#checksum() checksum} of the rules the run used
     * @param <O>              The type of the output object
     * @return The result
     * @throws NullPointerException if {@code firedRules}, one of its elements, or {@code ruleSetChecksum} is
     *                              {@code null}
     */
    public static <O> RunResult<O> of(@Nullable O output, List<Rule> firedRules, String ruleSetChecksum) {
        return new RunResult<>(output, firedRules, List.of(), ruleSetChecksum, Set.of(), null);
    }

    /**
     * Creates a result. The engine creates its own; this is for a class that implements {@link RulesEngine}, such as a
     * decorator or a test double. Its {@link #tags()} are empty and its {@link #startedAt()} is {@code null}.
     * {@link RunContext} is sealed, its one implementation is internal to the engine, and the engine creates the
     * contexts it passes to listeners, so an implementation carries a run's tags and start with
     * {@link #withRun(RunContext)} and a context an engine gave it, such as one its listener received.
     *
     * @param output           The output object, or {@code null} if no rule fired
     * @param firedRules       The rules whose actions ran, in firing order; copied
     * @param evaluations      What each rule's condition evaluated to, in evaluation order; copied
     * @param ruleSetChecksum  The {@link RuleSetInfo#checksum() checksum} of the rules the run used
     * @param <O>              The type of the output object
     * @return The result
     * @throws NullPointerException if {@code firedRules}, {@code evaluations}, one of their elements, or
     *                              {@code ruleSetChecksum} is {@code null}
     */
    public static <O> RunResult<O> of(@Nullable O output, List<Rule> firedRules, List<RuleEvaluation> evaluations,
                                      String ruleSetChecksum) {
        return new RunResult<>(output, firedRules, evaluations, ruleSetChecksum, Set.of(), null);
    }

    /**
     * Returns a copy of this result that carries {@code run}'s {@link RunContext#tags() tags} and
     * {@link RunContext#startedAt() start}; everything else is the same. The engine calls it on the result of each of
     * its runs before {@link RuleListener#afterRun(RunContext, RunResult)} sees it. {@link RunContext} is sealed, its
     * one implementation is internal to the engine, and the engine creates the contexts it passes to listeners, so
     * another implementation of {@link RulesEngine} calls it with a context an engine gave it, such as one its
     * listener received.
     *
     * @param run The run whose tags and start the copy carries
     * @return The copy
     * @throws NullPointerException if {@code run} is {@code null}
     */
    public RunResult<O> withRun(RunContext run) {
        Objects.requireNonNull(run, "run must not be null");
        return new RunResult<>(outputValue, fired, evaluated, checksum, run.tags(), run.startedAt());
    }

    /**
     * Returns the output object the run produced.
     *
     * @return The output, or {@code null} if no rule fired
     */
    public @Nullable O output() {
        return outputValue;
    }

    /**
     * Returns the rules whose actions ran, in firing order. An engine that fires the first match returns at most one.
     *
     * @return The fired rules; unmodifiable, and empty if none fired
     */
    public List<Rule> firedRules() {
        return fired;
    }

    /**
     * Returns what each rule's condition evaluated to: one evaluation for every loaded rule, in evaluation order, the
     * same {@link Rule} instances as {@link RuleSetInfo#rules()}. On a first-match engine, the rules after the match
     * are {@link RuleEvaluation.Outcome#NOT_EVALUATED}; to know whether they would have matched, use an all-matches or
     * a unique-match engine, which evaluate every condition of the rules the run uses. A rule the run skipped is
     * {@link RuleEvaluation.Outcome#SKIPPED} on every engine. A result created with {@link #of(Object, List, String)}
     * has none.
     *
     * @return The evaluations; unmodifiable, and empty if the rule list is empty
     */
    public List<RuleEvaluation> evaluations() {
        return evaluated;
    }

    /**
     * Returns the checksum of the rules this run used. During a reload it can differ from the engine's current
     * {@link RuleSetInfo#checksum()}, because a run finishes with the rules it started with.
     *
     * @return The checksum, as {@link RuleSetInfo#checksum()} describes it
     */
    public String ruleSetChecksum() {
        return checksum;
    }

    /**
     * Returns the tags the run was given, as {@link RunContext#tags()} describes them: the run used only the rules
     * that carry at least one of them.
     *
     * @return The tags, in {@link String} order; unmodifiable, and empty if the run used every rule or the result was
     *         created with {@code of(...)} and not {@link #withRun(RunContext) given a run}
     */
    public Set<String> tags() {
        return runTags;
    }

    /**
     * Returns when the run started, by the engine's clock, as {@link RunContext#startedAt()} describes it: the instant
     * that judged every rule's validity window. A fixed clock gives every run the same instant. It isn't comparable
     * to {@link RuleSetInfo#loadedAt()} or the run's deadline.
     *
     * @return The instant, or {@code null} if the result was created with {@code of(...)} and not
     *         {@link #withRun(RunContext) given a run}
     */
    public @Nullable Instant startedAt() {
        return start;
    }

    /**
     * Describes the result, such as {@code RunResult(output={rate=4.5}, firedRules=[prime-rate],
     * evaluations=[prime-rate=MATCHED], ruleSetChecksum=9f2c..., tags=[], startedAt=2027-06-01T00:00:00Z)}. Rule
     * names and tags are shortened to 200 characters, then escaped, as the engine's error messages show names.
     *
     * @return The description
     */
    @Override
    public String toString() {
        return "RunResult(output=" + outputValue + ", firedRules="
                + Names.quoteEach(fired.stream().map(Rule::getRuleName).toList()) + ", evaluations=" + evaluated
                + ", ruleSetChecksum=" + checksum + ", tags=" + Names.quoteEach(runTags) + ", startedAt=" + start
                + ")";
    }
}
