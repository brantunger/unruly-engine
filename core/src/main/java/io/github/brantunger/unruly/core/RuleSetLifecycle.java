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
 * the rule sets still to retire and the lock that guards them; runs read the current rule set with
 * {@link #currentOrThrow(String)}.
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
    // Volatile so a load() call on one thread is seen by run() on others. The rule set holds the rules and the
    // compilers of the languages they use, and is fully built before it is assigned, so one volatile write swaps in
    // both. Assigned while holding lifecycle, so each rule set replaced is retired once, and none is assigned after
    // close().
    private volatile RuleSet ruleSet;
    // Set by close() while holding lifecycle, before it lets go of ruleSet, so a reader that finds no rule set and
    // then reads this sees the engine closed if close() took the rules (see currentOrThrow).
    private volatile boolean closed;
    private final Object lifecycle = new Object();
    // The rule sets still to retire, linked through RuleSet.nextUnretired, so adding one allocates nothing: each one a
    // reload replaced, close() detached or a failed load() couldn't swap in, from before the engine lets go of it
    // until it's retired for good. A load() or close() claims the ones no other call has claimed
    // (RuleSet.retiringClaim) and retires them; one whose retiring fails part way, as it can when it runs out of stack,
    // stays for the next load() or close(). So none is only in a call's locals at any point, and none is retired by
    // two calls at once. Guarded by lifecycle.
    private RuleSet unretired;
    // The number of the last load() or close() that claimed rule sets to retire, so each claims with its own. Guarded
    // by lifecycle.
    private long lastClaim;
    // How many compiled copies of the rules runs hold at once, and which runs that applies to.
    private final CopyLimit copyLimit;
    // The permits for copyLimit, which every rule list this engine loads shares, so a reload can't raise the limit on
    // runs holding copies.
    private final CopyPermits copyPermits;
    // How long a run waits without one copy being given back before it makes an extra one. Only a test changes it,
    // with stallWindow(long).
    private long stallWindowMillis = RuleSet.STALL_WINDOW_MILLIS;
    // How many copies of the rules load() makes, before runs can see them.
    private final int copiesAtLoad;

    /**
     * Creates the rule-set lifecycle of an engine, with no rules loaded. Built with the engine, so
     * {@code RuleSetLifecycle} itself is loaded then, not by a {@code load()} or {@code close()}, which may be called
     * deep in a run's stack, from an action (see StackHeadroom).
     *
     * @param log          The engine's logger
     * @param compiler     Compiles the rule lists {@code load()} is given
     * @param copyLimit    How many compiled copies of the rules runs hold at once, and which runs that applies to
     * @param copiesAtLoad How many copies of the rules {@code load()} makes, before runs can see them
     */
    RuleSetLifecycle(Logger log, RuleListCompiler compiler, CopyLimit copyLimit, int copiesAtLoad) {
        this.log = log;
        this.compiler = compiler;
        this.copyLimit = copyLimit;
        this.copyPermits = new CopyPermits(copyLimit.maxCopies());
        this.copiesAtLoad = copiesAtLoad;
    }

    /**
     * Returns the rule set runs start with: the last one {@link #load(List)} swapped in.
     *
     * @return The rule set, or {@code null} if no rules are loaded or the engine is closed
     */
    RuleSet current() {
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
    RuleSet currentOrThrow(String notLoadedMessage) {
        RuleSet rules = ruleSet;
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
    RuleSet currentIfOpen() {
        RuleSet rules = ruleSet;
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
            log.error(first.getMessage());
            throw LoggedFailures.loggedByLoad(first);
        }
        RuleListCompiler.Compilation compilation = compiler.compilation(RuleListCompiler.Mode.LOAD);
        RuleSet loaded;
        try {
            compilation.compile(ruleList);
            compilation.throwIfAnyFailed();
            loaded = new RuleSet(compilation.compiledRules(), compilation.usedCompilers(),
                    compilation.factNamesRead(), compilation.reservedFactNames(), compilation.usedLanguages(),
                    copyLimit, copyPermits, stallWindowMillis);
        } catch (Throwable t) {
            // Any Throwable, as in validate(): the compilers created must be closed however compiling ends.
            Failures.throwIfPresent(Failures.fatalInsteadOf(t, compilation.closeCompilers()));
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
            lastClaim++;
            claim = lastClaim;
            closedMeanwhile = closed;
            if (!closedMeanwhile) {
                // The rules replaced join the rules still to retire before they're replaced, so they're never only
                // in a local, and this call claims them, with any left by earlier calls that no other has claimed.
                RuleSet replaced = ruleSet;
                if (replaced != null) {
                    replaced.nextUnretired = unretired;
                    unretired = replaced;
                }
                ruleSet = loaded;
                claimToRetire(claim);
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
            failed = retireClaimed(claim);
        } catch (Throwable t) {
            // Any Throwable, as retiring each rule set catches: retiring them failed before it began.
            failed = t;
        }
        Failures.throwIfPresent(Failures.fatalError(failed));
        if (failed != null) {
            log.warn(RETIRE_AGAIN, Failures.describe(failed));
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
    private void prepareCopies(RuleSet loaded) {
        Faults.at(Faults.Step.COPIES_PREPARED);
        try {
            loaded.prepareCopies(copiesAtLoad);
        } catch (ReportedFailure e) {
            RuleCompilationException failure = LoggedFailures.builtByEngine(
                    new RuleCompilationException(e.getMessage(), e.getCause()));
            // Logged as the language's failure unless it held a nested run's or load's, which that one logged.
            if (Failures.nestedRunFailure(e.getCause()) == null) {
                LoggedFailures.loggedByLoad(failure);
            }
            throw failure;
        }
    }

    /**
     * Retires a rule set that {@code load()} won't swap in, before the caller throws {@code failure}: closes its
     * copies, and then its compilers. A fatal {@link Error} from closing them is thrown here instead, carrying
     * {@code failure} as suppressed, or logging it at WARN if the error can't carry one, unless {@code failure} is a
     * fatal error itself, which came first, and carries the one from closing (see {@link Failures#fatalInsteadOf}). A
     * rule set that retiring fails to mark retired, as it can when it runs out of stack, is kept for the next
     * {@code load()} or {@code close()} to retire. What else stopped the retiring, even before it began, is thrown
     * here instead of {@code failure}, carrying it, unless {@code failure} holds a fatal error, which came first: then
     * that error carries it (see {@link Failures#laterInsteadOf}). What retiring threw that isn't thrown, fatal or
     * not, is logged at WARN once while it leaves the rule set to a later {@code load()} or {@code close()}, as a
     * {@code load()} that swapped its rules in logs it, so the compilers left open aren't news only to the code that
     * catches the error; one the thrown error can't carry is logged instead (see {@link Failures#keepAlso}).
     *
     * @param loaded  The rule set, which no run can see
     * @param failure What the caller throws next
     */
    private void retireBefore(RuleSet loaded, Throwable failure) {
        long claim;
        synchronized (lifecycle) {
            lastClaim++;
            claim = lastClaim;
            loaded.nextUnretired = unretired;
            loaded.retiringClaim = claim;
            unretired = loaded;
        }
        Throwable failed;
        try {
            failed = retireClaimed(claim);
        } catch (Throwable t) {
            // Any Throwable, as after a swap: retiring them failed before it began, and the load's own failure is
            // still weighed against it.
            failed = t;
        }
        Error fatal = Failures.fatalError(failed);
        Throwable instead = fatal != null ? Failures.fatalInsteadOf(failure, fatal)
                : Failures.laterInsteadOf(failure, failed);
        // What retiring threw says it when it's thrown, and keepAlso logged it when it couldn't be carried. Carried,
        // it's logged once here while the rule set is left for a later load() or close(); a fatal error closing it
        // threw, which leaves it retired, was logged as it was caught.
        if (Failures.carries(instead != null ? instead : failure, failed) && !loaded.retiredForGood()) {
            log.warn(RETIRE_AGAIN, Failures.describe(failed));
        }
        Failures.rethrowUnchecked(instead);
    }

    // Claims the rule sets still to retire that no other call has claimed, for the call holding the claim to retire.
    // Called holding lifecycle.
    private void claimToRetire(long claim) {
        for (RuleSet rules = unretired; rules != null; rules = rules.nextUnretired) {
            if (rules.retiringClaim == 0) {
                rules.retiringClaim = claim;
            }
        }
    }

    /**
     * Retires the rule sets still to retire that the caller claimed, the one it replaced or detached first, each even
     * after retiring one before it threw (see {@link RuleSet#retire()}). Each stays among the rule sets still to
     * retire until it's retired for good, and the claim on it is given up after each attempt, so one whose retiring
     * fails part way is left for the next {@code load()} or {@code close()}; the claims are given up however this
     * ends, even when calling it fails, as it can when the stack runs out, so none is claimed for good.
     *
     * @param claim The number the caller claimed them with
     * @return The first fatal {@link Error} retiring them threw or returned, carrying the others, or else the last
     *         failure retiring them threw, carrying those before it, or {@code null} if they were all retired
     */
    // Each claim this call still holds is given up, with no call in the loop.
    private Throwable retireClaimed(long claim) {
        try {
            return retireEachClaimed(claim);
        } finally {
            synchronized (lifecycle) {
                for (RuleSet rules = unretired; rules != null; rules = rules.nextUnretired) {
                    if (rules.retiringClaim == claim) {
                        rules.retiringClaim = 0;
                    }
                }
            }
        }
    }

    private Throwable retireEachClaimed(long claim) {
        Faults.at(Faults.Step.CLAIMED_RETIRING);
        Error fatal = null;
        Throwable failure = null;
        for (RuleSet rules = claimed(claim); rules != null; rules = claimed(claim)) {
            try {
                fatal = Failures.first(fatal, rules.retire());
            } catch (Throwable t) {
                // Any Throwable: the rest are retired all the same, and what it was is reported once they are.
                Error thrownFatal = Failures.fatalError(t);
                if (thrownFatal == null) {
                    Failures.keepAlso(t, failure);
                    failure = t;
                } else {
                    fatal = Failures.first(fatal, thrownFatal);
                }
            } finally {
                settle(rules);
            }
        }
        Failures.keepAlso(fatal, failure);
        return fatal != null ? fatal : failure;
    }

    // The first rule set still to retire that the claim holds, or null if it holds none any more.
    private RuleSet claimed(long claim) {
        synchronized (lifecycle) {
            RuleSet rules = unretired;
            while (rules != null && rules.retiringClaim != claim) {
                rules = rules.nextUnretired;
            }
            return rules;
        }
    }

    // Gives up the claim on a rule set once retiring it has been tried, and takes every rule set retired for good out
    // of the rule sets still to retire.
    @SuppressWarnings("PMD.NullAssignment")
    private void settle(RuleSet tried) {
        synchronized (lifecycle) {
            tried.retiringClaim = 0;
            // Unlinked in place, so the list is whole, and in order, at every step, even if one fails part way.
            RuleSet previous = null;
            RuleSet rules = unretired;
            while (rules != null) {
                RuleSet next = rules.nextUnretired;
                Faults.at(Faults.Step.SETTLING);
                if (rules.retiredForGood()) {
                    if (previous == null) {
                        unretired = next;
                    } else {
                        previous.nextUnretired = next;
                    }
                    rules.nextUnretired = null;
                } else {
                    previous = rules;
                }
                rules = next;
            }
        }
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
                    // to themselves, and leave claimToRetire looping for ever.
                    return;
                }
                lastClaim++;
                claim = lastClaim;
                // The rules join the rules still to retire before the engine lets go of them, as in load(), and this
                // call claims them, with any left by earlier calls that no other has claimed.
                RuleSet detached = ruleSet;
                if (detached != null) {
                    detached.nextUnretired = unretired;
                    unretired = detached;
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
                claimToRetire(claim);
            }
            Failures.rethrowUnchecked(retireClaimed(claim));
        } finally {
            LoggedFailures.leave();
        }
    }
}
