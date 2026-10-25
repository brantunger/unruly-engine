package io.github.brantunger.unruly.core;

import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;

/**
 * Holds an engine's rule set from one {@code load()} to the next: swaps in each rule list {@code load()} is given, once
 * {@link RuleListCompiler} has compiled it and its copies are made, and retires the rule sets the engine no longer
 * uses, on {@code load()} and {@code close()}. It owns the engine's current rule set, whether the engine is closed,
 * the rule sets still to retire, in its {@link RetireQueue}, and the lock that guards them; runs read the current
 * rule set with {@link #currentOrThrow(String)}.
 * It holds the engine's copy limit and permits, its compiler and its logger, all fixed when the engine is built, and
 * the stall window a test can set.
 */
final class RuleSetLifecycle {

    private static final String CLOSED_MESSAGE = "The engine is closed";
    // What load() logs when rules the engine no longer uses couldn't all be retired, once it has swapped its own in, or
    // when its own rules couldn't be, under a fatal error of its own.
    private static final String RETIRE_AGAIN = "Rules this engine no longer uses couldn't all be retired, so the next"
            + " load() or close() tries again: {}";
    // The engine's logger, which logs what loading a rule list fails with, and what retiring couldn't finish.
    private final Logger log;
    // Compiles the rule lists load() is given, with the engine's languages, imports and options.
    private final RuleListCompiler compiler;
    // Volatile so a load() call on one thread is seen by run() on others. The copy pool holds the rules and the
    // compilers of the languages they use, in its LoadedRules, and is fully built before it is assigned, so one
    // volatile write swaps in both. Assigned while holding lifecycle, so each rule set replaced is retired once, and
    // none is assigned after close().
    private volatile CopyPool ruleSet;
    // Set by close() while holding lifecycle, before it lets go of ruleSet, so a reader that finds no rule set and
    // then reads this sees the engine closed if close() took the rules (see currentOrThrow).
    private volatile boolean closed;
    private final Object lifecycle = new Object();
    // The rule sets still to retire, and the claims each load() or close() retires them by. Guarded by lifecycle.
    private final RetireQueue toRetire = new RetireQueue(lifecycle);
    // How many compiled copies of the rules runs hold at once, and which runs that applies to.
    private final CopyLimit copyLimit;
    // The permits for copyLimit, which every rule list this engine loads shares, so a reload can't raise the limit on
    // runs holding copies.
    private final CopyPermits copyPermits;
    // How long a run waits without one copy being given back before it makes an extra one. Only a test changes it,
    // with stallWindow(long).
    private long stallWindowMillis = CopyPool.STALL_WINDOW_MILLIS;
    // How many copies of the rules load() makes, before runs can see them.
    private final int copiesAtLoad;

    /**
     * Creates the rule-set lifecycle of an engine, with no rules loaded. Built with the engine, so
     * {@code RuleSetLifecycle} itself is loaded then, not by a {@code load()} or {@code close()}, which may be called
     * deep in a run's stack, from an action (see StackHeadroom).
     *
     * @param log      The engine's logger
     * @param compiler Compiles the rule lists {@code load()} is given
     * @param settings The engine's settings: how many compiled copies of the rules runs hold at once, and which runs
     *                 that applies to, and how many copies {@code load()} makes, before runs can see them
     */
    RuleSetLifecycle(Logger log, RuleListCompiler compiler, EngineSettings settings) {
        this.log = log;
        this.compiler = compiler;
        this.copyLimit = settings.copyLimit();
        this.copyPermits = new CopyPermits(copyLimit.maxCopies());
        this.copiesAtLoad = settings.copiesAtLoad();
    }

    /**
     * Returns the rule set runs start with: the last one {@link #load(List)} swapped in.
     *
     * @return The rule set, or {@code null} if no rules are loaded or the engine is closed
     */
    CopyPool current() {
        return ruleSet;
    }

