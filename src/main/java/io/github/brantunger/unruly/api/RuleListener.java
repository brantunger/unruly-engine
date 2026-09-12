package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.util.Map;

/**
 * A listener interface to hook into the lifecycle of rule evaluation and execution.
 * Implement this interface to receive callbacks before and after rules are evaluated and executed.
 * Any exceptions thrown by a listener will be caught and logged by the engine,
 * ensuring the core execution is not interrupted.
 *
 * <p>
 * <b>Thread safety:</b> an engine shared across threads invokes the same listener from every
 * thread calling {@code run()}, possibly at the same time, so implementations must be
 * thread-safe. Listeners may be registered at any time, including from inside a callback.
 * A listener registered while a run is in progress may start receiving callbacks partway
 * through that run.
 * </p>
 */
public interface RuleListener {

    /**
     * Called before a rule's condition is evaluated.
     *
     * @param rule  The rule being evaluated.
     * @param facts The facts map that will be passed to the rule condition.
     */
    default void beforeEvaluate(Rule rule, Map<String, Object> facts) {
        // default empty implementation
    }

    /**
     * Called after a rule's condition is evaluated.
     *
     * @param rule        The rule that was evaluated.
     * @param facts       The facts map that was passed to the rule condition.
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
