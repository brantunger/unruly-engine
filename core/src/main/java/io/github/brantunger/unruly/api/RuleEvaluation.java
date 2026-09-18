package io.github.brantunger.unruly.api;

import java.util.Objects;

/**
 * What a run found out about one rule: whether its condition was true, false, or never evaluated. A
 * {@link RunResult#evaluations() run result} has one for every loaded rule, in evaluation order, so a caller can
 * answer "why didn't rule X apply?" without a {@link RuleListener}.
 *
 * <p>
 * It's a final class rather than a record, so a later 2.x release can add accessors without breaking code compiled
 * against this one. Two evaluations are equal when their rules and outcomes are.
 * </p>
 */
public final class RuleEvaluation {

    /** What evaluating a rule's condition found. A run that fails throws, so there is no outcome for a failure. */
    public enum Outcome {
        /** The condition was true. On every engine, a matched rule is one that fired, unless the run failed. */
        MATCHED,
        /** The condition was false. */
        NOT_MATCHED,
        /**
         * The condition wasn't evaluated: the rule comes after the match on a first-match engine, so it's neither
         * matched nor unmatched.
         */
        NOT_EVALUATED
    }

    private final Rule evaluated;
    private final Outcome result;

    private RuleEvaluation(Rule rule, Outcome outcome) {
        this.evaluated = Objects.requireNonNull(rule, "rule must not be null");
        this.result = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /**
     * Creates an evaluation. The engine creates its own; this is for a class that implements {@link RulesEngine},
     * such as a decorator or a test double, and for tests that compare results.
     *
     * @param rule    The rule
     * @param outcome What evaluating its condition found
     * @return The evaluation
     * @throws NullPointerException if an argument is {@code null}
     */
    public static RuleEvaluation of(Rule rule, Outcome outcome) {
        return new RuleEvaluation(rule, outcome);
    }

    /**
     * Returns the rule, the same instance that was passed to {@link RulesEngine#load(java.util.List)}.
     *
     * @return The rule
     */
    public Rule rule() {
        return evaluated;
    }

    /**
     * Returns what evaluating the rule's condition found.
     *
     * @return The outcome
     */
    public Outcome outcome() {
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuleEvaluation that && evaluated.equals(that.evaluated) && result == that.result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(evaluated, result);
    }

    /**
     * Names the rule and its outcome, such as {@code prime-rate=MATCHED}.
     *
     * @return The description
     */
    @Override
    public String toString() {
        return evaluated.getRuleName() + "=" + result;
    }
}