    /**
     * Returns the rule set runs start with, for a caller that can't go on without one. The rule set is read first, and
     * whether the engine is closed only if there is none: {@link #close()} marks the engine closed before it lets go
     * of the rule set, so finding none on an engine that is closing finds it closed. A call made while it closes, once
     * it has marked the engine closed and before it lets go of the rule set, still gets the rule set, while
     * {@link #checkOpen()} already fails.
     *
     * @param notLoadedMessage What the exception says when the engine is open and no rules are loaded
     * @return The rule set
     * @throws IllegalStateException if no rules are loaded, or the engine is closed
     */
    CopyPool currentOrThrow(String notLoadedMessage) {
        CopyPool rules = ruleSet;
        if (rules == null) {
            throw new IllegalStateException(closed ? CLOSED_MESSAGE : notLoadedMessage);
        }
        return rules;
    }

    /**
     * Returns the rule set runs start with, for a caller that can go on without one, but not on a closed engine. The
     * rule set is read first, and whether the engine is closed only if there is none, as in
     * {@link #currentOrThrow(String)}, so a call made while {@link #close()} runs may still get it.
     *
     * @return The rule set, or {@code null} if no rules are loaded
     * @throws IllegalStateException if the engine is closed
     */
    CopyPool currentIfOpen() {
        CopyPool rules = ruleSet;
        if (rules == null && closed) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        return rules;
    }

    /**
     * Fails if {@link #close()} has closed the engine.
     *
     * @throws IllegalStateException if the engine is closed
     */
    void checkOpen() {
        if (closed) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
    }

    /**
     * Sets how long the runs of the rule lists loaded from now on wait without one copy being given back before they
     * make an extra one, as {@link AbstractRulesEngine#stallWindow(long)} describes.
     *
     * @param millis How long a run waits, in milliseconds
     */
    void stallWindow(long millis) {
        stallWindowMillis = millis;
    }

    /**
     * Loads a rule list, as {@link AbstractRulesEngine#load(List)} describes.
     *
     * @param ruleList The rules
     */
    void load(List<Rule> ruleList) {
        // Before the load counts or compiles anything, as in run().
        StackHeadroom.check();
        // A run a language starts while this compiles or makes copies is nested in the load, so a fatal Error it
        // logged isn't logged again here (see LoggedFailures).
        LoggedFailures.enterLoad();
        try {
            loadRules(ruleList);
        } finally {
            LoggedFailures.leave();
        }
    }

