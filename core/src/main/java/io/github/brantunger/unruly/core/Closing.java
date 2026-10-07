package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Closes the sessions and compilers expression languages created for a rule list, and the values languages kept for a
 * run with {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScopedClosing}. Whatever a
 * {@code close()} throws, any {@link Throwable}, is logged at WARN, and the rest are still closed; unless it's the
 * failure of a {@code run()} or a {@code load()} the {@code close()} started, or a fatal {@link Error} that run logged,
 * which that run or load logged already (see {@link LoggedFailures#logged}). A fatal error (see
 * {@link Failures#fatalError}) is returned unchanged once everything has been closed, for the caller to throw once it
 * has closed the rest of what it closes: the first, if several were thrown, carrying the others as suppressed
 * exceptions (see {@link Failures#first}). Nothing else a {@code close()} throws reaches the caller.
 */
final class Closing {

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);
    private static final String ITS_COMPILER = "its compiler";

    private Closing() {
    }

    /**
     * Closes the sessions of one copy of the rules.
     *
     * @param sessions The sessions by language name
     * @return The first fatal {@link Error} a session threw, or {@code null} if none did
     */
    static Error sessions(Map<String, Session> sessions) {
        return closeAll(sessions, "a session");
    }

    /**
     * Closes the compilers of a rule list.
     *
     * @param compilers The compilers by language name
     * @return The first fatal {@link Error} a compiler threw, or {@code null} if none did
     */
    static Error compilers(Map<String, ExpressionCompiler> compilers) {
        return closeAll(compilers, ITS_COMPILER);
    }

    /**
     * Closes one compiler of a rule list, for a caller that closes them one at a time.
     *
     * @param language The name of the compiler's language
     * @param compiler The compiler
     * @return The fatal {@link Error} it threw, or {@code null} if it threw none
     */
    static Error compiler(String language, ExpressionCompiler compiler) {
        return close(compiler, language, ITS_COMPILER);
    }

    /**
     * Closes the values the languages kept for a run, the last made first. Each is closed whatever was thrown while
     * the one before was closed, even by logging what its {@code close()} threw, as logging can throw when it runs
     * out of stack, or by keeping what logging threw, as that can when it runs out of memory. What logging throws
     * isn't logged: it reaches the caller, carrying what the {@code close()} threw as a suppressed exception. What a
     * {@code close()} threw is never lost when handling it fails: it's held, by assignment only, and added once every
     * value has been closed. Only combining what was thrown, then, can throw.
     *
     * @param values The values, in the order they were made
     * @return What the caller throws, or {@code null} if nothing is to be thrown: the first fatal {@link Error} met,
     *         in the order the values were closed, whether it was logged or logging it failed; or else the first
     *         other throwable that escaped handling a value's failure. Either carries the rest as suppressed
     *         exceptions (see {@link Failures#fatalFirst}), except those that escaped handling after the first: of
     *         them only the first is kept, and of what the values threw, the last fatal error the JVM threw, or
     *         else the first.
     */
    // Any Throwable: one that stopped the loop would leave the rest of the values open.
    static Throwable runValues(List<AutoCloseable> values) {
        Throwable failed = null;
        Throwable escaped = null;
        Throwable held = null;
        for (int i = values.size() - 1; i >= 0; i--) {
            Throwable thrown = null;
            try {
                LoggedFailures.callOut();
                values.get(i).close();
            } catch (Throwable e) {
                thrown = e;
            }
            if (thrown != null) {
                try {
                    failed = runValueFailed(thrown, failed);
                } catch (Throwable t) {
                    // Only assignments and instanceof checks, which can't fail as handling the failure did, so what
                    // close() threw isn't lost, a VirtualMachineError but a StackOverflowError above all, and the next
                    // value is still closed.
                    if (escaped == null) {
                        escaped = t;
                    }
                    if (held == null
                            || thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError)) {
                        held = thrown;
                    }
                }
            }
        }
        Faults.at(Faults.Step.RUN_VALUES_CLOSED);
        return Failures.fatalFirst(Failures.fatalFirst(failed, escaped), held);
    }

    /**
     * Handles what a value a language kept for a run threw from {@code close()}, and adds it to what the values closed
     * before it threw, the first fatal {@link Error} first (see {@link Failures#fatalFirst}): a fatal error it threw
     * once it's logged, or, if logging it fails, what logging threw, carrying what it threw.
     *
     * @param thrown What the value's {@code close()} threw
     * @param failed What the values closed before it threw that the caller throws, or {@code null}
     * @return What the caller throws, with this value's failure added
     */
    // Any Throwable: what logging throws is the caller's to throw.
    private static Throwable runValueFailed(Throwable thrown, Throwable failed) {
        try {
            Faults.at(Faults.Step.RUN_VALUE_FAILURE_LOGGED);
            return Failures.fatalFirst(failed, failedToClose(thrown, null, null));
        } catch (Throwable logging) {
            Faults.at(Faults.Step.RUN_VALUE_FAILURE_LOGGED);
            // As failedToClose would have, before it failed: an interrupt that made close() fail is kept.
            Failures.keepInterruptStatus(thrown);
            // What close() threw goes with it, so a fatal Error among it isn't lost.
            return Failures.fatalFirst(Failures.fatalFirst(failed, logging), thrown);
        }
    }

    private static Error closeAll(Map<String, ? extends AutoCloseable> resources, String what) {
        Error fatal = null;
        for (Map.Entry<String, ? extends AutoCloseable> resource : resources.entrySet()) {
            fatal = Failures.first(fatal, close(resource.getValue(), resource.getKey(), what));
        }
        return fatal;
    }

    // Any Throwable: one that stopped the loop would leave the rest open, and callers that close more after this, such
    // as the compilers after the sessions, would never reach them. Every caller is inside a run, a load(), a
    // validate() or a close(), so what a run a close() starts logged is known.
    private static Error close(AutoCloseable resource, String language, String what) {
        try {
            LoggedFailures.callOut();
            resource.close();
            return null;
        } catch (Throwable e) {
            return failedToClose(e, language, what);
        }
    }

    /**
     * Handles what a {@code close()} threw: keeps the thread's interrupt status, and logs it at WARN unless it was
     * logged already. The warning is written out here rather than handed in as a lambda, so a failed borrow, which may
     * be the JVM's first failure, closes what it made without linking a call site, which could overflow deep in a
     * stack (#1093).
     *
     * @param e        What the {@code close()} threw
     * @param language The name of the language whose session or compiler it was, or {@code null} for a value a
     *                 language kept for a run, which belongs to no one language: the run's context is shared by all
     *                 of them
     * @param what     What it was, such as {@code a session}, or {@code null} for a run's value
     * @return The fatal {@link Error} in it, or {@code null} if there is none
     */
    private static Error failedToClose(Throwable e, String language, String what) {
        Failures.keepInterruptStatus(e);
        if (!LoggedFailures.logged(e)) {
            if (language == null) {
                log.warn("A value a language kept for the run failed to close: {}", Failures.describe(e));
            } else {
                log.warn("The '{}' expression language failed to close {}: {}", Failures.quote(language), what,
                        Failures.describe(e));
            }
        }
        return Failures.fatalError(e);
    }
}
