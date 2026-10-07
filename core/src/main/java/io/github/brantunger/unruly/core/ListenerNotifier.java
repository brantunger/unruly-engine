package io.github.brantunger.unruly.core;

import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

/**
 * Tells an engine's listeners what its runs do: calls each run and rule callback on every listener, logging what a
 * listener throws so a faulty listener can't interrupt a run, and reports each rule's failure to
 * {@link RuleListener#onError} and each run's to {@link RuleListener#onRunError}, rethrowing a fatal {@link Error} once
 * every listener has been told. It owns the engine's listeners, fixed when the engine is built, and the failure a
 * rule's fatal error was reported with on each thread, which {@code onRunError} gets in turn. It holds the engine's
 * logger.
 */
final class ListenerNotifier {

    // The engine's logger, which logs what a listener throws, and the failures reported to the listeners.
    private final Logger log;
    // Fixed when the engine is built, so every callback of a run goes to the same listeners.
    private final List<RuleListener> listeners;
    // The failure a rule's fatal Error was reported to onError with, so onRunError gets the same one, naming the rule.
    // Set just before the error is rethrown, and removed when a run starts and ends.
    private final ThreadLocal<RuleExecutionException> fatalFailure = new ThreadLocal<>();

    /**
     * Creates the listener notifier of an engine. Built with the engine, so {@code ListenerNotifier} itself is loaded
     * then, not by a run's first callback, which may come deep in a run's stack (see StackHeadroom).
     *
     * @param log       The engine's logger
     * @param listeners The engine's listeners, in the order they're called
     */
    ListenerNotifier(Logger log, List<RuleListener> listeners) {
        this.log = log;
        this.listeners = listeners;
    }

    /**
     * Returns the failure a rule's fatal {@link Error} was reported to {@code onError} with, on this thread, which
     * {@link #runFailure} gives {@code onRunError} and the rule's event reads to tell a stop from a failure. Recorded
     * just before the error is rethrown, and cleared when a run starts and ends.
     *
     * @return The recorded failure, or {@code null} if none is recorded
     */
    RuleExecutionException recordedFatalFailure() {
        return fatalFailure.get();
    }

    /**
     * Clears what a rule of a run on this thread recorded as its fatal failure, as a run does when it starts and when
     * it ends.
     */
    void clearFatalFailure() {
        fatalFailure.remove();
    }

    /**
     * Returns the exception {@code onRunError} gets for a fatal {@link Error} leaving a run: the one the rule it came
     * from was reported to {@code onError} with, which names the rule, or else one that names no rule. A rule's is
     * recorded just before its error is rethrown, and nothing between there and the run catches or replaces it.
     *
     * @param error The error the run is rethrowing
     * @return The exception for {@code onRunError}
     */
    RuleExecutionException runFailure(Error error) {
        RuleExecutionException reported = fatalFailure.get();
        return reported != null
                ? reported
                : new RuleExecutionException("The run failed with " + Failures.describeWithClass(error), error);
    }

    /**
     * Closes a run that failed with {@code onRunError} on every listener. A stop is recorded on the tally first, so
     * a fatal error a listener throws in its place still leaves the run's event saying the run stopped, as the
     * listeners were told, and so is an interrupt that caused it, so the thread's interrupt status is set again
     * before the copy is given back and when {@code run()} returns (see
     * {@link AbstractRulesEngine#keepInterruptOfStop}). A fatal error a listener throws is rethrown in place of what
     * the run failed with, and carries that as a suppressed exception (see {@link Failures#keepAlso}).
     *
     * @param runs    What is in progress on the run's thread, which marks each listener's callback as a call-out (see
     *                {@link LoggedFailures#callOut()})
     * @param error   The exception listeners are told of
     * @param failing What the run throws if no listener throws a fatal error: {@code error}, or the fatal error it
     *                carries
     */
    void notifyRunError(LoggedFailures.Runs runs, RunContext run, RuntimeException error, Throwable failing,
                        RunTally tally) {
        if (ReportedFailure.isStop(error)) {
            tally.markStopped();
            if (error.getCause() instanceof InterruptedException) {
                tally.markInterrupted();
            }
        }
        notifyRun(runs, "onRunError", new OnRunError(run, error), error, failing);
    }

