package io.github.brantunger.unruly.api.language;

/**
 * A compiled action.
 *
 * <p>
 * The engine never executes one compiled action on two threads at once: overlapping runs each use their own
 * {@link #copy()}. An action that several threads can execute at the same time returns itself from {@code copy()}.
 * </p>
 */
public interface CompiledAction {

    /**
     * Executes the action, which changes {@link ActionContext#output()} in place. Variables it declares stay local to
     * this execution. An exception it throws fails the rule with a
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}, except a fatal {@link Error}, which
     * {@code run()} rethrows unchanged.
     *
     * @param context The facts of the run and the output object
     */
    void execute(ActionContext context);

    /**
     * Returns an action that a concurrent run can execute while this one is in use.
     *
     * @return A new copy, or this action if several threads can execute it at the same time
     */
    CompiledAction copy();
}
