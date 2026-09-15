package io.github.brantunger.unruly.api.language;

/**
 * A compiled action.
 *
 * <p>
 * The engine never executes one compiled action on two threads at once, as long as {@link #copy()} returns a new
 * object: each run executes its own copy, and the action the compiler returned is only copied, never executed. An
 * action that several threads can execute at the same time returns itself from {@code copy()}.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface in a 1.x release is a {@code default}
 * method, so an existing language keeps compiling and working.
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
     * Returns an action for one run to execute. The engine calls it on the action the compiler returned, which no run
     * executes, and may call it from several threads at once, so a copy must be built only from state that doesn't
     * change, such as the source and what it was compiled with. By default, returns this action, which is right for an
     * action that several threads can execute at the same time.
     *
     * @return A new copy, or this action if several threads can execute it at the same time. Throwing or returning
     *         {@code null} fails the run with a {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}.
     */
    default CompiledAction copy() {
        return this;
    }
}