    /**
     * Calls {@link RuleListener#onRunError}. A class of its own rather than a lambda, as is {@link OnError}, so the
     * JVM's first failure links no call site, and loaded when the JVM's first engine is built (see RunClasses), so it
     * loads no class either, either of which could overflow deep in a stack (#1093).
     */
    static final class OnRunError implements Consumer<RuleListener> {
        private final RunContext run;
        private final RuntimeException error;

        OnRunError(RunContext run, RuntimeException error) {
            this.run = run;
            this.error = error;
        }

        @Override
        public void accept(RuleListener listener) {
            listener.onRunError(run, error);
        }
    }

    /** Calls {@link RuleListener#onError}, as {@link OnRunError} calls {@code onRunError}. */
    static final class OnError implements Consumer<RuleListener> {
        private final CompiledRule rule;
        private final RuleExecutionException error;

        OnError(CompiledRule rule, RuleExecutionException error) {
            this.rule = rule;
            this.error = error;
        }

        @Override
        public void accept(RuleListener listener) {
            listener.onError(rule.rule(), error);
        }
    }

    /**
     * Calls one run callback on every listener, logging what a listener throws, like the rule callbacks. A fatal
     * {@link Error} a listener throws is rethrown once every listener has had the callback, and logged first unless a
     * run it started logged it already (see {@link LoggedFailures}), or else what it wrapped the error in, when that
     * says something of its own (see {@link #listenerFatalMessage}). Each listener's callback is a call-out of the
     * run's own (see {@link LoggedFailures#callOut()}), marked through {@code runs}.
     */
    void notifyRun(LoggedFailures.Runs runs, String callback, Consumer<RuleListener> call) {
        notifyRun(runs, callback, call, null, null);
    }

    /**
     * Calls one run callback on every listener, as {@link #notifyRun(LoggedFailures.Runs, String, Consumer)} does, for
     * a callback that tells listeners of the run's failure. A fatal {@link Error} a listener throws carries what it's
     * rethrown in place of as a suppressed exception (see {@link Failures#keepAlso}). The failure the callback tells
     * listeners of, rethrown or wrapped, is the run's own, whatever run logged its fatal error, so nothing a listener
     * wrapped around it is taken for news about a nested run.
     *
     * @param told    The exception the callback tells listeners of, or {@code null}; see
     *                {@link #logListenerException}
     * @param failing What the run throws if no listener throws a fatal error, or {@code null}
     */
    private void notifyRun(LoggedFailures.Runs runs, String callback, Consumer<RuleListener> call, Throwable told,
                           Throwable failing) {
        ListenerFatal thrown = listenerFatal(runs, callback, call, null, told);
        if (thrown != null) {
            Error fatal = thrown.fatal();
            // Asked before unlogged() records a fatal error it's told of for the first time.
            boolean wrapped = !thrown.ofTold() && Failures.wrapsLoggedFatal(thrown.thrown());
            boolean logs = thrown.ofTold() ? LoggedFailures.unloggedFatal(fatal)
                    : LoggedFailures.unlogged(thrown.thrown());
            if (logs) {
                log.error(listenerFatalMessage(thrown, callback, wrapped));
            }
            Failures.keepAlso(fatal, failing);
            throw fatal;
        }
    }

    /**
     * Closes the rule's open {@code before*} callback with {@code onError} and the exception a stopped run throws. A
     * fatal {@link Error} a listener throws is rethrown, and isn't logged, unless the listener wrapped one logged
     * already in an exception that says something of its own, which is logged (see {@link #logWrappedFromOnError}).
     *
     * @param rule The rule the run stopped in
     * @param stop The exception the run stops with
     * @return {@code stop}, to throw
     */
    RuleExecutionException closedWithStop(CompiledRule rule, ReportedFailure stop) {
        // Only a rule's condition, action or output writer stops a run here, inside run(), so a run is in progress.
        ListenerFatal thrown = listenerFatal(LoggedFailures.inProgress(), "onError", new OnError(rule, stop), null,
                stop);
        if (thrown != null) {
            Error fatal = logWrappedFromOnError(thrown, rule);
            stop.addSuppressedByEngine(fatal);
            fatalFailure.set(stop);
            throw fatal;
        }
        return stop;
    }

