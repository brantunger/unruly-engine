package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * What a run found out about one rule: whether its condition was true, false, or never evaluated, or whether the run
 * skipped the rule. A
 * {@link RunResult#evaluations() run result} has one for every loaded rule, in evaluation order, so a caller can
 * answer "why didn't rule X apply?" without a {@link RuleListener}.
 *
 * <p>
 * It's a final class rather than a record, so a later 2.x release can add accessors without breaking code compiled
 * against this one. Two evaluations are equal when their rules and outcomes are, whatever their
 * {@linkplain #detail() details}: a detail is the language's own object, with whatever {@code equals} it has, so it
 * is left out of {@code equals} and {@code hashCode}.
 * </p>
 */
public final class RuleEvaluation {

    /**
     * What evaluating a rule's condition found, or that the run skipped the rule. A run that fails throws, so there is
     * no outcome for a failure.
     */
    public enum Outcome {
        /** The condition was true. On every engine, a matched rule is one that fired, unless the run failed. */
        MATCHED,
        /** The condition was false. */
        NOT_MATCHED,
        /**
         * The condition wasn't evaluated: the rule comes after the match on a first-match engine, so it's neither
         * matched nor unmatched.
         */
        NOT_EVALUATED,
        /**
         * The run skipped the rule without evaluating its condition: the rule is {@link Rule#isEnabled() disabled},
         * outside its {@link Rule#getValidFrom() validity window} when the run started, or carries none of the tags
         * the run was {@link RunOptions#withTags(java.util.Collection) given}. A skipped rule is reported as
         * skipped wherever it is in the list, including after the match on a first-match engine.
         */
        SKIPPED
    }

    private final Rule evaluated;
    private final Outcome result;
    private final @Nullable Object explanation;

    private RuleEvaluation(Rule rule, Outcome outcome, @Nullable Object detail) {
        this.evaluated = Objects.requireNonNull(rule, "rule must not be null");
        this.result = Objects.requireNonNull(outcome, "outcome must not be null");
        this.explanation = detail;
    }

    /**
     * Creates an evaluation. The engine creates its own; this is for a class that implements {@link RulesEngine},
     * such as a decorator or a test double, and for tests that compare results.
     *
     * @param rule    The rule
     * @param outcome What evaluating its condition found, or that the run skipped it
     * @return The evaluation
     * @throws NullPointerException if an argument is {@code null}
     */
    public static RuleEvaluation of(Rule rule, Outcome outcome) {
        return new RuleEvaluation(rule, outcome, null);
    }

    /**
     * Creates an evaluation with the detail the rule's condition was explained with. The engine creates its own; this
     * is for a class that implements {@link RulesEngine}, such as a decorator or a test double, and for tests.
     *
     * @param rule    The rule
     * @param outcome What evaluating its condition found, or that the run skipped it
     * @param detail  Why the condition evaluated as it did, in its language's own terms, or {@code null} for none
     * @return The evaluation
     * @throws NullPointerException if {@code rule} or {@code outcome} is {@code null}
     */
    public static RuleEvaluation of(Rule rule, Outcome outcome, @Nullable Object detail) {
        return new RuleEvaluation(rule, outcome, detail);
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
     * Returns what evaluating the rule's condition found, or that the run skipped the rule.
     *
     * @return The outcome
     */
    public Outcome outcome() {
        return result;
    }

    /**
     * Returns why the rule's condition evaluated as it did, in its language's own terms, such as the operands a
     * comparison read: the {@link io.github.brantunger.unruly.api.language.ConditionResult#detail() detail} the
     * language returned from
     * {@link io.github.brantunger.unruly.api.language.CompiledCondition#evaluateWithDetail evaluateWithDetail}. The
     * engine always records it when the language gives one. What it is, and what it's worth, is up to the language.
     *
     * @return The detail, or {@code null} if there is none. On an evaluation the engine created, that's when the
     *         language gave none, or when the rule was {@link Outcome#SKIPPED skipped} or
     *         {@link Outcome#NOT_EVALUATED not evaluated}. One created with {@link #of(Rule, Outcome, Object)} has
     *         whatever detail it was given, whatever its outcome.
     */
    public @Nullable Object detail() {
        return explanation;
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