    /**
     * Loads the rules, as {@link #load(List)} describes.
     *
     * @param ruleList The rules
     */
    // The rule set closes the compilers, or this method does if the rule list fails to load. Any Throwable once the
    // rule set exists: it must be retired however making its copies ends, as a finally would, and a failure that
    // isn't fatal is kept under a fatal Error from closing; and once it's swapped in, only a fatal Error is thrown.
    private void loadRules(List<Rule> ruleList) {
        Objects.requireNonNull(ruleList, "ruleList must not be null");
        // Checked here so a closed engine rejects any list, and again under the lock, for a close() that runs while
        // this compiles.
        checkOpen();
        // A null rule or a duplicate name stops the load before anything is compiled: duplicate names would make
        // error messages, exceptions and listener logs ambiguous.
        List<RuleCompilationException> listProblems = compiler.listProblems(ruleList, RuleListCompiler.Mode.LOAD);
        if (!listProblems.isEmpty()) {
            RuleCompilationException first = listProblems.get(0);
            Failures.logError(log, first.getMessage());
            throw LoggedFailures.loggedByLoad(first);
        }
        RuleListCompiler.Compilation compilation = compiler.compilation(RuleListCompiler.Mode.LOAD);
        CopyPool loaded;
        try {
            compilation.compile(ruleList);
            compilation.throwIfAnyFailed();
            loaded = new CopyPool(compilation.loadedRules(), copyLimit, copyPermits, stallWindowMillis,
                    new OneStepStack<>());
        } catch (Throwable t) {
            // Any Throwable, as in validate(): the compilers created must be closed however compiling ends.
            Failures.throwIfPresent(Failures.closingInsteadOf(t, compilation.closeCompilers()));
            throw t;
        }
        // From here on the rule set owns the compilers, so a failure retires it, which closes the copies made so far,
        // the one that failed too, and then the compilers, once: even when the call to make them is what fails.
        try {
            prepareCopies(loaded);
        } catch (Throwable t) {
            retireBefore(loaded, t);
            throw t;
        }
        long claim;
        boolean closedMeanwhile;
        synchronized (lifecycle) {
            claim = toRetire.newClaim();
            closedMeanwhile = closed;
            if (!closedMeanwhile) {
                // The rules replaced join the rules still to retire before they're replaced, so they're never only
                // in a local, and this call claims them, with any left by earlier calls that no other has claimed.
                CopyPool replaced = ruleSet;
                if (replaced != null) {
                    toRetire.add(replaced);
                }
                ruleSet = loaded;
                toRetire.claim(claim);
            }
        }
        // Retired after the lock, as close() retires its rules: a language slow to close its compilers mustn't hold
        // up every close() and load(). No run can reach rules that weren't swapped in.
        if (closedMeanwhile) {
            IllegalStateException failure = new IllegalStateException(CLOSED_MESSAGE);
            retireBefore(loaded, failure);
            throw failure;
        }
        // The new rules are in, and runs use them, so the load has succeeded: only a fatal Error is thrown now. What
        // couldn't be retired is retired again by the next load() or close().
        Throwable failed;
        try {
            failed = toRetire.retireClaimed(claim);
        } catch (Throwable t) {
            // Any Throwable, as retiring each rule set catches: retiring them failed before it began.
            failed = t;
        }
        Failures.throwIfPresent(Failures.fatalError(failed));
        if (failed != null) {
            Failures.logWarn(log, RETIRE_AGAIN, NestedFailures.describe(failed));
        }
    }

    /**
     * Makes the copies of the rules the engine was built to make at load. The caller retires the rule set if this
     * fails, which closes the copies made so far, the one that failed too, and then the compilers, once.
     *
     * @param loaded The rule set, which no run can see yet
     * @throws RuleCompilationException if a language can't create or warm up a session: already logged, as
     *                                  {@code load()} logs every failure, and recorded as logged, so a run around a
     *                                  nested {@code load()} doesn't log it again, unless what it holds is a nested
     *                                  run's or load's failure, which the code around names instead (see
     *                                  {@link LoggedFailures})
     */
    // The cause is what the language threw, as when a language can't create its compiler: the ReportedFailure around
    // it is the engine's own wrapper for a run, and was logged when it was made, or holds a failure that was. The
    // exception thrown in its place is the engine's too, so the code around a nested load() takes its message, which
    // has a nested failure's text, for adding nothing to it (see LoggedFailures).
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void prepareCopies(CopyPool loaded) {
        Faults.at(Faults.Step.COPIES_PREPARED);
        try {
            loaded.prepareCopies(copiesAtLoad);
        } catch (ReportedFailure e) {
            RuleCompilationException failure = LoggedFailures.builtByEngine(
                    new RuleCompilationException(e.getMessage(), e.getCause()));
            // Logged as the language's failure unless it held a nested run's or load's, which that one logged.
            if (NestedFailures.nestedRunFailure(e.getCause()) == null) {
                LoggedFailures.loggedByLoad(failure);
            }
            throw failure;
        }
    }