    /**
     * Calls a {@code before*} callback on every listener. If one throws a fatal {@link Error}, the condition or action
     * doesn't run: every listener gets {@link RuleListener#onError} to close the callback it received, and the error
     * is rethrown. It's logged first, unless a run the listener started logged it already (see
     * {@link LoggedFailures}), or else what the listener wrapped it in, when that says something of its own (see
     * {@link #listenerFatalMessage}). Each listener's callback is marked as a call-out through {@code runs}.
     */
    void notifyBefore(LoggedFailures.Runs runs, CompiledRule rule, String callback, Consumer<RuleListener> call) {
        ListenerFatal thrown = listenerFatal(runs, callback, call);
        if (thrown != null) {
            Error fatal = thrown.fatal();
            // Described before unlogged() records a fatal error it's told of for the first time.
            String msg = listenerFatalMessage(thrown, forRule(callback, rule),
                    Failures.wrapsLoggedFatal(thrown.thrown()));
            RuleExecutionException failure = new RuleExecutionException(msg, fatal, rule.rule().getRuleName());
            // Already on its way out of run(), so a second fatal error from onError can't replace it, but it's kept.
            keepSecondFatal(failure, fatal,
                    reportFailure(rule, failure, LoggedFailures.unlogged(thrown.thrown())));
            fatalFailure.set(failure);
            throw fatal;
        }
    }

    /**
     * Calls an {@code after*} callback on every listener, then logs the first fatal {@link Error} one threw at ERROR,
     * unless a run the listener started logged it already (see {@link LoggedFailures}), or else what the listener
     * wrapped it in, when that says something of its own (see {@link #listenerFatalMessage}), and rethrows it. Every
     * listener already closed its callback, so none gets {@code onError}. Each listener's callback is marked as a
     * call-out through {@code runs}.
     */
    void notifyAfter(LoggedFailures.Runs runs, CompiledRule rule, String callback, Consumer<RuleListener> call) {
        ListenerFatal thrown = listenerFatal(runs, callback, call);
        if (thrown != null) {
            // Asked before unlogged() records a fatal error it's told of for the first time.
            boolean wrapped = Failures.wrapsLoggedFatal(thrown.thrown());
            if (LoggedFailures.unlogged(thrown.thrown())) {
                log.error(listenerFatalMessage(thrown, forRule(callback, rule), wrapped));
            }
            throw thrown.fatal();
        }
    }

    /**
     * Logs what a listener's {@code onError} wrapped a fatal {@link Error} logged already in, when that says something
     * of its own (see {@link Failures#wrapsLoggedFatal}), at ERROR, as {@link #listenerFatalMessage} says it. Nothing
     * else logs it: the error itself is rethrown, or kept on the failure's own, and isn't logged there again.
     *
     * @param thrown The fatal error a listener threw from {@code onError}, and what it threw it in
     * @param rule   The rule whose failure {@code onError} closed
     * @return The fatal error
     */
    private Error logWrappedFromOnError(ListenerFatal thrown, CompiledRule rule) {
        if (Failures.wrapsLoggedFatal(thrown.thrown())) {
            log.error(listenerFatalMessage(thrown, forRule("onError", rule), true));
        }
        return thrown.fatal();
    }

    private static String forRule(String callback, CompiledRule rule) {
        return callback + " for rule '" + rule.displayName() + "'";
    }

