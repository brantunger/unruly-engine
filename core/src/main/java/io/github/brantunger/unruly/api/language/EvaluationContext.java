package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What a condition is evaluated against.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a language. It's sealed, so a language can't implement it; a
 * language's unit tests create one with {@code io.github.brantunger.unruly.test.LanguageTestContexts}, from the
 * {@code unruly-engine-test} artifact. Because only the engine implements it, a later release can add methods to it
 * without breaking languages.
 * </p>
 *
 * <p>
 * <b>Identity:</b> a context equals only itself, however its facts change and whatever another run's facts are, and its
 * {@code hashCode()} reads neither the facts nor, for an {@link ActionContext}, the output object. A run passes one
 * evaluation context to every condition, and a new action context to each action, so the two are different objects. A
 * {@link Session} serves one run at a time and later runs reuse it, and no call to it marks where a run starts or
 * ends, so state a run leaves in a session is still there for the next run; state for one run belongs in
 * {@link #runScoped}, or, if it holds a resource that must be released, such as a runtime's context or interpreter
 * opened for the run, in {@link #runScopedClosing}, which closes it when the run ends. A map keyed on contexts must not
 * keep them alive, as a {@link java.util.WeakHashMap} doesn't.
 * </p>
 */
public sealed interface EvaluationContext
        permits ActionContext, io.github.brantunger.unruly.core.EngineEvaluationContext {

    /**
     * Returns the values of the run's facts by name.
     *
     * @return A read-only map, whose values can be {@code null}; writing to it throws
     *         {@link UnsupportedOperationException}
     */
    Map<String, @Nullable Object> facts();

    /**
     * Returns whether the run must stop, because its thread has been interrupted or it has passed its deadline.
     *
     * <p>
     * The engine checks this itself before each condition and each action, and again when each one returns, so a
     * language that evaluates an expression and returns needn't. A language whose expressions can stop part-way polls
     * it, or maps it to its own cancellation, so a long-running expression stops too; one whose runtime can be stopped
     * only from outside registers an action with {@link #onCancel(Runnable)} for the deadline. Returning any value is
     * enough: the engine stops the run as soon as the expression returns, whatever it returned. Throwing an exception
     * once the run is cancelled stops the run the same way; an {@link Error}, or an exception with one anywhere in its
     * cause chain, such as one wrapping what a fact's Java code threw, is still that rule's failure. A language that
     * can't stop inside an expression runs it to its end, and the run stops when it returns.
     * </p>
     *
     * @return {@code true} if the run must stop
     */
    boolean isCancelled();

    /**
     * Returns when the run must stop, from the timeout its engine or its {@code run} call was given: when the run
     * started, on the system clock, plus its timeout. So it compares with {@link Instant#now()}, and shows the
     * deadline a person or a log expects.
     *
     * <p>
     * It is for showing. What stops the run is measured with a monotonic clock from when the run started, so a step
     * of the system clock while the run goes on moves {@link Instant#now()}, but not when the run stops. To time a
     * language's own work, use {@link #timeLeft()}, which is exact; {@link #isCancelled()} says whether the run must
     * stop.
     * </p>
     *
     * @return The deadline, or {@code null} if the run has none
     */
    @Nullable Instant deadline();

    /**
     * Returns how long the run has left before it must stop, measured as the engine measures it. A language that gives
     * a call of its own a timeout, such as a script it runs on another thread, gives it this much time; working it out
     * from {@link #deadline()} and {@link Instant#now()} instead is off by as much as the system clock has been
     * stepped since the run started.
     *
     * <p>
     * A run without a deadline has {@code Duration.ofNanos(Long.MAX_VALUE)}, about 292 years, and a run whose deadline
     * is further away than that has a little less: so the time left always converts with {@link Duration#toNanos()}
     * or {@link Duration#toMillis()} without overflowing. The time left says nothing about an interrupt:
     * {@link #isCancelled()} answers for both.
     * </p>
     *
     * @return The time left: positive while the deadline is ahead, {@link Duration#ZERO} once it has passed, and at
     *         most {@code Duration.ofNanos(Long.MAX_VALUE)}
     */
    default Duration timeLeft() {
        return io.github.brantunger.unruly.core.EngineEvaluationContext.timeLeft(this);
    }

    /**
     * Has {@code action} run once when the run passes its deadline, unless the registration returned is closed first.
     * It is for a language whose runtime can be stopped part-way only from outside the expression, by setting a flag
     * or cancelling the runtime, so it needs no timer of its own: the action does that, and the expression stops.
     *
     * <p>
     * The action runs while the expression runs, on a pooled daemon platform thread, or on a virtual thread of its own
     * when the pool can't start one, so it must be thread-safe; if no thread can be started for it, as when the JVM is
     * out of memory, that is logged at WARN and the action never runs. If the engine's cancel timer, the thread that
     * waits for the deadline, can't be started, this method throws instead, and nothing is registered. The action's
     * thread inherits none of the run's inheritable thread-locals, and has no context class loader. A pooled thread
     * runs later actions, of any run or engine, and after each resets only its context class loader and interrupt
     * status, so the action must remove any {@link ThreadLocal} value or MDC entry it sets, or the next action on that
     * thread sees it. The action should be short, such as setting a flag that the runtime reads: one still running, or
     * not yet started because no thread was free, a second after the timer handed it over is logged at WARN, though a
     * slow action delays no other, of this run or any other. What it throws is logged at WARN and ignored. If the
     * deadline has already passed, the action runs on this thread before this method returns, and a fatal
     * {@link Error} it throws, a {@link VirtualMachineError} such as {@link OutOfMemoryError} but not a
     * {@link StackOverflowError}, or an exception that carries one, is thrown from this method instead. A run without a
     * deadline registers nothing: the registration returned does nothing when closed. Passing the deadline stops the
     * run as {@link #isCancelled()} describes, and never interrupts its thread, so neither does the action unless it
     * interrupts it itself. An interrupt never runs the action: {@link #isCancelled()} still answers for both.
     * </p>
     *
     * <p>
     * The deadline is the one {@link #deadline()} shows, measured as {@link #timeLeft()} measures it, so a nested run's
     * is no later than the run it was started from. Every context of a run, the evaluation context and each action
     * context, registers against the same deadline. The run closes the registrations still open when it ends, as it
     * closes the values kept with {@link #runScopedClosing}, and none can be registered after that. Closing doesn't
     * wait for an action that has started, so an action whose deadline passed just as its expression or its run
     * ended may still run just after, even once the run has returned. A language that reuses its runtime therefore
     * gives each expression a stop flag of its own, made fresh for it and registered and closed with it, or checks
     * {@link #isCancelled()} before it acts on the flag, so a late action can't stop a later expression.
     * </p>
     *
     * @param action What to run when the run passes its deadline
     * @return The registration, whose {@link CancelRegistration#close()} stops the action from running if it hasn't
     *         started
     * @throws NullPointerException  if {@code action} is {@code null}
     * @throws IllegalStateException if the run has ended, or its registrations are being closed
     * @throws VirtualMachineError   the fatal error the action threw, or carried, when the deadline had already
     *                               passed and the action ran on this thread; or the error, such as an
     *                               {@link OutOfMemoryError}, with which starting the engine's cancel timer thread
     *                               failed, in which case nothing is registered
     */
    default CancelRegistration onCancel(Runnable action) {
        return io.github.brantunger.unruly.core.EngineEvaluationContext.onCancel(this, action);
    }

    /**
     * Returns the value kept under {@code key} for this run, making it with {@code init} the first time any condition
     * or action of the run asks for it. So a language that converts the facts before it evaluates an expression, such
     * as with {@link FactProperties#toData}, can convert them once per run rather than once per expression.
     *
     * <p>
     * Every condition and action of one {@code run()} shares the values: the evaluation context and each action context
     * of the run return the same value for a key. A nested run has values of its own, and the next run starts with
     * none. A key the language owns, such as its compiler, keeps its values apart from another language's. The same
     * key must always hold the same type, or the caller gets a {@link ClassCastException}.
     * </p>
     *
     * <p>
     * The value is made when it is first asked for, and isn't made again during the run, so it doesn't see a change
     * that Java code makes to a fact later in the run. The engine keeps the values until the run returns, and then lets
     * them go without closing them: a resource a language opens for one run belongs in {@link #runScopedClosing},
     * which closes it when the run ends, and one it keeps from run to run in its {@link Session}. Another thread can
     * ask for a value too, such as a script the language runs on another thread: a request for a key whose
     * {@code init} is running on another thread throws {@link IllegalStateException} rather than waiting for it. If
     * {@code init} throws, nothing is kept, and the next call for the key calls its {@code init} again. An
     * {@code init} can ask for other keys, but not for its own: that throws {@link IllegalStateException}, rather than
     * recursing or making the value twice.
     * </p>
     *
     * @param key  The key, compared with {@link Object#equals(Object)}
     * @param init Makes the value the first time the run asks for {@code key}
     * @param <T>  The value's type
     * @return The value kept under {@code key}, never {@code null}
     * @throws NullPointerException  if {@code key} or {@code init} is {@code null}, or {@code init} returns
     *                               {@code null}
     * @throws IllegalStateException if the {@code init} of {@code key} is running, so it asked for its own key or is
     *                               running on another thread, or the run keeps a value under {@code key} with
     *                               {@link #runScopedClosing}
     */
    default <T> T runScoped(Object key, Supplier<? extends T> init) {
        return io.github.brantunger.unruly.core.EngineEvaluationContext.runScoped(this, key, init);
    }

    /**
     * Returns the value kept under {@code key} for this run, as {@link #runScoped} does, and closes it when the run
     * ends. It is for a resource a language opens for one run and must release, such as a runtime's context or
     * interpreter, or a connection the run's expressions share.
     *
     * <p>
     * The engine closes the values once the run has ended, however it ends: it returns, fails, passes its deadline or
     * is interrupted. It closes them on the thread that ran it, after the run's last listener call and before
     * {@code run()} returns. The run still holds its copy of the rules then, so no other run uses its sessions until
     * its values are closed, and no session of the copy is closed before them. They are closed in the reverse of the
     * order they were made, so a value whose {@code init} asked for another is closed before that one, and each is
     * closed whatever the others throw. What a {@code close()} throws is handled as what a {@link Session#close()}
     * throws is: it is logged at WARN, and the run's outcome stands, unless it is a fatal {@link Error}, such as an
     * {@link OutOfMemoryError}. Once every value is closed, {@code run()} throws that error in place of the result, or
     * of a failure of the run that isn't fatal, which the error carries as a suppressed exception; a run that failed
     * with a fatal error of its own throws its own, carrying the one from {@code close()}. A nested run closes its own
     * values when it ends.
     * </p>
     *
     * <p>
     * A key is either closing or not: asking with this method for a key that the run keeps a value under with
     * {@link #runScoped}, or the other way round, throws {@link IllegalStateException}. So does asking for a value
     * with this method once the run's values are being closed, or have been, such as from a value's {@code close()} or
     * through a context kept past its run, as that value would never be closed. This method can be called from another
     * thread while the run ends: the value is then either closed with the run's others, or refused with
     * {@link IllegalStateException}, and never left open, unless the run's end fails twice, as it can when the stack
     * or the heap runs out. The {@code init}s of closing values run one at a time: a call
     * on another thread while one runs waits for it, then gets the value made for its key, or, if that {@code init}
     * threw, makes its own. The wait has no limit and ignores interrupts, keeping the interrupt status; the run's end
     * waits the same way, and a waiting call is refused once the run begins to end. So an {@code init} that waits for
     * another thread which itself calls this method hangs both. A language's unit tests close a test context's values
     * with {@code io.github.brantunger.unruly.test.LanguageTestContexts.endRun}. If an {@code init} ends the run that
     * way itself and returns a value, nothing is kept under {@code key}: the value is closed, and refused with the
     * {@link IllegalStateException} a later call gets, which carries what that {@code close()} threw as a suppressed
     * exception, unless that is or carries a fatal {@link Error}. The error is thrown in its place, carrying the
     * {@link IllegalStateException}. Otherwise the values behave as {@link #runScoped} describes.
     * </p>
     *
     * @param key  The key, compared with {@link Object#equals(Object)}
     * @param init Makes the value the first time the run asks for {@code key}
     * @param <T>  The value's type
     * @return The value kept under {@code key}, never {@code null}
     * @throws NullPointerException  if {@code key} or {@code init} is {@code null}, or {@code init} returns
     *                               {@code null}
     * @throws IllegalStateException if the {@code init} of {@code key} is running, so it asked for its own key, the
     *                               run keeps a value under {@code key} with {@link #runScoped}, or the run's values
     *                               are being or have been closed, including when {@code init} itself ended the run
     */
    default <T extends AutoCloseable> T runScopedClosing(Object key, Supplier<? extends T> init) {
        return io.github.brantunger.unruly.core.EngineEvaluationContext.runScopedClosing(this, key, init);
    }
}