    /**
     * Retires a rule set that {@code load()} won't swap in, before the caller throws {@code failure}: closes its
     * copies, and then its compilers. A fatal {@link Error} from closing them is thrown here instead, carrying
     * {@code failure} as suppressed, or logging it at WARN if the error can't carry one, unless {@code failure} is a
     * fatal error itself, which came first, and carries the one from closing (see {@link Failures#closingInsteadOf}). A
     * rule set that retiring fails to mark retired, as it can when it runs out of stack, is kept for the next
     * {@code load()} or {@code close()} to retire. What else stopped the retiring, even before it began, is thrown
     * here instead of {@code failure}, carrying it, unless {@code failure} holds a fatal error, which came first: then
     * that error carries it (see {@link Failures#insteadOf}). What retiring threw that isn't thrown, fatal or
     * not, is logged at WARN once while it leaves the rule set to a later {@code load()} or {@code close()}, as a
     * {@code load()} that swapped its rules in logs it, so the compilers left open aren't news only to the code that
     * catches the error; one the thrown error can't carry is logged instead (see {@link Failures#keepAlso}).
     *
     * @param loaded  The rule set, which no run can see
     * @param failure What the caller throws next
     */
    private void retireBefore(CopyPool loaded, Throwable failure) {
        Throwable failed;
        try {
            // In the try, as the calls that claim and add the rule set can overflow the stack: what they throw is
            // weighed against the load's own failure as a retire failure is, and nothing is retired with a claim
            // that was never made.
            long claim;
            synchronized (lifecycle) {
                claim = toRetire.newClaim();
                toRetire.addClaimed(loaded, claim);
            }
            failed = toRetire.retireClaimed(claim);
        } catch (Throwable t) {
            // Any Throwable, as after a swap: retiring them failed before it began, and the load's own failure is
            // still weighed against it.
            failed = t;
        }
        Throwable instead = Failures.insteadOf(failure, failed);
        // What retiring threw says it when it's thrown, and keepAlso logged it when it couldn't be carried. Carried,
        // it's logged once here while the rule set is left for a later load() or close(); a fatal error closing it
        // threw, which leaves it retired, was logged as it was caught. An overflow in the log call loses only the line,
        // as after a swap (#1217).
        if (Failures.carries(instead != null ? instead : failure, failed) && !loaded.retiredForGood()) {
            Failures.logWarn(log, RETIRE_AGAIN, NestedFailures.describe(failed));
        }
        Failures.rethrowUnchecked(instead);
    }

    /**
     * Closes the engine's rules, as {@link AbstractRulesEngine#close()} describes.
     */
    // A closed engine has no rule set.
    @SuppressWarnings("PMD.NullAssignment")
    void close() {
        // Before the engine is marked closed, so a close() that overflows here leaves it open, to close again.
        StackHeadroom.check();
        // As in load(): a run a language's close() starts is nested in this one, so what it logged isn't logged again
        // (see LoggedFailures). Counted before the rules are taken from the engine, so failing to count it leaves the
        // engine as it was.
        LoggedFailures.enter();
        try {
            long claim;
            synchronized (lifecycle) {
                if (closed && ruleSet != null) {
                    // Only a close() made from inside this very one, between its two writes below, as a test's watch
                    // does (see Faults.Step.CLOSE_MARKED), finds the engine closed with its rules still held. The
                    // close() around it lets go of them and retires them; detaching them again here would link them
                    // to themselves, and leave RetireQueue.claim looping for ever.
                    return;
                }
                claim = toRetire.newClaim();
                // The rules join the rules still to retire before the engine lets go of them, as in load(), and this
                // call claims them, with any left by earlier calls that no other has claimed.
                CopyPool detached = ruleSet;
                if (detached != null) {
                    toRetire.add(detached);
                }
                // Marked closed before the rules are let go of: a reader reads the rule set first, and whether the
                // engine is closed only if there is none, so one that finds no rule set finds the engine closed, never
                // an engine that was never loaded.
                closed = true;
                try {
                    // Watched only, never failed. Calling it can still overflow the stack, and a test's watch can
                    // throw, so the rules are let go of in a finally: a closed engine that still held its rules would
                    // leave the next close() linking them to themselves. The finally only writes a field, so it calls
                    // nothing and can't overflow itself.
                    Faults.reached(Faults.Step.CLOSE_MARKED);
                } finally {
                    ruleSet = null;
                }
                toRetire.claim(claim);
            }
            Failures.rethrowUnchecked(toRetire.retireClaimed(claim));
        } finally {
            LoggedFailures.leave();
        }
    }
}