    /**
     * Says that a listener threw a fatal {@link Error} in a callback, and, when it wrapped one logged already in an
     * exception with a message of its own (see {@link Failures#wrapsLoggedFatal}), what that says, with the error as a
     * note, as {@link Failures#describe} describes it. When reading that throws, as it can when the JVM has no memory
     * left, only the first part is said, so the caller still rethrows the error.
     *
     * @param thrown   The fatal error, and what the listener threw it in
     * @param callback The callback, and the rule it was for, if any
     * @param wrapped  Whether the listener wrapped a fatal error logged already, asked before it was recorded as
     *                 logged, if it's news
     * @return The message
     */
    private static String listenerFatalMessage(ListenerFatal thrown, String callback, boolean wrapped) {
        String plain = "A listener threw " + thrown.fatal().getClass().getName() + " in " + callback;
        if (!wrapped) {
            return plain;
        }
        try {
            Faults.at(Faults.Step.FAILURE_DESCRIBING);
            return plain + ": " + Failures.describe(thrown.thrown());
        } catch (Throwable e) {
            return plain;
        }
    }

    /**
     * The first fatal {@link Error} a listener threw in a callback, and what it threw it in: the error itself, or an
     * exception that has it among its causes.
     *
     * @param fatal  The fatal error
     * @param thrown What the listener threw
     * @param ofTold {@code true} if it's the very fatal error of the exception the callback told listeners of, which
     *               is the run's own, whatever run logged it
     */
    record ListenerFatal(Error fatal, Throwable thrown, boolean ofTold) {

        // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods,
        // which can fail for good when first called deep in the stack (#996). equals compares the components last
        // first, as ObjectMethods does.
        @Override
        public final boolean equals(Object other) {
            return this == other || other instanceof ListenerFatal that && ofTold == that.ofTold
                    && Objects.equals(thrown, that.thrown) && Objects.equals(fatal, that.fatal);
        }

        @Override
        public final int hashCode() {
            int hash = Objects.hashCode(fatal);
            hash = hash * 31 + Objects.hashCode(thrown);
            return hash * 31 + Boolean.hashCode(ofTold);
        }

        @Override
        public final String toString() {
            return "ListenerFatal[fatal=" + fatal + ", thrown=" + thrown + ", ofTold=" + ofTold + "]";
        }
    }

    /**
     * Calls every listener, logging what a listener throws so a faulty listener can't interrupt a run. A fatal
     * {@link Error} (see {@link Failures#fatalError}), thrown or found among the causes of what a listener throws, or
     * suppressed on them, doesn't stop the other listeners either, so each still gets the callback, and closes whatever
     * it opened; the error is returned for the caller to rethrow, with what the listener threw it in. A second fatal
     * error in the same callback is logged like an exception, and kept on the first as a suppressed exception (see
     * {@link Failures#keepAlso}).
     *
     * <p>
     * Each listener's callback is a call-out of its own (see {@link LoggedFailures#callOut()}), so what a run one
     * listener started logged isn't below the next listener. Once every listener has been called, the call-out of the
     * listener that threw the fatal error is taken up again, so what that listener's own nested run logged is still
     * below it when the caller tells what the listener wrapped the error in.
     * </p>
     *
     * @param runs What is in progress on the run's thread, which marks each call-out
     * @return The first fatal {@link Error} a listener threw, and what it threw it in, or {@code null}
     */
    private ListenerFatal listenerFatal(LoggedFailures.Runs runs, String callback, Consumer<RuleListener> call) {
        return listenerFatal(runs, callback, call, null, null);
    }

