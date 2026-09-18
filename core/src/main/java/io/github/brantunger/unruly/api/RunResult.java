package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * What one run did: the output object, the rules that fired, what each rule's condition evaluated to, and the checksum
 * of the rules it used. Returned by {@link RulesEngine#runWithResult(FactStore)}, and given to
 * {@link RuleListener#afterRun(RunContext, RunResult)}.
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

    private RunResult(@Nullable O output, List<Rule> firedRules, List<RuleEvaluation> evaluations,
                      String ruleSetChecksum) {
        this.outputValue = output;
        this.fired = List.copyOf(firedRules);
        this.evaluated = List.copyOf(evaluations);
        this.checksum = Objects.requireNonNull(ruleSetChecksum, "ruleSetChecksum must not be null");
    }

    /**
     * Creates a result without evaluations. The engine creates its own; this is for a class that implements
     * {@link RulesEngine}, such as a decorator or a test double. Its {@link #evaluations()} is empty; use
     * {@link #of(Object, List, List, String)} to report them.
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
        return new RunResult<>(output, firedRules, List.of(), ruleSetChecksum);
    }

    /**
     * Creates a result. The engine creates its own; this is for a class that implements {@link RulesEngine}, such as a
     * decorator or a test double.
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
        return new RunResult<>(output, firedRules, evaluations, ruleSetChecksum);
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
     * a unique-match engine, which evaluate every condition. A result created with
     * {@link #of(Object, List, String)} has none.
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

    @Override
    public String toString() {
        return "RunResult(output=" + outputValue + ", firedRules=" + fired.stream().map(Rule::getRuleName).toList()
                + ", evaluations=" + evaluated + ", ruleSetChecksum=" + checksum + ")";
    }
}
