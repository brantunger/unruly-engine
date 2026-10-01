package io.github.brantunger.unruly.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RuleSetInfo;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ConditionResult;

/**
 * What every engine shares, whatever its match policy: loading rules, which {@link RuleListCompiler} compiles,
 * borrowing a compiled copy for each run, evaluating conditions and running actions, listener callbacks, cancellation
 * and failure reporting. A subclass supplies the match policy through {@link #untilFirst()} and
 * {@link #fire(Matches, RuleSet, RuleSet.Copy, RunFacts)}, which {@link #runRules(FactStore, Duration, Set)} calls,
 * and names it in {@link #matchPolicy()}. The two hooks are one policy, as {@code fire} describes.
 *
 * <p>
 * The engine changes no global setting of any language: a session is used by one run at a time, so a language keeps
 * whatever changes while its expressions run there; see {@link RuleSet}.
 * </p>
 *
 * @param <O> The output object to instantiate
 */
abstract class AbstractRulesEngine<O> implements RulesEngine<O> {

    // A fixed name, documented for logging configuration, so it doesn't change when this internal class does.
    static final String LOGGER_NAME = "io.github.brantunger.unruly.engine";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private static final String CLOSED_MESSAGE = "The engine is closed";
    // What load() logs when rules the engine no longer uses couldn't all be retired, once it has swapped its own in.
    private static final String RETIRE_AGAIN = "Rules this engine no longer uses couldn't all be retired, so the next"
            + " load() or close() tries again: {}";
    // Where a cancelled run stopped, as its message says: before a rule's condition or action, or while one ran.
    private static final String BEFORE_RULE = "before";
    private static final String DURING_RULE = "during";
    // How many times in a row one run may find the same rule set closed: its first reading of it, and one more for
    // each time it reads the engine's rules again and gets back the very set it found closed. Reading again settles
    // the one race that can close a set a run has read — a reload, or close(), retires it in between the run's reading
    // and its borrow — and a reading that finds a different set shows a reload got through, so the count starts again:
    // a loader reloading fast can overtake one run any number of times. The same closed set read again means the
    // engine's current set is closed, so the bound is far above one. Past it, the invariant a run relies on has
    // broken, and a run that spun instead would leave no evidence.
    private static final int RULE_READS_PER_RUN = 64;
    // Compiles the rule lists load() and validate() are given, with the engine's languages, imports and options.
    private final RuleListCompiler compiler;
    // Fixed when the engine is built, so every callback of a run goes to the same listeners.
    private final List<RuleListener> listeners;
    // Volatile so a load() call on one thread is seen by run() on others. The rule set holds the rules and the
    // compilers of the languages they use, and is fully built before it is assigned, so one volatile write swaps in
    // both. Assigned while holding lifecycle, so each rule set replaced is retired once, and none is assigned after
    // close().
    private volatile RuleSet ruleSet;
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
    // How long a run may take, or null if runs have no deadline. A run() call can pass one of its own.
    private final Duration runTimeout;
    // The clock a run reads when it starts, to decide which rules are within their validity window.
    private final Clock clock;
    // What sets the properties actions return.
    private final OutputWriter<? super O> outputWriter;
    // Makes each run's output object, once a run has a rule to fire.
    private final Supplier<O> outputFactory;
    // Collects and checks the facts each run is given.
    private final FactIntake factIntake;
    // Numbers the engines of this JVM, so a Flight Recorder event tells one engine's runs from another's.
    private static final AtomicLong ENGINE_IDS = new AtomicLong();
    private final long engineId = ENGINE_IDS.incrementAndGet();
    // Numbers this engine's runs, so a listener can tell them apart.
    private final AtomicLong runIds = new AtomicLong();
    // The run this thread is inside, so a run an action or a listener starts knows the run around it. Removed when
    // the outermost run ends, so a pooled thread keeps nothing.
    private final ThreadLocal<RunContext> currentRun = new ThreadLocal<>();
    // The failure a rule's fatal Error was reported to onError with, so onRunError gets the same one, naming the rule.
    // Set just before the error is rethrown, and removed when a run starts and ends.
    private final ThreadLocal<RuleExecutionException> fatalFailure = new ThreadLocal<>();

