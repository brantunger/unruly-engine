package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

/**
 * A compiled condition.
 *
 * <p>
 * The engine never evaluates one compiled condition on two threads at once, as long as {@link #copy()} returns a new
 * object: each run evaluates its own copy, and the condition the compiler returned is only copied, never evaluated. A
 * condition that several threads can evaluate at the same time returns itself from {@code copy()}.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface in a 1.x release is a {@code default}
 * method, so an existing language keeps compiling and working.
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
    @Nullable Object evaluate(EvaluationContext context);

    /**
     * Returns a condition for one run to evaluate. The engine calls it on the condition the compiler returned, which
     * no run evaluates, and may call it from several threads at once, so a copy must be built only from state that
     * doesn't change, such as the source and what it was compiled with. By default, returns this condition, which is
     * right for a condition that several threads can evaluate at the same time.
     *
     * @return A new copy, or this condition if several threads can evaluate it at the same time. Throwing or returning
     *         {@code null} fails the run with a {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}.
     */
    default CompiledCondition copy() {
        return this;
    }
}