    /**
     * Calls every listener, as {@link #listenerFatal(LoggedFailures.Runs, String, Consumer)} does, ignoring what the
     * run already reports: a listener that rethrows the reported exception, or the fatal {@link Error} in it, has added
     * nothing, so it doesn't count as the first fatal error, whichever listener rethrows it. What a listener wrapped it
     * in is still logged, because its own message says something.
     *
     * @param runs     What is in progress on the run's thread, which marks each call-out
     * @param reported The exception listeners were told about, whose fatal {@link Error} the run is already
     *                 reporting, or {@code null}
     * @param told     The exception the callback tells listeners of, or {@code null}; see
     *                 {@link #logListenerException}
     * @return The first fatal {@link Error} a listener threw that the run isn't reporting, and what it threw it in, or
     *         {@code null}
     */
    // Rethrowing the very same instance is what makes it nothing new; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private ListenerFatal listenerFatal(LoggedFailures.Runs runs, String callback, Consumer<RuleListener> call,
                                        RuleExecutionException reported, Throwable told) {
        Error reportedFatal = reported == null ? null : Failures.fatalError(reported);
        Error fatal = null;
        Throwable fatalThrown = null;
        long fatalCall = 0;
        for (RuleListener listener : listeners) {
            long started = 0;
            try {
                started = runs.callOut();
                call.accept(listener);
            } catch (Throwable e) {
                Failures.keepInterruptStatus(e);
                Error found = Failures.fatalError(e);
                if (found != null && found == reportedFatal) {
                    if (e != reported && e != reportedFatal) {
                        logListenerException(callback, e, told);
                    }
                } else if (found != null && fatal == null) {
                    fatal = found;
                    fatalThrown = e;
                    fatalCall = started;
                } else {
                    // A non-fatal exception, or a second fatal error in this callback, which the caller can't rethrow
                    // but finds on the first.
                    logListenerException(callback, e, told);
                    Failures.keepAlso(fatal, found);
                }
            }
        }
        if (fatal == null) {
            return null;
        }
        runs.resume(fatalCall);
        return new ListenerFatal(fatal, fatalThrown, fatal == Failures.fatalError(told));
    }

    /**
     * Logs what a listener threw, where it isn't the error {@code run()} goes on to throw. A failure of a
     * {@code run()} or a {@code load()} the listener started, and a fatal {@link Error} such a run logged, isn't logged
     * at WARN, as that run or load logged it already (see {@link LoggedFailures#logged}), unless an exception wrapped
     * around it says something of its own: that's logged, described with the nested failure as a note, as
     * {@code ... (after a nested run() failed: ...)}, or, around a fatal error, with its class. The failure the
     * callback told the listener of, rethrown or wrapped, isn't one, nor is its fatal error: it's described with its
     * class, as anything else is. The stack trace is logged at DEBUG either way.
     *
     * @param callback The callback the listener threw from
     * @param thrown   What it threw
     * @param told     The exception the callback told the listener of, or {@code null}
     */
    // The innermost failure of the very run whose failure the listener was told of is no nested run's, nor is the very
    // fatal error that run failed with.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void logListenerException(String callback, Throwable thrown, Throwable told) {
        Error fatal = Failures.fatalError(thrown);
        boolean rethrowsTold = fatal != null ? fatal == Failures.fatalError(told)
                : Failures.below(thrown).logged() == Failures.below(told).logged();
        boolean hasNested = fatal == null && !rethrowsTold && Failures.below(thrown).logged() != null;
        // Escaped, like every message the engine logs: a listener's message can quote request data. The stack trace,
        // which prints the message as it is, goes to DEBUG for whoever debugs the listener; it's left out if printing
        // it throws, as a listener's own exception can.
        if (rethrowsTold || !LoggedFailures.logged(thrown)) {
            log.warn("Listener threw exception in {}: {}", callback,
                    hasNested ? Failures.describe(thrown) : Failures.describeWithClass(thrown));
        }
        logStackTrace(log, "Listener threw exception in " + callback, thrown);
    }

    /**
     * Logs a message at DEBUG with a throwable the engine didn't create, for the logging backend to print its stack
     * trace. Printing it calls the {@code toString()} of the throwable, of each of its causes and of each suppressed
     * exception, and one of a listener's own can throw; the stack trace is then left out, whatever was thrown, a fatal
     * {@link Error} too, rather than let that end the failure handling the call is part of (see
     * {@link Failures#messageOf}).
     *
     * @param log     The logger
     * @param message The message
     * @param thrown  The throwable whose stack trace is logged
     */
    static void logStackTrace(Logger log, String message, Throwable thrown) {
        try {
            log.debug(message, thrown);
        } catch (Throwable ignored) {
            // Only the stack trace is lost; the line before it has said what failed.
        }
    }

    /**
     * Keeps a fatal {@link Error} a listener's {@link RuleListener#onError} threw while it closed a failure that is
     * fatal itself. The failure's own error is still the one {@code run()} rethrows, so this one is logged like a
     * second fatal error in one callback, and added to the exception listeners were told about, where
     * {@link RuleListener#onRunError} finds it, and to the rethrown error (see {@link Failures#keepAlso}). One a run
     * the listener started logged already isn't logged at WARN again (see {@link LoggedFailures#logged}); its stack
     * trace is still logged at DEBUG.
     *
     * @param reported     The exception every listener's {@code onError} got
     * @param rethrown     The failure's own fatal error, which {@code run()} rethrows
     * @param fromListener The fatal error a listener threw from {@code onError}, or {@code null}
     */
    private void keepSecondFatal(RuleExecutionException reported, Error rethrown, Error fromListener) {
        if (fromListener != null) {
            if (reported instanceof ReportedFailure failure) {
                failure.addSuppressedByEngine(fromListener);
            } else {
                // Caused by the first fatal error, which is found before anything suppressed on it.
                reported.addSuppressed(fromListener);
            }
            Failures.keepAlso(rethrown, fromListener);
            // Says which one onRunError can see: the loop has already logged any later fatal error.
            if (!LoggedFailures.logged(fromListener)) {
                log.warn("Listener threw exception in onError, kept on the failure: {}",
                        Failures.describeWithClass(fromListener));
            }
            logStackTrace(log, "Listener threw exception in onError", fromListener);
        }
    }

    /**
     * Logs a run-time failure and tells every listener through {@link RuleListener#onError}, so each
     * {@code before*} callback still gets a closing call. A failure of a {@code run()} or a {@code load()} the rule
     * started isn't logged again, as that run or load logged it, nor is a fatal {@link Error} a run logged already
     * (see {@link LoggedFailures}), unless the rule wrapped a nested failure, or a fatal {@link Error} logged already,
     * in an exception with a message of its own, which is logged, with the nested failure as a note (see
     * {@link Failures#describe}); a fatal error wrapped so is still rethrown. So is what a listener's {@code onError}
     * wrapped such an error in (see {@link #logWrappedFromOnError}).
     * An interrupt in {@code cause} has set the thread's interrupt status again already: every caller with a cause is
     * reached through {@link AbstractRulesEngine#stopped}, which sets it first. Returns the exception for the
     * caller to throw, unless
     * the cause is or wraps a fatal {@link Error}, which is rethrown unchanged once listeners have been told, or a
     * listener threw a fatal error from {@code onError}, which is rethrown once every listener has been told.
     */
    RuleExecutionException failure(CompiledRule rule, ExpressionKind kind, String msg, Throwable cause) {
        ReportedFailure error = new ReportedFailure(msg, cause, rule.rule().getRuleName(), kind);
        // A failed run() started by this rule has already logged its failure, or the fatal error it rethrew.
        Error listenerFatal = reportFailure(rule, error, LoggedFailures.unlogged(cause));
        // A fatal error in what the rule threw comes first; one from a listener's onError is rethrown otherwise.
        Error fatal = Failures.fatalError(cause);
        if (fatal == null && listenerFatal != null) {
            // Not among the causes, so onRunError can still find it on the failure.
            error.addSuppressedByEngine(listenerFatal);
            fatal = listenerFatal;
        } else if (listenerFatal != null) {
            keepSecondFatal(error, fatal, listenerFatal);
        }
        if (fatal != null) {
            fatalFailure.set(error);
            throw fatal;
        }
        return error;
    }

    /**
     * Logs a failure at ERROR, unless told not to, and tells every listener through {@link RuleListener#onError}. What
     * a listener wrapped a fatal {@link Error} logged already in is logged, when it says something of its own (see
     * {@link #logWrappedFromOnError}).
     *
     * @return The first fatal {@link Error} a listener threw from {@code onError}, or {@code null}
     */
    private Error reportFailure(CompiledRule rule, RuleExecutionException error, boolean logged) {
        if (logged) {
            log.error(error.getMessage());
        }
        // Only a rule's condition, action or output writer, or a listener before one, fails a rule, inside run(), so a
        // run is in progress.
        ListenerFatal thrown = listenerFatal(LoggedFailures.inProgress(), "onError", new OnError(rule, error), error,
                error);
        return thrown == null ? null : logWrappedFromOnError(thrown, rule);
    }
}
