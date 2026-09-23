package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * What evaluating a condition returned, and, from a language that can explain it, why: the value, which must be a
 * {@link Boolean}, and a detail. When the value is a {@link Boolean}, the engine records the detail on the rule's
 * {@link io.github.brantunger.unruly.api.RuleEvaluation#detail() evaluation} in the run's result, unless the run
 * stops first; any other value fails the run, and the detail is dropped.
 *
 * <p>
 * The detail is the language's own, such as the operands a comparison read or a trace of the subexpressions it
 * evaluated. The engine doesn't read it, only keeps it, so a caller can read it after the run. That's after the
 * run's session has gone back to the engine, to be used by another run or closed, so the detail must not be the
 * {@link Session} or hold it, nor anything that reads from it later.
 * </p>
 *
 * <p>
 * A result without a detail for {@code true} or {@code false} is one of two shared instances, so returning one
 * allocates nothing.
 * </p>
 */
public final class ConditionResult {

    /** The result of a condition that was {@code true}, without a detail. */
    public static final ConditionResult TRUE = new ConditionResult(Boolean.TRUE, null);

    /** The result of a condition that was {@code false}, without a detail. */
    public static final ConditionResult FALSE = new ConditionResult(Boolean.FALSE, null);

    private final @Nullable Object result;
    private final @Nullable Object explanation;

    private ConditionResult(@Nullable Object value, @Nullable Object detail) {
        this.result = value;
        this.explanation = detail;
    }

    /**
     * Returns the result of a condition without a detail: {@link #TRUE} or {@link #FALSE} for a {@link Boolean}.
     *
     * @param value What the condition evaluated to. Anything but a {@link Boolean}, including {@code null}, fails the
     *              rule.
     * @return The result
     */
    public static ConditionResult of(@Nullable Object value) {
        if (value instanceof Boolean matched) {
            return matched ? TRUE : FALSE;
        }
        return new ConditionResult(value, null);
    }

    /**
     * Returns the result of a condition with the detail that explains it. With a {@code null} detail, it's
     * {@link #of(Object)}.
     *
     * @param value  What the condition evaluated to. Anything but a {@link Boolean}, including {@code null}, fails
     *               the rule.
     * @param detail Why, in the language's own terms, or {@code null} for no detail. It must not be the
     *               {@link Session} or hold it.
     * @return The result
     */
    public static ConditionResult of(@Nullable Object value, @Nullable Object detail) {
        return detail == null ? of(value) : new ConditionResult(value, detail);
    }

    /**
     * Returns what the condition evaluated to.
     *
     * @return The value, which fails the rule unless it's a {@link Boolean}
     */
    public @Nullable Object value() {
        return result;
    }

    /**
     * Returns why the condition evaluated to its value, in the language's own terms.
     *
     * @return The detail, or {@code null} if the language gave none
     */
    public @Nullable Object detail() {
        return explanation;
    }

    /**
     * Compares the value and the detail, each with its own {@code equals}, so a detail that is an array is compared by
     * identity, as {@link ActionResult} compares the values of its properties.
     *
     * @param other The object to compare with
     * @return Whether {@code other} is a result with an equal value and an equal detail
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ConditionResult that && Objects.equals(result, that.result)
                && Objects.equals(explanation, that.explanation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, explanation);
    }

    // A boolean without a detail is always TRUE or FALSE, as of() returns no other.
    @Override
    public String toString() {
        if (explanation != null) {
            return "ConditionResult.of(" + result + ", " + explanation + ")";
        }
        if (result instanceof Boolean matched) {
            return matched ? "ConditionResult.TRUE" : "ConditionResult.FALSE";
        }
        return "ConditionResult.of(" + result + ")";
    }
}