    /**
     * Creates an engine with the builder's settings: takes or finds its languages and picks the default one, and
     * resolves its imports with the building thread's context class loader. A limit on copies means a run that finds
     * all of them in use waits for one; see {@link RuleSet}.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with
     * @param configuration The builder's settings
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are given
     *                                  for a language the engine doesn't have, as
     *                                  {@link io.github.brantunger.unruly.api.RulesEngineBuilder#build()} describes
     * @throws IllegalArgumentException if an import has more than 1,000 characters or more than 64 dot-separated
     *                                  parts, checked before it is looked up; if it is neither a loadable class nor a
     *                                  valid package name; or if it names a class that exists but can't be loaded,
     *                                  with the linkage error's text cut to at most 1,000 characters, with a note of
     *                                  how many were left out, then escaped, and a root cause it hides named
     * @throws NullPointerException     if {@code outputFactory} is {@code null}, checked after the settings
     */
    AbstractRulesEngine(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        LanguageRegistry languages = LanguageRegistry.resolve(configuration.languages(),
                configuration.defaultLanguage(), ImportResolver.contextClassLoader());
        // Checked once the languages are known, so an option for a language that isn't found isn't silently ignored.
        for (String language : configuration.options().keySet()) {
            if (!languages.languages().containsKey(language)) {
                throw new IllegalStateException("Options are given for the expression language '"
                        + Failures.quote(language) + "', which isn't one of the engine's expression languages: "
                        + Failures.quoteAll(new TreeSet<>(languages.languages().keySet())));
            }
        }
        Set<String> packages = new LinkedHashSet<>();
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (String name : configuration.imports()) {
            Class<?> type = ImportResolver.resolve(name);
            if (type != null) {
                classes.add(type);
            } else {
                packages.add(name);
            }
        }
        this.listeners = configuration.listeners();
        this.copyLimit = configuration.copyLimit();
        this.copyPermits = new CopyPermits(copyLimit.maxCopies());
        this.copiesAtLoad = configuration.copiesAtLoad();
        this.runTimeout = configuration.runTimeout();
        this.clock = configuration.clock();
        this.outputWriter = configuration.outputWriter();
        Map<String, Class<?>> declaredFacts = configuration.declaredFacts();
        boolean allFactsDeclared = configuration.allFactsDeclared();
        this.factIntake = new FactIntake(log, declaredFacts, allFactsDeclared);
        this.compiler = new RuleListCompiler(log, languages, Collections.unmodifiableSet(packages),
                Collections.unmodifiableSet(classes), configuration.outputType(), configuration.options(),
                declaredFacts, allFactsDeclared);
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Returns the rules as {@link #load(List)} compiled them, or {@code null} if it has not been called or the
     * engine is closed. Every run shares them, each with its own sessions, from
     * {@link #runInScope(FactStore, Duration, Set, RunBody)}.
     *
     * @return An unmodifiable list of compiled rules, or {@code null}
     */
    // null, not an empty list: an empty rule list is loaded, and null means none is.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    List<CompiledRule> getCompiledRules() {
        RuleSet rules = ruleSet;
        return rules != null ? rules.rules() : null;
    }

    /** The body of one run: what the engine does with the rules, its copy of them and the run's facts. */
    @FunctionalInterface
    interface RunBody<O> {

        /**
         * Runs the rules.
         *
         * @param rules The rule set the run uses
         * @param copy  The run's copy of the rules
         * @param facts The run's fact values, already checked, and the views built over them
         * @return What the run did
         */
        RunResult<O> run(RuleSet rules, RuleSet.Copy copy, RunFacts facts);
    }

    /**
     * Fires the rules against {@code facts} with {@code options}: its timeout if it has one, otherwise the timeout
     * the engine was built with, if any, and only the rules its tags choose, if it has any.
     *
     * @param facts   {@inheritDoc}
     * @param options {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public final RunResult<O> runWithResult(FactStore<?> facts, RunOptions options) {
        // Before the run counts, borrows or sets anything, so a caller near the end of its stack overflows here, with
        // nothing to give back (see StackHeadroom).
        StackHeadroom.check();
        Objects.requireNonNull(options, "options must not be null");
        Duration timeout = options.timeout();
        return runRules(facts, timeout == null ? runTimeout : timeout, options.tags());
    }

    /**
     * Fires the rules the way this engine fires them: evaluates the conditions in priority order, stopping at the
     * first match if {@link #untilFirst()} says to, and returns no output if no rule matched; otherwise
     * {@link #fire(Matches, RuleSet, RuleSet.Copy, RunFacts)} fires the matched rules.
     *
     * @param facts   The facts the run was given
     * @param timeout How long the run may take, or {@code null} if it has no deadline
     * @param tags    The tags that choose the rules the run uses, or none to use rules whatever their tags
     * @return What the run did: its output is {@code null} if the rule list is empty or no rule matched
     */
    RunResult<O> runRules(FactStore<?> facts, Duration timeout, Set<String> tags) {
        return runInScope(facts, timeout, tags, (ruleSet, copy, runFacts) -> {
            // Match the facts and data against the set of rules with the highest priority first.
            Matches matches = this.match(ruleSet.rules(), copy, runFacts, untilFirst());
            if (matches.matched().isEmpty()) {
                return RunResult.of(null, List.of(), matches.evaluations(), ruleSet.checksum());
            }
            return fire(matches, ruleSet, copy, runFacts);
        });
    }

    /**
     * Returns whether a run stops evaluating conditions at the first match. By default it doesn't: every condition
     * is evaluated, except those of rules the run skips. It is one policy with
     * {@link #fire(Matches, RuleSet, RuleSet.Copy, RunFacts)}, as that describes.
     *
     * @return {@code true} to stop at the first match
     */
    boolean untilFirst() {
        return false;
    }

    /**
     * Fires the action of the first rule that matched, on a new output object. The run has evaluated the conditions
     * already, and calls this only when at least one rule matched.
     *
     * <p>
     * It is one policy with {@link #untilFirst()}: firing only the first match is right when the run stops there, or
     * when an override fails a run with more than one match first, as the unique-match engine does, so a subclass
     * that overrides either must consider the other. A check that fails the run must come before
     * {@link #createOutput()}, so a failed run never calls the output factory.
     * </p>
     *
     * @param matches The rules that matched, at least one, and every rule's outcome
     * @param ruleSet The rule set the run uses
     * @param copy    The run's copy of the rules
     * @param facts   The run's facts and the views built over them
     * @return The output object the action shaped, with the rule that fired
     * @throws RuleExecutionException if the output factory throws or returns {@code null}, the action fails, or the
     *                                run was cancelled before the rule. A {@link VirtualMachineError} other than
     *                                {@link StackOverflowError} from the output factory is rethrown unchanged, as
     *                                {@link #createOutput()} describes.
     */
    RunResult<O> fire(Matches matches, RuleSet ruleSet, RuleSet.Copy copy, RunFacts facts) {
        // Run the action of the selected rule on given data and return the output.
        CompiledRule resolvedRule = matches.matched().get(0);
        O output = this.executeRule(resolvedRule, copy, createOutput(), facts);
        return RunResult.of(output, List.of(resolvedRule.rule()), matches.evaluations(), ruleSet.checksum());
    }

    /**
     * Runs one run inside its listener scope: every listener gets {@link RuleListener#beforeRun}, then the rule
     * callbacks, then exactly one of {@link RuleListener#afterRun} and {@link RuleListener#onRunError}.
     *
     * <p>
     * The fact values are collected before the run borrows a copy of the rules, so the scope can carry them. The
     * engine's own checks of them run before the run waits for a copy, so a run whose facts it rejects fails at once,
     * however many copies are in use, opening its scope without a copy. The languages check the names inside the
     * scope, once the run holds its copy, so a name no language can refer to reaches {@code onRunError} and a
     * compiler is never closed while it checks one. A run that waits for a copy opens its scope when the wait ends; an
     * interrupt while waiting opens and closes a scope of its own. Failing because no rules are loaded, or because the
     * engine is closed, is misuse and reaches no listener. Failing because the run found the same rule list closed
     * time after time while it was borrowing a copy reaches none either: that means an engine invariant has broken
     * rather than that the call was wrong.
     * </p>
     *
     * @param facts   The facts the run was given
     * @param timeout How long the run may take, or {@code null} if it has no timeout of its own. The deadline is
     *                taken from when the run starts, so waiting for a copy of the rules counts towards it, and a run
     *                started from inside another run on this thread stops no later than that run's deadline, even
     *                one started while that run reads its facts or gets or gives back its copy.
     * @param tags    The tags that choose the rules the run uses, or none to use rules whatever their tags
     * @param body    What the engine does once it holds a copy of the rules
     * @return What the run did
     * @throws IllegalStateException if {@link #load(List)} has not been called, the engine is closed, or the run found
     *                               the same rule list closed {@value #RULE_READS_PER_RUN} times in a row, reading
     *                               the engine's rules again each time, which means the engine's own invariant has
     *                               broken
     */
    // Any Throwable: the copy must be given back however the run ends, as a finally would, and a failure that isn't
    // fatal is kept under a fatal Error from closing. Whether a reading found the very set that was closed, rather
    // than one a reload put in its place, is a question of identity.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    RunResult<O> runInScope(FactStore<?> facts, Duration timeout, Set<String> tags, RunBody<O> body) {
        Objects.requireNonNull(facts, "facts must not be null");
        // Misuse, before the run is numbered or recorded: it reaches no listener and no recording either.
        RuleSet rules = currentRules();
        long runId = runIds.incrementAndGet();
        RunContext parent = currentRun.get();
        long parentRunId = parent == null ? 0 : parent.runId();
        // The event spans the whole call: reading the facts, and waiting for a copy, which counts towards the
        // deadline too.
        RunEvent event = FlightRecorderEvents.startRun();
        RunTally tally = new RunTally();
        String outcome = RunEvent.FAILED;
        // Published before the facts are read, so a run started from a fact's getValue(), from a language's
        // newSession() while the copy is made or from a session's close() while it's given back stops no later than
        // this one, as a run started from a rule or a listener does.
        Deadline deadline = Cancellation.deadlineFrom(timeout);
        // Read before the run's own deadline is set, and put back however the run ends: even when setting that
        // deadline fails once it has stored it, or counting the run fails, as an OutOfMemoryError can make it.
        Deadline enclosingDeadline = Cancellation.current();
        boolean counted = false;
        RuleSet.Copy copy = null;
        try {
            Cancellation.enter(deadline);
            LoggedFailures.enter();
            counted = true;
            // Read once, so every rule's validity window is judged at the same time, however long the run takes.
            // A clock that returns null fails the run here, like one that throws, before any listener hears of it.
            RuleSelection selection = new RuleSelection(
                    Objects.requireNonNull(clock.instant(), "the engine's clock returned a null instant"), tags);
            Map<String, Object> values = factIntake.factValues(facts);
            Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(values);
            // Checked before the run waits for a copy, which facts the engine will reject needn't do.
            RuntimeException rejected = factIntake.factRejection(values);
            RunFacts runFacts = RunFacts.of(values, listenerFacts, deadline, runId, parent, tally, selection);
            if (rejected == null) {
                copy = borrow(rules, runFacts);
                int read = 1;
                while (copy == null) {
                    // The rule set the run read was closed before it could borrow from it, which a reload does to the
                    // set it replaced, and close() to the set it detaches: reading again finds the set that replaced
                    // it, or reports the closed engine. A run the caller has stopped meanwhile stops here rather than
                    // reading again, and one that keeps finding the same set closed fails rather than spinning for
                    // ever. A different set starts the count again: a reload got through, however many overtake the
                    // run.
                    stopIfCancelledWhileReading(rules, runFacts);
                    if (read == RULE_READS_PER_RUN) {
                        throw new IllegalStateException("The engine's rule list was found closed "
                                + RULE_READS_PER_RUN + " times in a row while this run was borrowing a copy of it. A"
                                + " rule list is closed only after it has been retired, and only a rule list that is"
                                + " no longer the engine's current one is retired, so the list a run reads can never"
                                + " already be closed: that invariant has broken. Please report this stack trace at"
                                + " https://github.com/brantunger/unruly-engine/issues");
                    }
                    RuleSet again = currentRules();
                    read = again == rules ? read + 1 : 1;
                    rules = again;
                    copy = borrow(rules, runFacts);
                }
            }
            // The copy is given back however the run ends, even when setting it up fails: the engine's permits
            // outlive its rule lists, so a permit that isn't returned would lower its limit for good. A fatal Error
            // from closing the rules as it's given back replaces a failure of the run that isn't fatal. A run whose
            // facts were rejected holds no copy, and gives nothing back.
            RunResult<O> result;
            try {
                result = runWithCopy(rules, copy, runFacts, rejected, body);
            } catch (Throwable t) {
                // Given back even if setting the interrupt status again fails, as it can when it runs out of stack.
                try {
                    keepInterruptOfStop(tally);
                } finally {
                    Failures.throwIfPresent(Failures.fatalInsteadOf(t, copy == null ? null : rules.release(copy)));
                }
                throw t;
            }
            Failures.throwIfPresent(rules.release(copy));
            outcome = RunEvent.COMPLETED;
            return result;
        } catch (RuntimeException e) {
            outcome = ReportedFailure.isStop(e) ? RunEvent.STOPPED : RunEvent.FAILED;
            throw e;
        } catch (Error e) {
            outcome = tally.hasStopped() ? RunEvent.STOPPED : RunEvent.FAILED;
            throw e;
        } finally {
            // Each step in a finally of the one before, so one that fails, as one that runs out of stack does, doesn't
            // keep the next from running.
            try {
                if (counted) {
                    LoggedFailures.leave();
                }
            } finally {
                try {
                    // A permit or build slot that giving the copy back failed to give back, before it had given back
                    // anything, is given back from here, so the engine's limit isn't lowered for good.
                    if (copy != null) {
                        rules.giveBackLeft(copy);
                    }
                } finally {
                    try {
                        Cancellation.leave(enclosingDeadline);
                    } finally {
                        try {
                            // Again on the way out, as a language's close() may have cleared it too.
                            keepInterruptOfStop(tally);
                        } finally {
                            if (event != null) {
                                event.commit(engineId, runId, parentRunId, matchPolicy(), tally, rules.checksum(),
                                        outcome);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Sets the thread's interrupt status again if an interrupt stopped the run, which a listener told of the stop, or
     * a language's {@code close()}, may have cleared. Called before the run gives back its copy or leaves the rules,
     * so the closing that starts sees it set, and again as {@code run()} returns, so the caller sees it. It isn't set
     * again between one {@code close()} and the next: a language whose {@code close()} clears it hides it from the
     * sessions and compilers closed after it.
     *
     * @param tally The run's tally, which records whether an interrupt stopped the run
     */
    private static void keepInterruptOfStop(RunTally tally) {
        Faults.at(Faults.Step.INTERRUPT_KEPT);
        if (tally.wasInterrupted()) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs the rules with a copy the caller borrowed and gives back, inside the run's listener and deadline scope. A
     * run whose facts the engine rejected before it borrowed one opens the same scope without a copy, and fails in it
     * with what the check threw.
     *
     * @param copy     The copy the run borrowed, or {@code null} if its facts were rejected
     * @param facts    The run's facts, and what the run carries with them
     * @param rejected What the engine's checks of the facts threw before the run borrowed a copy, or {@code null} if
     *                 they passed
     */
    private RunResult<O> runWithCopy(RuleSet rules, RuleSet.Copy copy, RunFacts facts, RuntimeException rejected,
                                     RunBody<O> body) {
        EngineRunContext run = newRun(rules, facts);
        // Read before the scope is opened, and the scope opened inside the try, so it's closed even if opening it
        // fails part way, as it can when it runs out of stack.
        Deadline outerDeadline = Cancellation.current();
        try {
            enterRun(run, facts.deadline());
            RunResult<O> result;
            try {
                // Inside the try, so a fatal Error from a listener's beforeRun still closes every listener's run.
                notifyRun("beforeRun", listener -> listener.beforeRun(run));
                if (rejected != null) {
                    throw rejected;
                }
                // With the copy held, so no compiler of the rule list the run uses is closed while it checks a name.
                factIntake.checkFactNames(facts.values(), rules.factChecks());
                // Every run that returns passes here, a nested one too, so the result carries the run's tags and
                // start before afterRun or the caller sees it.
                result = body.run(rules, copy, facts).withRun(run);
            } catch (RuntimeException e) {
                notifyRunError(run, e, e, facts.tally());
                throw e;
            } catch (Error e) {
                // run() rethrows the error itself; listeners see what it failed with.
                notifyRunError(run, runFailure(e), e, facts.tally());
                throw e;
            } catch (Throwable t) {
                // A backstop: every place the run calls a rule, a listener, a language or the output reports a
                // Throwable that is neither an Exception nor an Error as a failure of its own, so none should get
                // here. One that does is handled the same way: it fails the run like an exception, closing every
                // listener's run, and a fatal Error among its causes, or suppressed on them, is then rethrown
                // unchanged.
                Failures.keepInterruptStatus(t);
                String msg = "The run failed with " + Failures.describeWithClass(t);
                log.error(msg);
                RuleExecutionException failure = new ReportedFailure(msg, t);
                Error fatal = Failures.fatalError(t);
                notifyRunError(run, failure, fatal != null ? fatal : failure, facts.tally());
                Failures.throwIfPresent(fatal);
                throw failure;
            }
            notifyRun("afterRun", listener -> listener.afterRun(run, result));
            return result;
        } finally {
            leaveRun(facts.parent(), outerDeadline);
        }
    }

    /**
     * Opens the scope a run's listeners hear of it in: {@code run} becomes the thread's current run, which a run
     * started from one of its callbacks takes as its parent, and {@code deadline} the one such a run inherits. A fatal
     * failure a rule of an earlier run left recorded is cleared, so a run started from {@code onRunError} of a run a
     * fatal {@link Error} left isn't told of that run's rule. {@link #runInScope} has set the run's deadline already;
     * it's set again here, and put back by {@link #leaveRun}, with the run's context. The caller reads the deadline to
     * put back with {@link Cancellation#current()} first, and calls this inside the {@code try} whose {@code finally}
     * calls {@link #leaveRun}, so a scope opened part way is closed too.
     *
     * @param run      The run that is starting
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     */
    private void enterRun(EngineRunContext run, Deadline deadline) {
        currentRun.set(run);
        Cancellation.enter(deadline);
        fatalFailure.remove();
    }

    /**
     * Closes the scope {@link #enterRun} opened: clears what a rule of the run recorded as its fatal failure, puts
     * back the deadline, and makes {@code parent} the thread's current run again. Each step is in a {@code finally} of
     * the one before, so one that fails, as one that runs out of stack does, doesn't keep the next from running.
     *
     * @param parent        The run this one was started from, or {@code null}
     * @param outerDeadline What {@link Cancellation#current()} returned before {@link #enterRun} was called
     */
    private void leaveRun(RunContext parent, Deadline outerDeadline) {
        try {
            fatalFailure.remove();
        } finally {
            try {
                Cancellation.leave(outerDeadline);
            } finally {
                if (parent == null) {
                    currentRun.remove();
                } else {
                    currentRun.set(parent);
                }
            }
        }
    }

    /**
     * Returns the exception {@code onRunError} gets for a fatal {@link Error} leaving a run: the one the rule it came
     * from was reported to {@code onError} with, which names the rule, or else one that names no rule. A rule's is
     * recorded just before its error is rethrown, and nothing between there and the run catches or replaces it.
     *
     * @param error The error the run is rethrowing
     * @return The exception for {@code onRunError}
     */
    private RuleExecutionException runFailure(Error error) {
        RuleExecutionException reported = fatalFailure.get();
        return reported != null
                ? reported
                : new RuleExecutionException("The run failed with " + Failures.describeWithClass(error), error);
    }

    /**
     * Closes a run that failed with {@code onRunError} on every listener. A stop is recorded on the tally first, so
     * a fatal error a listener throws in its place still leaves the run's event saying the run stopped, as the
     * listeners were told, and so is an interrupt that caused it, so the thread's interrupt status is set again
     * before the copy is given back and when {@code run()} returns (see {@link #keepInterruptOfStop}). A fatal error a
     * listener throws is rethrown in place of what the run failed with, and carries that as a suppressed exception
     * (see {@link Failures#keepAlso}).
     *
     * @param error   The exception listeners are told of
     * @param failing What the run throws if no listener throws a fatal error: {@code error}, or the fatal error it
     *                carries
     */
    private void notifyRunError(RunContext run, RuntimeException error, Throwable failing, RunTally tally) {
        if (ReportedFailure.isStop(error)) {
            tally.markStopped();
            if (error.getCause() instanceof InterruptedException) {
                tally.markInterrupted();
            }
        }
        notifyRun("onRunError", listener -> listener.onRunError(run, error), error, failing);
    }

    /** Creates the context one run is reported to listeners with. */
    private EngineRunContext newRun(RuleSet rules, RunFacts facts) {
        return new EngineRunContext(facts.runId(), facts.parent(), matchPolicy(), rules.checksum(),
                facts.forListeners(), facts.selection().tags(), facts.selection().startedAt());
    }

    /**
     * Calls one run callback on every listener, logging what a listener throws, like the rule callbacks. A fatal
     * {@link Error} a listener throws is rethrown once every listener has had the callback, and logged first unless a
     * run it started logged it already (see {@link LoggedFailures}), or else what it wrapped the error in, when that
     * says something of its own (see {@link #listenerFatalMessage}).
     */
    private void notifyRun(String callback, Consumer<RuleListener> call) {
        notifyRun(callback, call, null, null);
    }

    /**
     * Calls one run callback on every listener, as {@link #notifyRun(String, Consumer)} does, for a callback that
     * tells listeners of the run's failure. A fatal {@link Error} a listener throws carries what it's rethrown in
     * place of as a suppressed exception (see {@link Failures#keepAlso}). The failure the callback tells listeners of,
     * rethrown or wrapped, is the run's own, whatever run logged its fatal error, so nothing a listener wrapped around
     * it is taken for news about a nested run.
     *
     * @param told    The exception the callback tells listeners of, or {@code null}; see
     *                {@link #logListenerException}
     * @param failing What the run throws if no listener throws a fatal error, or {@code null}
     */
    private void notifyRun(String callback, Consumer<RuleListener> call, Throwable told, Throwable failing) {
        ListenerFatal thrown = listenerFatal(callback, call, null, told);
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
     * Returns which rules this engine fires, for {@link RunContext#matchPolicy()}.
     *
     * @return {@code "firstMatch"}, {@code "allMatches"} or {@code "uniqueMatch"}
     */
    abstract String matchPolicy();

    /**
     * Reports a run that failed for a reason that belongs to no rule and no listener callback is open for, such as
     * more than one rule matching on an engine that allows one. Logged at ERROR, as the engine logs every failure it
     * throws; the caller throws the exception from the run's body, so the run's listeners get {@code onRunError}.
     *
     * @param msg What failed
     * @return The exception to throw, which belongs to no rule
     */
    static RuleExecutionException failedRun(String msg) {
        log.error(msg);
        return new ReportedFailure(msg, null);
    }

    /**
     * Returns the rules this engine has loaded, their checksum and when they were loaded.
     *
     * @return The loaded rules, or an empty rule list with the checksum of no rules before the first
     *         {@link #load(List)}
     * @throws IllegalStateException if the engine is closed
     */
    @Override
    public RuleSetInfo rules() {
        RuleSet rules = ruleSet;
        if (rules == null) {
            if (closed) {
                throw new IllegalStateException(CLOSED_MESSAGE);
            }
            return RuleSetInfo.of(List.of(), Checksums.ofRules(List.of()), null);
        }
        return RuleSetInfo.of(rules.rules().stream().map(CompiledRule::rule).toList(), rules.checksum(),
                rules.loadedAt());
    }

    /**
     * Returns the rule set runs start with.
     *
     * @return The rule set
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
     */
    RuleSet currentRules() {
        RuleSet rules = ruleSet;
        if (rules == null) {
            throw new IllegalStateException(closed ? CLOSED_MESSAGE : "load() must be called before run()");
        }
        return rules;
    }

    /**
     * Sets how long the runs of the rule lists this engine loads from now on wait without one copy being given back
     * before they make an extra one. It is a deliberate test seam: a test whose runs wait for copies on purpose sets a
     * window longer than the test, so a stall of the test's own threads can't make a run give up and add a copy.
     * Call it before {@link #load(List)}, on the thread that loads.
     *
     * @param millis How long a run waits, in milliseconds
     */
    void stallWindow(long millis) {
        stallWindowMillis = millis;
    }

    /**
     * Borrows a copy of the rules for one run. An interrupt while waiting for one fails the run; the interrupt status
     * is set again, so the caller still sees it. A thread whose status is already set doesn't wait, and gets a free
     * copy: the run then stops at its first rule, the same way it does without a limit on copies.
     *
     * <p>
     * A run stopped while it waits is reported first, and only then leaves the rule set, so the stop reaches the
     * listeners and the log even when leaving closes a retired rule set that throws a fatal {@link Error}. That error
     * is thrown in place of the stop, which it carries as a suppressed exception.
     * </p>
     *
     * @param rules The rule set to borrow from
     * @param facts The run's facts, and what the run carries with them
     * @return The copy, to give back with {@link RuleSet#release(RuleSet.Copy)}
     * @throws RuleExecutionException if the thread is interrupted while it waits for a copy, or for a build slot to
     *                                make one, or if the run's deadline passes while it waits for a copy under a
     *                                limit
     */
    private RuleSet.Copy borrow(RuleSet rules, RunFacts facts) {
        try {
            return rules.borrow(facts.deadline());
        } catch (InterruptedException | TimeoutException e) {
            // Straight on, allocating nothing: the run is still counted on the rule set until it leaves there.
            throw stoppedThenLeft(rules, facts, e);
        }
    }

    /**
     * Reports a run that stopped waiting for a copy, as {@link #stoppedWaiting} does, and then leaves the rule set it
     * waited on, which {@link RuleSet#borrow(Deadline)} left the run counted on for this. The caller always throws what
     * this returns, or what it throws. Leaving closes the rule set only if it was retired and this run was its last
     * user; a fatal {@link Error} from that closing is thrown in place of the stop, carrying it as a suppressed
     * exception, unless a listener threw a fatal error while the stop was reported, which came first and is thrown
     * instead, carrying the one from closing. An interrupted run sets its thread's interrupt status again before it
     * leaves, and {@code run()} again when it returns (see {@link #keepInterruptOfStop}).
     *
     * @param facts The run's facts, and what the run carries with them
     * @param stop  What the wait stopped with: an {@link InterruptedException} or a {@link TimeoutException}
     * @return The stop, for the caller to throw
     */
    // Any Throwable: the run must leave however reporting the stop ends, as a finally would, and a failure that isn't
    // fatal is kept under a fatal Error from closing. Everything that allocates, the message too, is inside the try.
    private RuleExecutionException stoppedThenLeft(RuleSet rules, RunFacts facts, Exception stop) {
        RuleExecutionException failure;
        try {
            Deadline deadline = facts.deadline();
            boolean interrupted = stop instanceof InterruptedException;
            String msg;
            if (interrupted) {
                // Without a limit, the only wait is for a build slot, on a virtual thread.
                msg = "run() was interrupted while waiting " + (rules.limit() == RuleSet.UNLIMITED
                        ? "to make a compiled copy of the rules: every build slot was in use"
                        : "for a compiled copy of the rules: all " + rules.limit() + " were in use");
            } else {
                msg = "run() passed its deadline of " + deadline.instant() + " while waiting for a compiled copy of the"
                        + " rules:"
                        + " all " + rules.limit() + " were in use";
            }
            // The deadline passed, or none did when an interrupt stopped the run.
            failure = stoppedWaiting(rules, facts, msg, stop, interrupted ? null : deadline);
        } catch (Throwable t) {
            // The run leaves even if setting the interrupt status again fails, as it can when it runs out of stack.
            try {
                keepInterruptOfStop(facts.tally());
            } finally {
                Failures.throwIfPresent(Failures.fatalInsteadOf(t, rules.leaveAfterStop()));
            }
            throw t;
        }
        try {
            keepInterruptOfStop(facts.tally());
        } finally {
            Failures.throwIfPresent(Failures.fatalInsteadOf(failure, rules.leaveAfterStop()));
        }
        return failure;
    }

    /**
     * Stops a run that was interrupted, or passed its deadline, while reading the engine's rules again because the
     * rule set it read had been closed. Reported like a run that stopped waiting for a copy: it too stopped before
     * it got one, and for the same two reasons.
     *
     * @param rules The rule set the run found closed
     * @param facts The run's facts, and what the run carries with them
     * @throws RuleExecutionException if the thread is interrupted, or the run's deadline has passed
     */
    private void stopIfCancelledWhileReading(RuleSet rules, RunFacts facts) {
        Deadline deadline = facts.deadline();
        String reading = " while reading the engine's rules again: the rules this run read had been closed by a"
                + " reload or by close()";
        Cancellation.Reason reason = Cancellation.reason(deadline);
        if (reason == Cancellation.Reason.INTERRUPTED) {
            throw stoppedWaiting(rules, facts, "run() was interrupted" + reading, new InterruptedException(), null);
        }
        if (reason == Cancellation.Reason.TIMED_OUT) {
            throw stoppedWaiting(rules, facts, "run() passed its deadline of " + deadline.instant() + reading,
                    Cancellation.timedOut(deadline), deadline);
        }
    }

    /**
     * Reports a run that stopped before it got a copy of the rules, because it was interrupted or passed its deadline
     * while waiting. Logged at WARN, like the check between rules: a run the caller stopped isn't the rules or the
     * engine failing.
     *
     * @param rules  The rule set the run was waiting on
     * @param facts  The run's facts, and what the run carries with them
     * @param msg    What to log and what the exception says
     * @param cause  An {@link InterruptedException} or a {@link TimeoutException}
     * @param passed The deadline the run passed, or {@code null} if it was interrupted instead
     * @return The exception to throw
     */
    private RuleExecutionException stoppedWaiting(RuleSet rules, RunFacts facts, String msg, Exception cause,
                                                  Deadline passed) {
        RunTally tally = facts.tally();
        // Recorded before any listener is told, as a fatal error from beforeRun would keep the stop from reaching
        // onRunError, which records it for every other stop.
        if (cause instanceof InterruptedException) {
            tally.markInterrupted();
        }
        log.warn(msg);
        RuleExecutionException failure = ReportedFailure.stop(msg, cause, passed);
        // The run never got a copy, so it opens and closes a scope of its own for listeners. The scope still carries
        // the run's deadline and makes it the parent, so a run a listener starts here is treated like one started
        // from any other callback of a run that stopped.
        EngineRunContext run = newRun(rules, facts);
        Deadline outerDeadline = Cancellation.current();
        try {
            enterRun(run, facts.deadline());
            try {
                notifyRun("beforeRun", listener -> listener.beforeRun(run));
            } catch (Error e) {
                // The run stopped, though the error keeps the stop from reaching onRunError: its event says so.
                tally.markStopped();
                notifyRunError(run, runFailure(e), e, tally);
                throw e;
            }
            notifyRunError(run, failure, failure, tally);
            return failure;
        } finally {
            leaveRun(facts.parent(), outerDeadline);
        }
    }

    /**
     * Set the list of rules used for processing in the Rules Engine.
     * Rules are sorted by priority in descending order (highest priority first).
     * Rules with a {@code null} priority are treated as lowest priority.
     * Rules with equal priority keep their relative order from {@code ruleList}.
     *
     * <p>
     * Every condition and action is compiled in isolation. Variables, their types and inline
     * {@code import} statements in one expression don't affect any other rule, in this list or a later one.
     * </p>
     *
     * <p>
     * Classes in imported packages are looked up with the context class loader of the thread that calls this
     * method. {@code run()} checks fact names against the same class loader, whichever thread it runs on.
     * </p>
     *
     * <p>
     * Each rule is compiled by the expression language its {@link Rule#getLanguage() language} names, or by the
     * engine's default language if that is {@code null}. {@code run()} checks fact names against every language the
     * rules use, or against the default language if there are no rules.
     * </p>
     *
     * <p>
     * Every rule is compiled before a failure is thrown, so one {@link RuleCompilationException} reports everything
     * that failed: each broken rule, in priority order; a language that can't create its compiler, once, in place of
     * the first rule that needed it (the rules written in it aren't compiled, and get no failure of their own); and
     * each declared fact name the languages reject, last. Its {@code failures()} has each. Its message counts them,
     * lists the first whole, and each next one while the list stays within
     * {@value Failures#MAX_DESCRIPTION_LENGTH} characters, then counts the rest. A {@code null} rule or a duplicate
     * name is thrown at once, before anything is compiled.
     * </p>
     *
     * <p>
     * Every declared fact name is checked with the same languages a run checks names with, so a declared name no
     * language can refer to fails here rather than every run. When rules failed, the compilers that were created
     * still check the names; a language whose compiler couldn't be created doesn't, and when no compiler was created
     * the names aren't checked until the next {@code load()}.
     * </p>
     *
     * <p>
     * An engine built with {@link io.github.brantunger.unruly.api.RulesEngineBuilder#copiesAtLoad(int)
     * copiesAtLoad(n)} then makes {@code n} copies of the rules, on this thread, before swapping them in, so runs go on
     * using the rules loaded before until it returns. A language that fails to create or warm up a session for one
     * fails the load, and the rules loaded before stay loaded.
     * </p>
     *
     * <p>
     * The rule list it replaces is closed once no run is using it: its languages' sessions and compilers are closed. A
     * rule list that fails to load closes the compilers it created, and the sessions of any copies it made.
     * </p>
     *
     * <p>
     * A fatal {@link Error} a language throws while closing a session or a compiler is rethrown once everything being
     * closed has been closed: the first, if there are several, carrying the others as suppressed exceptions. A rule
     * list that fails to load throws it in place of its own failure, which the error carries as a suppressed
     * exception, or which is logged at WARN if the error can't carry one, as the JVM's own {@link OutOfMemoryError}
     * can't; unless that failure is itself a fatal error, which came first and is thrown instead, carrying the one
     * from closing. A reload throws it after swapping its rules in, when it closes the rule list they replaced: the
     * new rules stay loaded, and runs use them. Anything else that fails once the new rules are swapped in, as
     * retiring the rule list they replaced can when it runs out of stack, is logged at WARN rather than thrown, since
     * the load has succeeded, and that rule list is retired again by the next {@code load()} or {@code close()}, as is
     * a rule list that failed to load and couldn't be retired. A {@code load()} that swaps its rules in retires those
     * rule lists too, after the one it replaced, and throws a fatal {@link Error} closing any of them throws.
     * </p>
     *
     * @param ruleList The List of {@link Rule} objects to compile.
     * @throws RuleCompilationException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     * @throws IllegalStateException if the engine is closed; this is checked before anything in the list, so a closed
     *                               engine throws it even for a list that would fail to load
     */
    @Override
    public void load(List<Rule> ruleList) {
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
        if (closed) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
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
            loaded = new RuleSet(compilation.compiledRules(), compilation.usedCompilers(), copyLimit, copyPermits,
                    stallWindowMillis);
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
     * {@code load()} or {@code close()} to retire.
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
        Throwable failed = retireClaimed(claim);
        Error fatal = Failures.fatalError(failed);
        Failures.throwIfPresent(Failures.fatalInsteadOf(failure, fatal));
        if (fatal == null) {
            // The load's own failure goes with it, as it would under a fatal Error.
            Failures.keepAlso(failed, failure);
            rethrowUnchecked(failed);
        }
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

    // Throws what retiring threw, if anything, as it is, whatever its type: retire() declares nothing, so it is
    // unchecked, or a checked exception a language's code threw undeclared, which the caller would have had thrown as
    // it is too. Not Failures.throwIfPresent, which throws a fatal Error it's given.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrowUnchecked(Throwable failure) throws T {
        if (failure != null) {
            throw (T) failure;
        }
    }

    /**
     * Closes the engine. The rule list is closed once no run is using it: a run holding a copy finishes, and so does
     * one waiting for a copy, because {@link RuleSet#borrow(Deadline)} counts the run before it waits, and a rule list
     * with a run counted on it can't close. Their sessions are closed as each one returns, unless a run still waiting
     * for a copy of the same rules is there to take them: then the last run to leave closes the copies it kept. The
     * languages' compilers are closed after them, once this method has closed the idle copies too. Afterwards,
     * {@code run()} and {@link #load(List)} throw {@link IllegalStateException} — as does a run that had read the rules
     * but had not yet begun to borrow a copy when this method closed them, because it reads them again and finds a
     * closed engine. A {@code load()} that found the engine open before this method closed it isn't stopped. If it
     * fails, it throws what it would on an open engine, such as {@link RuleCompilationException}. If it succeeds,
     * either it swapped its rules in first, and this method retires them like any others, or it finds the engine
     * closed, retires its rules rather than swapping them in, and throws {@link IllegalStateException}. Closing it
     * again does nothing, unless retiring a rule list failed part way, as it can when it runs out of stack: then
     * closing it again finishes retiring it. So does a {@code close()} after a {@code load()} whose retiring of a
     * rule list failed part way, and it throws a fatal {@link Error} closing that rule list throws.
     *
     * <p>
     * A fatal {@link Error} a language throws while closing a session or a compiler is rethrown once every copy that
     * was idle when this method closed the rules, and the compilers if no run is using the rules by then (holding a
     * copy, waiting for one, or not yet returned), has been closed: the first, if there are several, carrying the
     * others as suppressed exceptions. The engine is closed all the same, and the rule list is retired, so closing it
     * again does nothing more than it would otherwise. A copy given back while this method is still taking the idle
     * copies, before it has marked the rules closed, counts as one of them: this method closes it too if no run is
     * using the rules by then, and the last run to leave does otherwise. A copy a run still holds is closed when the
     * run gives it back, or, when it's kept for a run still waiting for a copy, by that run or the last run to leave,
     * and a fatal error from that reaches the run that closes it, never this method. The compilers are closed once both
     * this method has closed the idle copies and the last run has left, by whichever finishes second, which gets their
     * fatal error: so when a run leaves while this method is still closing, this method closes the compilers and throws
     * their error.
     * </p>
     */
    // A closed engine has no rule set.
    @SuppressWarnings("PMD.NullAssignment")
    @Override
    public void close() {
        // Before the engine is marked closed, so a close() that overflows here leaves it open, to close again.
        StackHeadroom.check();
        // As in load(): a run a language's close() starts is nested in this one, so what it logged isn't logged again
        // (see LoggedFailures). Counted before the rules are taken from the engine, so failing to count it leaves the
        // engine as it was.
        LoggedFailures.enter();
        try {
            long claim;
            synchronized (lifecycle) {
                lastClaim++;
                claim = lastClaim;
                // The rules join the rules still to retire before the engine lets go of them, as in load(), and this
                // call claims them, with any left by earlier calls that no other has claimed.
                RuleSet detached = ruleSet;
                if (detached != null) {
                    detached.nextUnretired = unretired;
                    unretired = detached;
                }
                ruleSet = null;
                closed = true;
                claimToRetire(claim);
            }
            rethrowUnchecked(retireClaimed(claim));
        } finally {
            LoggedFailures.leave();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The rules are compiled by the same code as {@code load()}. The compilers created are closed before this
     * returns, and no session is ever made, so a language that fails only while a session is created or warmed up for
     * a copy of {@code copiesAtLoad(n)} fails {@code load()} alone and isn't reported here. With the default
     * {@code copiesAtLoad(0)} nothing is outside what it can see.
     * </p>
     */
    @Override
    public List<RuleCompilationException> validate(List<Rule> ruleList) {
        // Before the validation counts or compiles anything, as in run().
        StackHeadroom.check();
        // As in load(): a fatal Error a run a language starts here logged isn't logged again.
        LoggedFailures.enterLoad();
        try {
            return validateRules(ruleList);
        } finally {
            LoggedFailures.leave();
        }
    }

    /**
     * Checks the rules, as {@link #validate(List)} describes.
     *
     * @param ruleList The rules
     * @return Every problem found
     */
    // Any Throwable: the compilers must be closed however this ends, as a finally would, and a failure that isn't
    // fatal is kept under a fatal Error from closing.
    private List<RuleCompilationException> validateRules(List<Rule> ruleList) {
        Objects.requireNonNull(ruleList, "ruleList must not be null");
        if (closed) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        List<RuleCompilationException> problems = compiler.listProblems(ruleList, RuleListCompiler.Mode.VALIDATE);
        RuleListCompiler.Compilation compilation = compiler.compilation(RuleListCompiler.Mode.VALIDATE);
        try {
            compilation.compile(ruleList.stream().filter(Objects::nonNull).toList());
        } catch (Throwable t) {
            Failures.throwIfPresent(Failures.fatalInsteadOf(t, compilation.closeCompilers()));
            throw t;
        }
        Failures.throwIfPresent(compilation.closeCompilers());
        problems.addAll(compilation.foundFailures());
        return Collections.unmodifiableList(problems);
    }

    /**
     * What evaluating the conditions found: the rules that matched, in evaluation order, and every rule's outcome.
     *
     * @param matched     The rules whose condition was true, in evaluation order. The list is the engine's own and
     *                    isn't published, so it isn't copied.
     * @param evaluations One evaluation for every rule, in evaluation order; immutable, for the run's result
     */
    record Matches(List<CompiledRule> matched, List<RuleEvaluation> evaluations) {
    }

    /**
     * Evaluates the rules' conditions one after another, in list order, recording each rule's outcome, and keeps the
     * rules that matched. An engine that fires only the first match stops evaluating there: the rules after it are
     * recorded as not evaluated, so a broken condition among them can't fail a run that is already decided. A rule
     * the run skips is recorded as skipped wherever it is, and no listener hears about it.
     *
     * @param ruleList   The rules, in evaluation order
     * @param copy       The run's copy of the rules, whose sessions the conditions run with
     * @param facts      The run's facts and the views built over them
     * @param untilFirst Whether to stop evaluating at the first match
     * @return The matched rules and every rule's outcome
     * @throws RuleExecutionException if a condition fails, or the run was cancelled before one
     */
    Matches match(List<CompiledRule> ruleList, RuleSet.Copy copy, RunFacts facts, boolean untilFirst) {
        List<CompiledRule> matched = new ArrayList<>();
        List<RuleEvaluation> evaluations = new ArrayList<>(ruleList.size());
        for (CompiledRule rule : ruleList) {
            evaluations.add(evaluation(rule, copy, facts, matched, untilFirst));
        }
        // An immutable copy, which the result's own List.copyOf then keeps as it is rather than copying again.
        return new Matches(matched, List.copyOf(evaluations));
    }

    /**
     * Evaluates one rule's condition, unless the run skips the rule or is decided already, and adds the rule to
     * {@code matched} when the condition is true.
     *
     * @return The rule's outcome, with the detail its language explained the condition with, if it did
     */
    private RuleEvaluation evaluation(CompiledRule rule, RuleSet.Copy copy, RunFacts facts,
                                      List<CompiledRule> matched, boolean untilFirst) {
        // Before the first-match check, so a skipped rule reads as skipped wherever it is.
        if (facts.selection().skips(rule.rule())) {
            return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.SKIPPED);
        }
        if (untilFirst && !matched.isEmpty()) {
            return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.NOT_EVALUATED);
        }
        ConditionResult condition = matches(rule, copy, facts);
        if (!Boolean.TRUE.equals(condition.value())) {
            return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.NOT_MATCHED, condition.detail());
        }
        matched.add(rule);
        return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.MATCHED, condition.detail());
    }

    /**
     * Evaluates one rule's condition, telling the listeners about it.
     *
     * @param rule  The rule whose condition to evaluate
     * @param copy  The run's copy of the rules, whose sessions the condition runs with
     * @param facts The run's facts and the views built over them
     * @return What the language returned: a {@link Boolean} value, and the detail it explained it with, if any
     * @throws RuleExecutionException if the run was cancelled before this rule
     */
    ConditionResult matches(CompiledRule rule, RuleSet.Copy copy, RunFacts facts) {
        checkNotCancelled(rule, facts.deadline());
        return parseCondition(rule, copy, facts);
    }

    /**
     * Stops a run that must not go on to {@code rule}, because its thread has been interrupted or it has passed its
     * deadline. Checked before each condition and before each action, and again when each returns
     * ({@link #stopIfCancelled}), which is as often as the engine gets control back: an expression that doesn't return
     * can only be stopped by a language that can stop inside one, through
     * {@link io.github.brantunger.unruly.api.language.EvaluationContext#isCancelled()}.
     *
     * <p>
     * No listener is told about the rule, because nothing was started for it: the check runs before
     * {@code beforeEvaluate} and {@code beforeExecute}, so no callback is open to close with {@code onError}. The
     * run's own {@code onRunError} is called, like any other failure of a run.
     * </p>
     *
     * @param rule     The rule the run would go on to
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @throws RuleExecutionException if the run must stop
     */
    private void checkNotCancelled(CompiledRule rule, Deadline deadline) {
        ReportedFailure stop = cancellation(rule, BEFORE_RULE, deadline, null);
        if (stop != null) {
            throw stop;
        }
    }

    /**
     * Returns the exception a cancelled run stops with, or {@code null} if the run may go on. The message naming the
     * rule is built only when the run stops, because this is checked several times for every rule.
     *
     * @param rule     The rule the run stopped before or during
     * @param stage    {@link #BEFORE_RULE} or {@link #DURING_RULE}, where the run stopped
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @param thrown   What the expression threw, or {@code null}: when a run it started stopped for the same reason,
     *                 that run logged the stop, and it isn't logged again
     * @return The exception, or {@code null}
     */
    private ReportedFailure cancellation(CompiledRule rule, String stage, Deadline deadline, Throwable thrown) {
        // isInterrupted(), not interrupted(): the status stays set, so an executor shutting down still sees it.
        Cancellation.Reason reason = Cancellation.reason(deadline);
        if (reason == Cancellation.Reason.INTERRUPTED) {
            return cancelled("run() was interrupted " + stage + " rule '" + rule.displayName() + "'",
                    new InterruptedException(), null, thrown);
        }
        if (reason == Cancellation.Reason.TIMED_OUT) {
            return cancelled("run() passed its deadline of " + deadline.instant() + " " + stage + " rule '"
                    + rule.displayName() + "'", Cancellation.timedOut(deadline), deadline, thrown);
        }
        return null;
    }

    /**
     * Reports a condition or action that threw as its rule's failure.
     *
     * @param rule   The rule whose expression threw
     * @param kind   Whether the condition or the action threw
     * @param thrown What it threw
     * @return The exception to throw
     */
    private RuleExecutionException expressionFailure(CompiledRule rule, ExpressionKind kind, Throwable thrown) {
        String what = kind == ExpressionKind.CONDITION ? "Failed to evaluate condition" : "Failed to execute action";
        return failure(rule, kind, what + " for rule '" + rule.displayName() + "': "
                + Failures.describe(thrown), thrown);
    }

    /**
     * Decides what a condition or action that threw means, or the output writer setting a property an action
     * returned. When the run has been cancelled by then, the throw is
     * taken as the expression giving up, as a run started from inside it does when it stops at the deadline it
     * inherited, so the run stops the way a cancelled run always does: no rule name, logged at WARN, with an
     * {@link InterruptedException} or a {@link TimeoutException} as the cause and what the expression threw kept as a
     * suppressed exception. The rule's open callback is closed with {@code onError}. When the run hasn't been
     * cancelled, the rule failed; an {@link Error} in what the expression threw makes that the answer even when it
     * has, because the code being run broke rather than gave up. Either way a fatal {@link Error} in what it threw
     * is rethrown as for any failure.
     *
     * @param rule     The rule whose expression threw
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @param thrown   What the expression threw
     * @param failed   Reports the rule's failure, when the run wasn't cancelled or {@code thrown} has an
     *                 {@link Error} anywhere in its cause chain, or one suppressed on it at any depth (see
     *                 {@link Failures#errorInChain})
     * @return The exception to throw
     */
    private RuleExecutionException stoppedOrFailed(CompiledRule rule, Deadline deadline, Throwable thrown,
                                                   Supplier<RuleExecutionException> failed) {
        // An interrupt the expression caught and wrapped is put back first, so it counts as one here too, and an
        // Error inside what it threw is the rule's failure as it always is, cancelled or not.
        Failures.keepInterruptStatus(thrown);
        ReportedFailure stop = Failures.errorInChain(thrown) == null
                ? cancellation(rule, DURING_RULE, deadline, thrown) : null;
        if (stop == null) {
            return failed.get();
        }
        stop.addSuppressedByEngine(thrown);
        return closedWithStop(rule, stop);
    }

    /**
     * Stops a run that was cancelled while a condition or action ran, once that expression has returned, so a run
     * past its deadline or interrupted never returns a result, even when the expression was its last one.
     *
     * @param rule        The rule whose expression returned
     * @param kind        Whether the condition or the action returned
     * @param deadline    When the run must stop, {@link Deadline#NONE} if it has none
     * @param wrongResult Why what the expression returned would have failed the rule, or {@code null} if it wouldn't.
     *                    A stopped run keeps it as a suppressed exception, as it keeps what an expression threw.
     * @throws RuleExecutionException if the run was cancelled
     */
    private void stopIfCancelled(CompiledRule rule, ExpressionKind kind, Deadline deadline, String wrongResult) {
        ReportedFailure stop = cancellation(rule, DURING_RULE, deadline, null);
        if (stop != null) {
            if (wrongResult != null) {
                stop.addSuppressedByEngine(new RuleExecutionException(wrongResult, null, rule.rule().getRuleName(),
                        kind));
            }
            throw closedWithStop(rule, stop);
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
    private RuleExecutionException closedWithStop(CompiledRule rule, ReportedFailure stop) {
        ListenerFatal thrown = listenerFatal("onError", listener -> listener.onError(rule.rule(), stop), null, stop);
        if (thrown != null) {
            Error fatal = logWrappedFromOnError(thrown, rule);
            stop.addSuppressedByEngine(fatal);
            fatalFailure.set(stop);
            throw fatal;
        }
        return stop;
    }

    /**
     * Reports a run that stopped because it was cancelled. Logged at WARN, not ERROR: nothing failed, and the caller
     * asked for it, whether by interrupting the thread or by setting a timeout.
     *
     * @param msg      What to log and what the exception says
     * @param cause    An {@link InterruptedException} or a {@link TimeoutException}, so a caller can tell which
     *                 happened
     * @param deadline The deadline the run passed, or {@code null} if it was interrupted
     * @param thrown   What the expression threw, or {@code null}. A run it started that stopped for the same reason
     *                 has logged the stop already, so it isn't logged a second time.
     * @return The exception to throw, which belongs to no rule
     */
    private ReportedFailure cancelled(String msg, Exception cause, Deadline deadline, Throwable thrown) {
        if (!Failures.nestedRunStopped(thrown, deadline)) {
            log.warn(msg);
        }
        return ReportedFailure.stop(msg, cause, deadline);
    }

    /**
     * Execute a single {@link CompiledRule} object's action field against the input
     * data
     *
     * @param rule         The rule object to obtain the action expression to fire
     *                     the rule for
     * @param copy         The run's copy of the rules, whose session the action runs with
     * @param outputObject an empty output object to set output data into
     * @param facts        The run's facts and the views built over them
     * @return {@code outputObject}, which the action changed in place or whose properties were set from the action's
     *         result. An action can't replace it: the engine keeps its own reference, and a language such as MVEL
     *         fails the rule with a {@link RuleExecutionException} when an action assigns to {@code output}, except
     *         inside a {@code def} function, where the assignment creates a variable local to the function.
     * @throws RuleExecutionException if the run was cancelled before this rule
     */
    O executeRule(CompiledRule rule, RuleSet.Copy copy, O outputObject, RunFacts facts) {
        checkNotCancelled(rule, facts.deadline());
        return parseAction(rule, copy, outputObject, facts);
    }

    /**
     * Creates a fresh output object from the engine's factory. Without this, a factory that throws
     * escaped {@code run()} unwrapped, and one that returned {@code null} surfaced later as an action
     * failure blamed on whichever rule ran first.
     *
     * @return The new output object, never {@code null}
     * @throws RuleExecutionException if the factory throws or returns {@code null}. A {@link VirtualMachineError}
     *                                other than {@link StackOverflowError} is logged, then rethrown unchanged, also
     *                                when it is the cause of what the factory throws or suppressed on it; any other
     *                                {@link Error}, and a {@link Throwable} that is neither an exception nor an error,
     *                                is wrapped like an exception. A failure of a {@code run()} the factory started
     *                                reads {@code Output factory threw: a nested run() failed: } and the innermost
     *                                failure that run logged, and one of a {@code load()} it started
     *                                {@code Output factory threw: a nested load() failed: } and that load's failure;
     *                                neither is logged again, as that run or load logged it; nor is a fatal error that
     *                                run logged (see {@link LoggedFailures}). A nested failure wrapped by the factory
     *                                in an exception with a message of its own reads {@code Output factory threw: },
     *                                that message and the nested failure as a note (see {@link Failures#describe}), and
     *                                is logged; a fatal error wrapped so is still rethrown.
     */
    O createOutput() {
        O output;
        try {
            output = outputFactory.get();
        } catch (Throwable e) {
            Failures.keepInterruptStatus(e);
            // Described before reportCalledCodeFailure() asks unlogged(), which records a fatal error it's told of
            // for the first time.
            String msg = Failures.lineOr(() -> Failures.below(e).logged() != null || Failures.wrapsLoggedFatal(e)
                    ? "Output factory threw: " + Failures.describe(e)
                    : "Output factory threw " + Failures.describeWithClass(e),
                    "Output factory threw " + e.getClass().getName());
            reportCalledCodeFailure(msg, e);
            throw new ReportedFailure(msg, e);
        }
        if (output == null) {
            String msg = "Output factory returned null. It must return a new output object on every call.";
            log.error(msg);
            throw new ReportedFailure(msg, null);
        }
        return output;
    }

    /**
     * Logs the failure of code the engine calls but doesn't own, such as a language's session or the output factory,
     * unless a nested run or load already logged it (see {@link LoggedFailures}), and rethrows a fatal {@link Error}
     * found in its cause chain, or suppressed on it, unchanged (see {@link Failures#fatalError}). The caller keeps the
     * interrupt status first (see {@link Failures#keepInterruptStatus}), then builds the message, as describing the
     * failure reads what was logged before this records it, and throws a {@link ReportedFailure} with that message
     * itself once this returns.
     *
     * @param msg What failed
     * @param e   What the called code threw
     */
    static void reportCalledCodeFailure(String msg, Throwable e) {
        if (LoggedFailures.unlogged(e)) {
            log.error(msg);
        }
        Failures.throwIfPresent(Failures.fatalError(e));
    }

    private ConditionResult parseCondition(CompiledRule rule, RuleSet.Copy copy, RunFacts facts) {
        RuleEvent event = FlightRecorderEvents.startRule();
        String outcome = RuleEvent.FAILED;
        facts.tally().countEvaluated();
        try {
            ConditionResult result = evaluateCondition(rule, copy, facts);
            outcome = Boolean.TRUE.equals(result.value()) ? RuleEvent.MATCHED : RuleEvent.NOT_MATCHED;
            return result;
        } catch (RuleExecutionException e) {
            outcome = ruleOutcome(e);
            throw e;
        } catch (Error e) {
            outcome = fatalOutcome();
            throw e;
        } finally {
            if (event != null) {
                event.commit(engineId, facts.runId(), rule, ExpressionKind.CONDITION, outcome);
            }
        }
    }

    private ConditionResult evaluateCondition(CompiledRule rule, RuleSet.Copy copy, RunFacts facts) {
        // The run's evaluation context has its own read-only view, whose messages are about conditions, so a
        // listener that writes to the facts isn't told about conditions.
        Map<String, Object> listenerFacts = facts.forListeners();
        notifyBefore(rule, "beforeEvaluate", listener -> listener.beforeEvaluate(rule.rule(), listenerFacts));

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing. Always with
        // evaluateWithDetail, never evaluate, or the detail of a language that explains its conditions is lost.
        ConditionResult condition;
        try {
            condition = rule.compiledCondition().evaluateWithDetail(facts.evaluation(),
                    copy.sessions().get(rule.language()));
        } catch (Throwable t) {
            throw stoppedOrFailed(rule, facts.deadline(), t,
                    () -> expressionFailure(rule, ExpressionKind.CONDITION, t));
        }
        // Unboxing a null here would surface as an internal NPE naming MVEL's own
        // signature, which tells the caller nothing about their rule. So would reading a null result.
        Object evaluated = condition == null ? null : condition.value();
        String wrongResult = evaluated instanceof Boolean ? null : "Condition for rule '" + rule.displayName()
                + (condition == null ? "' returned no result from evaluateWithDetail" : "' evaluated to "
                + (evaluated == null ? "null" : "a " + evaluated.getClass().getName()))
                + ". A condition expression must evaluate to a boolean.";
        stopIfCancelled(rule, ExpressionKind.CONDITION, facts.deadline(), wrongResult);
        if (!(evaluated instanceof Boolean result)) {
            throw failure(rule, ExpressionKind.CONDITION, wrongResult, null);
        }

        notifyAfter(rule, "afterEvaluate", listener -> listener.afterEvaluate(rule.rule(), listenerFacts, result));

        return condition;
    }

    private O parseAction(CompiledRule rule, RuleSet.Copy copy, O outputResult, RunFacts facts) {
        RuleEvent event = FlightRecorderEvents.startRule();
        String outcome = RuleEvent.FAILED;
        try {
            O output = executeAction(rule, copy, outputResult, facts);
            facts.tally().countFired();
            outcome = RuleEvent.FIRED;
            return output;
        } catch (RuleExecutionException e) {
            outcome = ruleOutcome(e);
            throw e;
        } catch (Error e) {
            outcome = fatalOutcome();
            throw e;
        } finally {
            if (event != null) {
                event.commit(engineId, facts.runId(), rule, ExpressionKind.ACTION, outcome);
            }
        }
    }

    /**
     * Classifies what a condition or action threw, here rather than in {@link RuleEvent}, so a rule that fails doesn't
     * load the event class where {@link FlightRecorderEvents#USABLE} found it can't be.
     *
     * @param thrown The exception the rule's evaluation ends with
     * @return {@link RuleEvent#STOPPED} for a run that was interrupted or passed its deadline, otherwise
     *         {@link RuleEvent#FAILED}
     */
    private static String ruleOutcome(RuleExecutionException thrown) {
        return ReportedFailure.isStop(thrown) ? RuleEvent.STOPPED : RuleEvent.FAILED;
    }

    /**
     * Classifies a rule a fatal {@link Error} leaves: the run stopped in it when a listener's {@code onError} threw
     * the error while closing a stop, which {@link #closedWithStop} recorded for {@code onRunError}; otherwise the
     * rule failed.
     *
     * @return {@link RuleEvent#STOPPED} or {@link RuleEvent#FAILED}
     */
    private String fatalOutcome() {
        return ReportedFailure.isStop(fatalFailure.get()) ? RuleEvent.STOPPED : RuleEvent.FAILED;
    }

    private O executeAction(CompiledRule rule, RuleSet.Copy copy, O outputResult, RunFacts facts) {
        notifyBefore(rule, "beforeExecute", listener -> listener.beforeExecute(rule.rule(), outputResult));

        // The context gives the action a read-only view: an action changes the output object, never the facts other
        // rules see.
        // Not shared like the evaluation context: a first-match engine builds a fresh output object per rule. The
        // run's values are shared, so an action finds what the run's conditions kept.
        ActionContext context = new EngineActionContext(facts.values(), outputResult, facts.deadline(),
                facts.evaluation().runScope());
        ActionResult result;
        try {
            result = rule.compiledAction().execute(context, copy.sessions().get(rule.language()));
        } catch (Throwable t) {
            throw stoppedOrFailed(rule, facts.deadline(), t, () -> expressionFailure(rule, ExpressionKind.ACTION, t));
        }
        String wrongResult = result != null ? null : "Action for rule '" + rule.displayName()
                + "' returned no result. An action returns ActionResult.done() or ActionResult.set(...).";
        // Before the properties it returned are set: a run past its deadline changes the output no further.
        stopIfCancelled(rule, ExpressionKind.ACTION, facts.deadline(), wrongResult);
        if (result == null) {
            throw failure(rule, ExpressionKind.ACTION, wrongResult, null);
        }
        for (Map.Entry<String, Object> property : result.properties().entrySet()) {
            setProperty(rule, outputResult, property.getKey(), property.getValue(), facts.deadline());
        }
        // After them too: a writer that took the run past its deadline stops it, though what it set stays set.
        stopIfCancelled(rule, ExpressionKind.ACTION, facts.deadline(), null);

        notifyAfter(rule, "afterExecute", listener -> listener.afterExecute(rule.rule(), outputResult));

        return outputResult;
    }

    /**
     * Sets one property an action returned on the output object. A failure fails the rule, or stops a cancelled run,
     * as a failing action does (see {@link #stoppedOrFailed}).
     *
     * @throws RuleExecutionException if the property can't be set, or the run was cancelled while it was being set
     */
    private void setProperty(CompiledRule rule, O output, String property, Object value, Deadline deadline) {
        try {
            outputWriter.set(output, property, value);
        } catch (InvocationTargetException e) {
            // A writer of its own may throw one with no cause, or one of its own whose getCause() throws.
            Throwable cause = Failures.causeOf(e);
            Throwable thrown = cause != null ? cause : e;
            throw stoppedOrFailed(rule, deadline, thrown, () -> propertyFailure(rule, property, thrown));
        } catch (Throwable e) {
            throw stoppedOrFailed(rule, deadline, e, () -> propertyFailure(rule, property, e));
        }
    }

    private RuleExecutionException propertyFailure(CompiledRule rule, String property, Throwable cause) {
        return failure(rule, ExpressionKind.ACTION, "Failed to set '" + Failures.quote(property)
                + "' on the output for rule '" + rule.displayName() + "': " + Failures.describe(cause), cause);
    }

    /**
     * Calls a {@code before*} callback on every listener. If one throws a fatal {@link Error}, the condition or action
     * doesn't run: every listener gets {@link RuleListener#onError} to close the callback it received, and the error
     * is rethrown. It's logged first, unless a run the listener started logged it already (see
     * {@link LoggedFailures}), or else what the listener wrapped it in, when that says something of its own (see
     * {@link #listenerFatalMessage}).
     */
    private void notifyBefore(CompiledRule rule, String callback, Consumer<RuleListener> call) {
        ListenerFatal thrown = listenerFatal(callback, call);
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
     * listener already closed its callback, so none gets {@code onError}.
     */
    private void notifyAfter(CompiledRule rule, String callback, Consumer<RuleListener> call) {
        ListenerFatal thrown = listenerFatal(callback, call);
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
    private static Error logWrappedFromOnError(ListenerFatal thrown, CompiledRule rule) {
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
        return wrapped ? Failures.lineOr(() -> plain + ": " + Failures.describe(thrown.thrown()), plain) : plain;
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
    private record ListenerFatal(Error fatal, Throwable thrown, boolean ofTold) {
    }

    /**
     * Calls every listener, logging what a listener throws so a faulty listener can't interrupt a run. A fatal
     * {@link Error} (see {@link Failures#fatalError}), thrown or found among the causes of what a listener throws, or
     * suppressed on them, doesn't stop the other listeners either, so each still gets the callback, and closes whatever
     * it opened; the error is returned for the caller to rethrow, with what the listener threw it in. A second fatal
     * error in the same callback is logged like an exception, and kept on the first as a suppressed exception (see
     * {@link Failures#keepAlso}).
     *
     * @return The first fatal {@link Error} a listener threw, and what it threw it in, or {@code null}
     */
    private ListenerFatal listenerFatal(String callback, Consumer<RuleListener> call) {
        return listenerFatal(callback, call, null, null);
    }

    /**
     * Calls every listener, as {@link #listenerFatal(String, Consumer)} does, ignoring what the run already
     * reports: a listener that rethrows the reported exception, or the fatal {@link Error} in it, has added nothing,
     * so it doesn't count as the first fatal error, whichever listener rethrows it. What a listener wrapped it in is
     * still logged, because its own message says something.
     *
     * @param reported The exception listeners were told about, whose fatal {@link Error} the run is already
     *                 reporting, or {@code null}
     * @param told     The exception the callback tells listeners of, or {@code null}; see
     *                 {@link #logListenerException}
     * @return The first fatal {@link Error} a listener threw that the run isn't reporting, and what it threw it in, or
     *         {@code null}
     */
    // Rethrowing the very same instance is what makes it nothing new; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private ListenerFatal listenerFatal(String callback, Consumer<RuleListener> call, RuleExecutionException reported,
                                        Throwable told) {
        Error reportedFatal = reported == null ? null : Failures.fatalError(reported);
        Error fatal = null;
        Throwable fatalThrown = null;
        for (RuleListener listener : listeners) {
            try {
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
                } else {
                    // A non-fatal exception, or a second fatal error in this callback, which the caller can't rethrow
                    // but finds on the first.
                    logListenerException(callback, e, told);
                    Failures.keepAlso(fatal, found);
                }
            }
        }
        return fatal == null ? null : new ListenerFatal(fatal, fatalThrown, fatal == Failures.fatalError(told));
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
    private static void logListenerException(String callback, Throwable thrown, Throwable told) {
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
        logStackTrace(() -> log.debug("Listener threw exception in {}", callback, thrown));
    }

    /**
     * Makes a log call that hands the logging backend a throwable the engine didn't create, to print its stack trace.
     * Printing it calls the {@code toString()} of the throwable, of each of its causes and of each suppressed
     * exception, and one of a listener's own can throw; the stack trace is then left out, whatever was thrown, a fatal
     * {@link Error} too, rather than let that end the failure handling the call is part of (see
     * {@link Failures#messageOf}).
     *
     * @param logCall The log call
     */
    static void logStackTrace(Runnable logCall) {
        try {
            logCall.run();
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
    private static void keepSecondFatal(RuleExecutionException reported, Error rethrown, Error fromListener) {
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
            logStackTrace(() -> log.debug("Listener threw exception in onError", fromListener));
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
     * An interrupt in {@code cause} sets the thread's interrupt status again. Returns the exception for the caller to
     * throw, unless
     * the cause is or wraps a fatal {@link Error}, which is rethrown unchanged once listeners have been told, or a
     * listener threw a fatal error from {@code onError}, which is rethrown once every listener has been told.
     */
    private RuleExecutionException failure(CompiledRule rule, ExpressionKind kind, String msg, Throwable cause) {
        ReportedFailure error = new ReportedFailure(msg, cause, rule.rule().getRuleName(), kind);
        Failures.keepInterruptStatus(cause);
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
        ListenerFatal thrown = listenerFatal("onError", listener -> listener.onError(rule.rule(), error), error, error);
        return thrown == null ? null : logWrappedFromOnError(thrown, rule);
    }
}
