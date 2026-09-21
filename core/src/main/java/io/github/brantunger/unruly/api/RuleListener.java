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
 * When {@link #onError} closes a failure that is fatal itself, whether the rule or a {@code before*} callback threw
 * that error, and a listener throws another {@link VirtualMachineError} there, the failure's own error is still the
 * one {@code run()} throws: the first such error, other than that failure's own, is suppressed on the exception
 * {@link #onRunError} gets, and any later one is only logged.
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
 * added in a later 2.x release also has a default that does nothing, so an existing listener keeps compiling and
 * working.
 * </p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/listeners-and-logging.md">Listeners &amp;
 *      logging</a>
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
     * @param run The run, which identifies it and carries its facts, the checksum of the rules it uses, and the
     *            {@link RunContext#tags() tags} and {@link RunContext#startedAt() start time} that choose which of them
     *            it uses
     */
    default void beforeRun(RunContext run) {
        // default empty implementation
    }

    /**
     * Called when a run has finished, after the last {@code after*} callback.
     *
     * @param run    The run that {@link #beforeRun} opened
     * @param result What the run did: the output object, the rules that fired, each rule's outcome, the rules'
     *               checksum, and the run's {@link RunResult#tags() tags} and {@link RunResult#startedAt() start time}
     */
    default void afterRun(RunContext run, RunResult<?> result) {
        // default empty implementation
    }

    /**
     * Called when a run fails, in place of {@link #afterRun}. Unlike {@link #onError}, this reports the failures
     * that belong to no rule too, such as a stopped run or an output supplier that throws. A failure inside a rule
     * reaches that rule's {@link #onError} first, then this callback. A run that fails before it starts, because a
     * language throws or returns {@code null} when it creates a session for the run, reaches no listener at all.
     *
     * <p>
     * {@code error} is what {@code run()} throws. When {@code run()} rethrows a fatal {@link Error} instead,
     * {@code error} is a {@link RuleExecutionException} that carries it. If the error came while a rule's callback
     * was open, from the rule, a listener's {@code before*} callback or a listener's {@link #onError}, that is the
     * exception {@link #onError} got, which names the rule unless the run was stopped; for one from anywhere else,
     * such as a listener's {@link #beforeRun}, {@link #afterEvaluate} or {@link #afterExecute}, it names no rule.
     * </p>
     *
     * @param run   The run that {@link #beforeRun} opened
     * @param error The failure; {@code run()} throws it, or the fatal {@link Error} it wraps
     * @see <a href=
     * "https://github.com/brantunger/unruly-engine/blob/main/docs/listeners-and-logging.md#-callbacks">Callbacks</a>
     */
    default void onRunError(RunContext run, RuntimeException error) {
        // default empty implementation
    }

    /**
     * Called before a rule's condition is evaluated.
     *
     * @param rule  The rule being evaluated
     * @param facts A read-only view of the fact values, keyed by fact name. Writing to it throws
     *              {@link UnsupportedOperationException}.
     */
    default void beforeEvaluate(Rule rule, Map<String, @Nullable Object> facts) {
        // default empty implementation
    }

    /**
     * Called after a rule's condition is evaluated.
     *
     * @param rule        The rule that was evaluated
     * @param facts       A read-only view of the fact values, keyed by fact name. Writing to it throws
     *                    {@link UnsupportedOperationException}.
     * @param matchResult The boolean result of the condition evaluation
     */
    default void afterEvaluate(Rule rule, Map<String, @Nullable Object> facts, boolean matchResult) {
        // default empty implementation
    }

    /**
     * Called before a rule's action is executed.
     *
     * @param rule   The rule whose action is about to be executed
     * @param output The current output object
     */
    default void beforeExecute(Rule rule, Object output) {
        // default empty implementation
    }

    /**
     * Called after a rule's action is executed.
     *
     * @param rule   The rule whose action was executed
     * @param output The output object after execution
     */
    default void afterExecute(Rule rule, Object output) {
        // default empty implementation
    }

    /**
     * Called when evaluating a rule's condition or executing its action fails, in place of
     * {@link #afterEvaluate} or {@link #afterExecute}. Every {@code before*} callback is followed by exactly one
     * call to the matching {@code after*} method or to this method.
     *
     * <p>
     * {@code error} is the exception that {@code run()} throws once all listeners have been notified. An
     * {@link Error} the condition or action throws is wrapped in it; for a fatal {@link VirtualMachineError} such as
     * an {@link OutOfMemoryError}, {@code error} wraps it and {@code run()} rethrows the original error instead.
     * Errors found while compiling rules in {@code load()} are not reported here.
     * </p>
     *
     * <p>
     * A run stopped <b>between</b> rules, because its thread was interrupted or it passed its deadline, is not
     * reported here, because no callback for the next rule is open: only {@link #onRunError} is called. A run
     * stopped <b>while</b> a condition or action was running, which returns or throws once the run is cancelled,
     * closes that rule's callback here: {@code error} then has no rule name and an {@link InterruptedException} or
     * a {@link java.util.concurrent.TimeoutException} as its cause. That holds unless what it threw has an
     * {@link Error} anywhere in its cause chain: that rule's own failure is reported instead, exactly as it would be
     * with no stop pending, so {@code error} names the rule and has what the expression threw as its cause.
     * </p>
     *
     * @param rule  The rule whose condition or action failed
     * @param error The failure; {@code run()} throws it, or the fatal {@link Error} it wraps
     * @see <a href=
     * "https://github.com/brantunger/unruly-engine/blob/main/docs/listeners-and-logging.md#-guarantees">Guarantees</a>
     */
    default void onError(Rule rule, RuleExecutionException error) {
        // default empty implementation
    }
}
