package io.github.brantunger.unruly.api.language;

/**
 * A compiled action, shared by every run of its rule list.
 *
 * <p>
 * Several runs can execute one compiled action at the same time, each with its own {@link Session}, which only one run
 * uses at a time. Keep what changes while the action runs in the session.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface is a {@code default} method, so an
 * existing language keeps compiling and working.
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
     * @param session The run's session for this action's language, created by the compiler that compiled it
     * @throws Exception if the action fails
     */
    void execute(ActionContext context, Session session) throws Exception;
}
