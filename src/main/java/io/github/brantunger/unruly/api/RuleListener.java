package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.util.Map;

/**
 * A listener interface to hook into the lifecycle of rule evaluation and execution.
 * Implement this interface to receive callbacks before and after rules are evaluated and executed.
 * Any exception thrown by a listener, including a {@link StackOverflowError} or {@link AssertionError}, is caught
 * and logged by the engine, ensuring the core execution is not interrupted. Any other {@link Error}, such as an
 * {@link OutOfMemoryError}, propagates out of {@code run()} once every listener has received the same callback, also
 * when it is the cause of an exception the listener throws. If it came from a {@code before*} callback, the condition
 * or action doesn't run, and every listener first gets {@link #onError} to close that callback.
 *
 * <p>
 * <b>Thread safety:</b> an engine shared across threads invokes the same listener from every
 * thread calling {@code run()}, possibly at the same time, so implementations must be
 * thread-safe. Listeners may be registered at any time, including from inside a callback.
 * A listener registered while a run is in progress may start receiving callbacks partway
 * through that run, but always with a {@code before*} callback, never with the {@code after*} or
 * {@code onError} that closes one.
 * </p>
 *
 * <p>
 * Every callback receives its own copy of the {@link Rule}. Changing it has no effect on the engine, on other
 * listeners, or on later callbacks and runs.
 * </p>
 */
public interface RuleListener {

    /**
     * Called before a rule's condition is evaluated.
     *
     * @param rule  The rule being evaluated.
     * @param facts A read-only view of the fact values, keyed by fact name. Writing to it throws
     *              {@link UnsupportedOperationException}.
     */
    default void beforeEvaluate(Rule rule, Map<String, Object> facts) {
        // default empty implementation
    }

    /**
     * Called after a rule's condition is evaluated.
     *
     * @param rule        The rule that was evaluated.
     * @param facts       A read-only view of the fact values, keyed by fact name. Writing to it throws
     *                    {@link UnsupportedOperationException}.
     * @param matchResult The boolean result of the condition evaluation.
     */
    default void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
        // default empty implementation
    }

    /**
     * Called before a rule's action is executed.
     *
     * @param rule   The rule whose action is about to be executed.
     * @param output The current output object.
     */
    default void beforeExecute(Rule rule, Object output) {
        // default empty implementation
    }

    /**
     * Called after a rule's action is executed.
     *
     * @param rule   The rule whose action was executed.
     * @param output The output object after execution.
     */
    default void afterExecute(Rule rule, Object output) {
        // default empty implementation
    }

    /**
     * Called when evaluating a rule's condition or executing its action fails, in place of
     * {@link #afterEvaluate} or {@link #afterExecute}. Every {@code before*} callback is followed by
     * exactly one call to the matching {@code after*} method or to this method, so resources opened
     * in {@code before*} (timers, tracing spans, logging context) can always be closed.
     *
     * <p>
     * {@code error} is the exception that {@code run()} throws once all listeners have been notified.
     * This includes a condition or action that throws an {@link Error}: a {@link StackOverflowError} or
     * {@link AssertionError} is wrapped in {@code error}. For any other {@link Error}, such as an
     * {@link OutOfMemoryError}, {@code error} wraps it and {@code run()} rethrows the original error instead. That
     * includes an error thrown by Java code the rule calls, such as a method, a getter or a lambda held in a fact,
     * which reaches the engine as the cause of another exception.
     * Errors found while compiling rules in {@code setRuleList()} are not reported here.
     * </p>
     *
     * @param rule  The rule whose condition or action failed.
     * @param error The exception about to be thrown from {@code run()}.
     */
    default void onError(Rule rule, RuleExecutionException error) {
        // default empty implementation
    }
}
