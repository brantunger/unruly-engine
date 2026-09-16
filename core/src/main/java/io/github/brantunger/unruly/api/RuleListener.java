package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A listener interface to hook into the lifecycle of rule evaluation and execution.
 * Implement this interface to receive callbacks before and after rules are evaluated and executed.
 * Any exception thrown by a listener, including a {@link StackOverflowError}, an {@link AssertionError} or a
 * {@link LinkageError} such as {@link NoClassDefFoundError}, is caught and logged by the engine, ensuring the core
 * execution is not interrupted. A {@link VirtualMachineError} such as an {@link OutOfMemoryError} propagates out of
 * {@code run()} once every listener has received the same callback, also when it is the cause of an exception the
 * listener throws. If it came from a {@code before*} callback, the condition
 * or action doesn't run, and every listener first gets {@link #onError} to close that callback.
 *
 * <p>
 * <b>Thread safety:</b> an engine shared across threads invokes the same listener from every
 * thread calling {@code run()}, possibly at the same time, so implementations must be
 * thread-safe. Listeners are added when the engine is built, with
 * {@link RulesEngineBuilder#listener(RuleListener)}, and can't change afterwards.
 * </p>
 *
 * <p>
 * A {@link Rule} is immutable, so every callback receives the rule as it was passed to
 * {@link RulesEngine#load(java.util.List)}: the same instance each time.
 * </p>
 *
 * <p>
 * <b>Implementing:</b> every callback has a default that does nothing, so override only the ones you need. A callback
 * added in a 1.x release also has a default that does nothing, so an existing listener keeps compiling and working.
 * </p>
 */
public interface RuleListener {

    /**
     * Called when a run starts, before any condition is evaluated. It's followed by exactly one call to
     * {@link #afterRun} or {@link #onRunError}, so a span, timer or logging context opened here can always be closed.
     *
     * <p>
     * A run that waits for a compiled copy of the rules opens its scope when the wait ends, so the wait isn't inside
     * the pair. A {@code run()} that fails because no rules are loaded, or because the engine is closed, is misuse and
     * reaches no callback.
     * </p>
     *
     * @param run The run, which identifies it and carries its facts and the checksum of the rules it uses
     */
    default void beforeRun(RunContext run) {
        // default empty implementation
    }

    /**
     * Called when a run has finished, after the last {@code after*} callback.
     *
     * @param run    The run that {@link #beforeRun} opened
     * @param result What the run did: the output object, the rules that fired, and the rules' checksum
     */
    default void afterRun(RunContext run, RunResult<?> result) {
        // default empty implementation
    }

    /**
     * Called when a run fails, in place of {@link #afterRun}. Unlike {@link #onError}, this reports every failure of
     * the run, including the ones that belong to no rule: a fact name no language can refer to, an output supplier
     * that throws or returns {@code null}, an interrupt while the run waits for a compiled copy of the rules, and a
     * run stopped because its thread was interrupted or it passed its deadline.
     *
     * <p>
     * A failure inside a rule reaches that rule's {@link #onError} first, then this callback. {@code error} is what
     * {@code run()} throws; when a fatal {@link Error} is rethrown instead, {@code error} is the
     * {@link RuleExecutionException} that wraps it, as {@link #onError} describes.
     * </p>
     *
     * @param run   The run that {@link #beforeRun} opened
     * @param error The exception about to be thrown from {@code run()}
     */
    default void onRunError(RunContext run, RuntimeException error) {
        // default empty implementation
    }

    /**
     * Called before a rule's condition is evaluated.
     *
     * @param rule  The rule being evaluated.
     * @param facts A read-only view of the fact values, keyed by fact name. Writing to it throws
     *              {@link UnsupportedOperationException}.
     */
    default void beforeEvaluate(Rule rule, Map<String, @Nullable Object> facts) {
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
    default void afterEvaluate(Rule rule, Map<String, @Nullable Object> facts, boolean matchResult) {
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
     * This includes a condition or action that throws an {@link Error}: a {@link StackOverflowError}, an
     * {@link AssertionError} or a {@link LinkageError} such as {@link NoClassDefFoundError} is wrapped in
     * {@code error}, because a missing or unreadable class means the rule is misconfigured. For a
     * {@link VirtualMachineError} such as an {@link OutOfMemoryError}, {@code error} wraps it and {@code run()}
     * rethrows the original error instead. That
     * includes an error thrown by Java code the rule calls, such as a method, a getter or a lambda held in a fact,
     * which reaches the engine as the cause of another exception.
     * Errors found while compiling rules in {@code load()} are not reported here.
     * </p>
     *
     * <p>
     * A run stopped <b>between</b> rules, because its thread was interrupted or it passed its deadline, is not
     * reported here. The engine checks that before {@link #beforeEvaluate} and {@link #beforeExecute}, so the rule it
     * would have gone on to never started and has no callback to close. Only {@link #onRunError} is called. A run
     * stopped <b>while</b> a condition or action was running, which throws once the run is cancelled (a run it started
     * stopping at the deadline it inherited, say), closes that rule's callback here: {@code error} then has no rule
     * name and an {@link InterruptedException} or a {@link java.util.concurrent.TimeoutException} as its cause, so
     * don't count it as the rule failing.
     * </p>
     *
     * @param rule  The rule whose condition or action failed.
     * @param error The exception about to be thrown from {@code run()}.
     */
    default void onError(Rule rule, RuleExecutionException error) {
        // default empty implementation
    }
}
