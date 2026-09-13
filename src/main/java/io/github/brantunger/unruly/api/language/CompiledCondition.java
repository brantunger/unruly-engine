package io.github.brantunger.unruly.api.language;

/**
 * A compiled condition.
 *
 * <p>
 * The engine never evaluates one compiled condition on two threads at once: overlapping runs each use their own
 * {@link #copy()}. A condition that several threads can evaluate at the same time returns itself from {@code copy()}.
 * </p>
 */
public interface CompiledCondition {

    /**
     * Evaluates the condition. An exception it throws fails the rule with a
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}, except a fatal {@link Error}, which
     * {@code run()} rethrows unchanged.
     *
     * @param context The facts of the run
     * @return The result. Anything but a {@link Boolean}, including {@code null}, fails the rule.
     */
    Object evaluate(EvaluationContext context);

    /**
     * Returns a condition that a concurrent run can evaluate while this one is in use. By default, returns this
     * condition, which is right for a condition that several threads can evaluate at the same time.
     *
     * @return A new copy, or this condition if several threads can evaluate it at the same time
     */
    default CompiledCondition copy() {
        return this;
    }
}
