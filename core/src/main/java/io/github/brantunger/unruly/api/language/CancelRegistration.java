package io.github.brantunger.unruly.api.language;

/**
 * An action a language registered with {@link EvaluationContext#onCancel(Runnable)}, to be run when its run passes its
 * deadline. Closing it before then means the action never runs.
 *
 * <p>
 * <b>Returned by</b> the engine. A language's own unit tests may implement it, as a fake; a method added to this
 * interface is a {@code default} method, so such an implementation keeps compiling.
 * </p>
 */
// Not a functional interface: the engine returns it, and a later release may give it more methods.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface CancelRegistration extends AutoCloseable {

    /**
     * Stops the action from running, if it hasn't started yet. Closing it while the action runs, or after it has run,
     * does nothing, and doesn't wait for the action to finish. It can be called any number of times, on any thread,
     * and throws nothing.
     */
    @Override
    void close();
}
