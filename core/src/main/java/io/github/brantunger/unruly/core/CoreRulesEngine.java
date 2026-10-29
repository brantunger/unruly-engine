package io.github.brantunger.unruly.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RuleSetInfo;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.internal.Causes;
import io.github.brantunger.unruly.internal.IsoInstant;
import io.github.brantunger.unruly.internal.Text;

/**
 * The engine, whatever its match policy: loading rules, which {@link RuleListCompiler} compiles and
 * {@link RuleSetLifecycle} swaps in and retires, borrowing a compiled copy for each run, evaluating conditions and
 * running actions, which {@link RuleExecutor} does, cancellation and failure reporting, and listener callbacks and the
 * reporting of rule and run failures to listeners, which {@link ListenerNotifier} does. The {@link MatchPolicy} it is
 * built with decides whether {@link #runRules(FactStore, Duration, Set)} stops evaluating at the first match, and
 * which of the rules that matched {@link #fire(RuleExecutor.Matches, CopyPool, CopyPool.Copy, RunFacts)} fires.
 *
 * <p>
 * The engine changes no global setting of any language: a session is used by one run at a time, so a language keeps
 * whatever changes while its expressions run there; see {@link CopyPool}.
 * </p>
 *
 * @param <O> The output object to instantiate
 */
final class CoreRulesEngine<O> implements RulesEngine<O> {

    // A fixed name, documented for logging configuration, so it doesn't change when this internal class does.
    static final String LOGGER_NAME = "io.github.brantunger.unruly.engine";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    // How many rules may match in one run of a unique-match engine: the policy's whole point.
    private static final int ALLOWED_MATCHES = 1;
    // What a run throws when no rules are loaded, or the engine is closed.
    private static final String NOT_LOADED = "load() must be called before run()";

    // How many times in a row one run may find the same rule set closed: its first reading of it, and one more for
    // each time it reads the engine's rules again and gets back the very set it found closed. Reading again settles
    // the one race that can close a set a run has read — a reload, or close(), retires it in between the run's reading
    // and its borrow — and a reading that finds a different set shows a reload got through, so the count starts again:
    // a loader reloading fast can overtake one run any number of times. The same closed set read again means the
    // engine's current set is closed, so the bound is far above one. Past it, the invariant a run relies on has
    // broken, and a run that spun instead would leave no evidence.
    private static final int RULE_READS_PER_RUN = 64;
    // Which rules a run fires once it has evaluated their conditions.
    private final MatchPolicy policy;
    // What a test puts in place of the engine's reading of its rules or of its runs' body, or null, as from Engines.
    private final TestSeam<O> seam;
    // Compiles the rule lists load() and validate() are given, with the engine's languages, imports and options.
    private final RuleListCompiler compiler;
    // Calls the listeners, and reports the failures of rules and runs to them.
    private final ListenerNotifier notifier;
    // Holds the rule set runs use: swaps in each one load() makes, and retires the ones the engine no longer uses.
    private final RuleSetLifecycle ruleSets;
    // How long a run may take, or null if runs have no deadline. A run() call can pass one of its own.
    private final Duration runTimeout;
    // The clock a run reads when it starts, to decide which rules are within their validity window.
    private final Clock clock;
    // Evaluates the conditions of a run's rules and runs their actions, for the match policy to fire them.
    final RuleExecutor<O> executor;
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

    /**
     * Creates an engine with the builder's settings, which {@link EngineSettings#resolve(EngineConfiguration)}
     * checks first: it takes or finds the languages and picks the default one, and resolves the imports with the
     * building thread's context class loader. A limit on copies means a run that finds all of them in use waits for
     * one; see {@link CopyPool}.
     *
     * @param policy        Which rules a run fires once it has evaluated their conditions
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once per
     *                      run that fires a rule and must return a new, non-null object each time.
     * @param configuration The builder's settings
     * @param seam          What a test puts in place of the engine's reading of its rules or of its runs' body, or
     *                      {@code null}, as {@link Engines} passes, for the engine's own
     * @throws IllegalStateException    if the languages or the default language can't be resolved, a language's
     *                                  {@link ExpressionLanguage#reservedFactNames()} returns {@code null} or a set
     *                                  holding {@code null}, or options or imports are given for a language the
     *                                  engine doesn't have, as
     *                                  {@link io.github.brantunger.unruly.api.RulesEngineBuilder#build()} describes
     * @throws IllegalArgumentException if a fact is declared with a blank name, or a name one of the engine's
     *                                  languages reserves for every rule list (see
     *                                  {@link ExpressionLanguage#reservesForEveryRuleList()}); if a language's own
     *                                  import has more than 1,000 characters; if an import has more than 1,000
     *                                  characters or more than 64 dot-separated parts, or has a dot-separated part
     *                                  that isn't a Java identifier ({@code '...' is neither a class nor a valid
     *                                  package name}, with no cause), all checked before any class lookup, so even a
     *                                  class of that name fails; or if it names
     *                                  a class that exists but can't be loaded, with the linkage error's text cut to
     *                                  at most 1,000 characters, with a note of how many were left out, then escaped,
     *                                  and a root cause it hides named
     * @throws NullPointerException     if {@code outputFactory} is {@code null}, checked after the settings
     * @throws StackOverflowError       if this is the JVM's first engine and the thread has too little stack left to
     *                                  initialize the classes engines use, before any of them is touched (see
     *                                  {@link RunClasses}): {@link Engines} checks that before it creates the engine,
     *                                  and before the builder resolves its settings, and this checks it last for a
     *                                  caller that doesn't; or if a language the engine is built to use (see
     *                                  {@link EngineSettings#languagesInUse()}) is of a class no engine has prepared
     *                                  yet and the thread has too little stack left to prepare it, checked last,
     *                                  before any language is prepared
     */
    CoreRulesEngine(MatchPolicy policy, Supplier<O> outputFactory, EngineConfiguration<O> configuration,
                    TestSeam<O> seam) {
        this.policy = policy;
        this.seam = seam;
        EngineSettings settings = EngineSettings.resolve(configuration);
        this.notifier = new ListenerNotifier(log, configuration.listeners());
        this.runTimeout = configuration.runTimeout();
        this.clock = settings.clock();
        this.factIntake = new FactIntake(log, settings);
        this.compiler = new RuleListCompiler(log, settings);
        this.ruleSets = new RuleSetLifecycle(log, compiler, settings);
        this.executor = new RuleExecutor<>(log, notifier, engineId, configuration.outputWriter(),
                Objects.requireNonNull(outputFactory, "outputFactory must not be null"));
        // Last, so the settings are checked first: the classes engines use that have a static initializer, which a
        // load or a run, maybe deep in another run's stack, must not be the first to use (see RunClasses). Engines
        // has initialized them already, before the builder read its settings, unless the engine was created without
        // it. Then each language the engine is built to use initializes its own, before anything is compiled.
        RunClasses.initialize();
        RunClasses.prepare(settings.languagesInUse());
    }

    /**
     * Returns the rules as {@link #load(List)} compiled them, or {@code null} if it has not been called or the
     * engine is closed. Every run shares them, each with its own sessions, from
     * {@link #runInScope(FactStore, Duration, Set, RuleExecutor.RunBody)}.
     *
     * @return An unmodifiable list of compiled rules, or {@code null}
     */
    // null, not an empty list: an empty rule list is loaded, and null means none is.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    List<CompiledRule> getCompiledRules() {
        CopyPool rules = ruleSets.current();
        return rules != null ? rules.loaded().rules() : null;
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
     * Fires the rules the way this engine's {@link MatchPolicy} fires them: evaluates the conditions in priority
     * order, stopping at the first match if {@link MatchPolicy#untilFirst} says to, and returns no output if no rule
     * matched; otherwise {@link #fire(RuleExecutor.Matches, CopyPool, CopyPool.Copy, RunFacts)} fires the matched
     * rules. A test's {@link TestSeam} may give the run a body of its own in place of this.
     *
     * @param facts   The facts the run was given
     * @param timeout How long the run may take, or {@code null} if it has no deadline
     * @param tags    The tags that choose the rules the run uses, or none to use rules whatever their tags
     * @return What the run did: its output is {@code null} if the rule list is empty or no rule matched
     */
    RunResult<O> runRules(FactStore<?> facts, Duration timeout, Set<String> tags) {
        RuleExecutor.RunBody<O> body = seam == null ? null : seam.body();
        return runInScope(facts, timeout, tags, body != null ? body : (ruleSet, copy, runFacts) -> {
            // Match the facts and data against the set of rules with the highest priority first.
            RuleExecutor.Matches matches = executor.match(ruleSet.loaded().rules(), copy, runFacts,
                    policy.untilFirst);
            if (matches.matched().isEmpty()) {
                return RunResult.of(null, List.of(), matches.evaluations(), ruleSet.loaded().checksum());
            }
            return fire(matches, ruleSet, copy, runFacts);
        });
    }

    /**
     * Fires the rules that matched, as the engine's {@link MatchPolicy} says, on a new output object. The run has
     * evaluated the conditions already, and calls this only when at least one rule matched. A first-match engine
     * fires the first, as its run stopped there. An all-matches engine fires every one, in priority order, highest
     * priority first; the actions share the output object, so a lower-priority action can overwrite a field set by a
     * higher-priority one. A unique-match engine fires the one rule that matched, and fails a run in which more than
     * one did before it creates the output object, so a failed run never calls the output factory.
     *
     * @param matches The rules that matched, at least one, and every rule's outcome
     * @param ruleSet The rule set the run uses
     * @param copy    The run's copy of the rules
     * @param facts   The run's facts and the views built over them
     * @return The output object the actions shaped, with the rules that fired
     * @throws RuleExecutionException if more than one rule matched on a unique-match engine: it names each of them,
     *                                in priority order, with the list cut at 1,000 characters like text copied from
     *                                an exception, and belongs to no rule; or if the output factory throws or returns
     *                                {@code null}, an action fails, or the run was cancelled before a rule. A fatal
     *                                {@link Error} (see {@link Failures#fatalError}) from the output factory is
     *                                rethrown unchanged, as {@link RuleExecutor#createOutput(RunFacts)} describes.
     */
    RunResult<O> fire(RuleExecutor.Matches matches, CopyPool ruleSet, CopyPool.Copy copy, RunFacts facts) {
        List<CompiledRule> matched = matches.matched();
        if (policy == MatchPolicy.UNIQUE_MATCH && matched.size() > ALLOWED_MATCHES) {
            // The list of names is cut like text copied from an exception: a table whose rows all match would
            // otherwise put every name in one log line. It's escaped after the cut, so the cut can't split an
            // escape. Read in a loop rather than with a stream and a lambda, so the JVM's first run that matches
            // more than one rule links no call site, which could overflow deep in a stack (#1093).
            List<String> names = new ArrayList<>(matched.size());
            for (CompiledRule rule : matched) {
                names.add(rule.rule().getRuleName());
            }
            throw failedRun(matched.size() + " rules matched, but a unique-match engine allows one: "
                    + Text.quoteJoined(names));
        }
        O output = executor.createOutput(facts);
        if (policy == MatchPolicy.ALL_MATCHES) {
            // Run the action of every rule on given data, saving state each time
            List<Rule> fired = new ArrayList<>();
            for (CompiledRule rule : matched) {
                output = executor.executeRule(rule, copy, output, facts);
                fired.add(rule.rule());
            }
            return RunResult.of(output, fired, matches.evaluations(), ruleSet.loaded().checksum());
        }
        // Run the action of the selected rule on given data and return the output: the first match, and on a
        // unique-match engine the only one.
        CompiledRule resolvedRule = matched.get(0);
        return RunResult.of(executor.executeRule(resolvedRule, copy, output, facts), List.of(resolvedRule.rule()),
                matches.evaluations(), ruleSet.loaded().checksum());
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
     * compiler is never closed while it checks one; so are the names a language reserves only for the rule lists that
     * use it rejected there, as only the rule list the run borrowed from says which languages it uses. A run that
     * waits for a copy opens its scope when the wait ends; an interrupt while waiting opens and closes a scope of its
     * own. Failing because no rules are loaded, or because the
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
    RunResult<O> runInScope(FactStore<?> facts, Duration timeout, Set<String> tags, RuleExecutor.RunBody<O> body) {
        Objects.requireNonNull(facts, "facts must not be null");
        // Misuse, before the run is numbered or recorded: it reaches no listener and no recording either.
        CopyPool rules = currentRules();
        // A first run of a language needs more room than run() checked for, as its rules' first calls may make the JDK
        // generate classes: checked before the run counts, borrows or sets anything, as there (see StackHeadroom). A
        // run that finds the rules it read closed, and borrows from a list a reload put in their place, isn't checked
        // again for that list, as by then it has counted itself and set its deadline: accepted, for that one run, in
        // the narrow window of a reload, which still records the languages it ran for that list.
        boolean firstRun = rules.loaded().languagesRan().needsRoom();
        if (firstRun) {
            Faults.at(Faults.Step.FIRST_RUN_ROOM_CHECKING);
            StackHeadroom.checkFirstRun();
        }
        long runId = runIds.incrementAndGet();
        RunContext parent = currentRun.get();
        long parentRunId = parent == null ? 0 : parent.runId();
        // The event spans the whole call: reading the facts, and waiting for a copy, which counts towards the
        // deadline too.
        RunEvent event = FlightRecorderEvents.startRun();
        RunTally tally = new RunTally();
        if (firstRun) {
            tally.recordLanguages();
        }
        String outcome = RunEvent.FAILED;
        // Published before the facts are read, so a run started from a fact's getValue(), from a language's
        // newSession() while the copy is made or from a session's close() while it's given back stops no later than
        // this one, as a run started from a rule or a listener does.
        Deadline deadline = Cancellation.deadlineFrom(timeout);
        // Read before the run's own deadline is set, and put back however the run ends: even when setting that
        // deadline fails once it has stored it, or counting the run fails, as an OutOfMemoryError can make it.
        Deadline enclosingDeadline = Cancellation.current();
        boolean counted = false;
        CopyPool.Copy copy = null;
        // What ending the run throws is kept in this, made before the run holds anything, so that ending a run that
        // has run out of memory allocates nothing until what it threw is combined (#855).
        Failures.Teardown teardown = new Failures.Teardown(4, Faults.Step.RUN_ENDING_COMBINED);
        try {
            Cancellation.enter(deadline);
            LoggedFailures.Runs runs = LoggedFailures.enter();
            counted = true;
            // Read once, so every rule's validity window is judged at the same time, however long the run takes.
            // A clock that returns null fails the run here, like one that throws, before any listener hears of it.
            runs.callOut();
            RuleSelection selection = new RuleSelection(
                    Objects.requireNonNull(clock.instant(), "the engine's clock returned a null instant"), tags);
            Map<String, Object> values = factIntake.factValues(facts, runs);
            Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(values);
            // Checked before the run waits for a copy, which facts the engine will reject needn't do. What the run
            // fails with in its scope, once its listeners have heard of it: what the check threw, or the stop of a run
            // that stopped before it got a copy.
            RuntimeException failure = factIntake.factRejection(values);
            RunFacts runFacts = RunFacts.of(values, listenerFacts, deadline, runId, parent, tally, selection, runs);
            // What a wait for a copy stopped with, the run still counted on the rule set it waited on.
            Exception waitStopped = null;
            if (failure == null) {
                // An interrupt while waiting for a copy, or the run's deadline passing under a limit, stops the run;
                // the interrupt status is set again, so the caller still sees it. A thread whose status is already set
                // doesn't wait, and gets a free copy: the run then stops at its first rule, the same way it does
                // without a limit on copies.
                try {
                    copy = rules.borrow(deadline);
                    int read = 1;
                    while (copy == null) {
                        // The rule set the run read was closed before it could borrow from it, which a reload does to
                        // the set it replaced, and close() to the set it detaches: reading again finds the set that
                        // replaced it, or reports the closed engine. A run the caller has stopped meanwhile stops here
                        // rather than reading again, and one that keeps finding the same set closed fails rather than
                        // spinning for ever. A different set starts the count again: a reload got through, however
                        // many overtake the run.
                        failure = stopIfCancelledWhileReading(runFacts);
                        if (failure != null) {
                            break;
                        }
                        if (read == RULE_READS_PER_RUN) {
                            throw new IllegalStateException("The engine's rule list was found closed "
                                    + RULE_READS_PER_RUN + " times in a row while this run was borrowing a copy of"
                                    + " it. A rule list is closed only after it has been retired, and only a rule list"
                                    + " that is no longer the engine's current one is retired, so the list a run reads"
                                    + " can never already be closed: that invariant has broken. Please report this"
                                    + " stack trace at https://github.com/brantunger/unruly-engine/issues");
                        }
                        CopyPool again = currentRules();
                        if (!firstRun && again.loaded().languagesRan().needsRoom()) {
                            firstRun = true;
                            tally.recordLanguages();
                        }
                        read = again == rules ? read + 1 : 1;
                        rules = again;
                        copy = rules.borrow(deadline);
                    }
                } catch (InterruptedException | TimeoutException e) {
                    // Straight on, allocating nothing: the run is still counted on the rule set until it leaves there,
                    // which it does only once it has reported the stop (see endRun), so the stop reaches the listeners
                    // and the log even when leaving closes a retired rule set that throws a fatal Error.
                    waitStopped = e;
                }
            }
            // The copy is given back however the run ends, even when setting it up fails: the engine's permits
            // outlive its rule lists, so a permit that isn't returned would lower its limit for good. Before that, once
            // every listener has been told how the run ended, the values its languages kept to be closed are closed.
            // A fatal Error from closing them, or the rules as the copy is given back, replaces a failure of the run
            // that isn't fatal, and never the other way round. A run whose facts were rejected, or that stopped before
            // it got a copy, holds none, and gives nothing back; one whose wait for a copy stopped leaves the rule set
            // it waited on instead.
            RunResult<O> result;
            try {
                // Reported inside the try, so the run leaves the rule set however reporting the stop ends.
                if (waitStopped != null) {
                    failure = waitStop(rules, runFacts, waitStopped);
                }
                result = runWithCopy(rules, copy, runFacts, failure, body);
            } catch (Throwable t) {
                // Given back even if setting the interrupt status again fails, as it can when it runs out of stack.
                try {
                    keepInterruptOfStop(tally);
                } finally {
                    Failures.rethrowUnchecked(
                            failedRunEnding(t, endRun(rules, copy, waitStopped != null, runFacts, teardown)));
                }
                throw t;
            }
            // Only a run that holds a copy gets here: one without fails in its scope.
            Failures.rethrowUnchecked(endRun(rules, copy, false, runFacts, teardown));
            // A run that ran an action of a language has made that language's first calls, so later runs of it need
            // no more room than run() checks for.
            List<String> ranLanguages = tally.firedLanguages();
            if (!ranLanguages.isEmpty()) {
                rules.loaded().languagesRan().completed(ranLanguages);
            }
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
                                event.commit(engineId, runId, parentRunId, policy.label, tally,
                                        rules.loaded().checksum(), outcome);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Ends a run once every listener has been told how it ended: closes the values its languages kept with
     * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScopedClosing}, then gives back its copy of
     * the rules, or, if its wait for one stopped, leaves the rule set it waited on, which
     * {@link CopyPool#borrow(Deadline)} left the run counted on so it could report the stop first. Leaving closes the
     * rule set only if it was retired and this run was its last user. It throws nothing: what closing the values or
     * giving back the copy throws, as logging what a {@code close()} threw can when it runs out of stack, is returned
     * for the run to throw, and the copy is given back, or the rule set left, whatever closing the values threw. The
     * values are handed over first, so none can be made once they're handed over: an init running on another thread
     * finishes first, and its value is handed over with them (see {@link RunScope#end()}). If handing them over fails,
     * as waiting for that init can when the stack or the heap runs out, nothing is handed over, so it's tried once
     * more, and only once: what that hands over is closed all the same, and what the first try threw is returned all
     * the same. Each step's failure is only stored where it's caught, which can't fail again, in a
     * {@link Failures.Teardown} the run made before it held anything; they are combined last, so nothing is allocated
     * here before that after a run that has run out of memory (#855).
     *
     * @param rules    The rule set the run borrowed its copy from, or waited on
     * @param copy     The run's copy, or {@code null} if its facts were rejected, or it stopped before it got one, and
     *                 it holds none
     * @param waited   Whether the run's wait for a copy stopped, so it leaves {@code rules} rather than give one back
     * @param facts    The run's facts, whose evaluation context holds the run's values
     * @param teardown Where each step's failure is kept, made for the four steps and not yet added to
     * @return What the run throws, or {@code null} if nothing is to be thrown: the first fatal {@link Error} closing
     *         the values or the rules threw, or else the first other throwable that escaped them, as logging one can,
     *         either carrying the rest as suppressed exceptions (see {@link Failures#fatalFirst}). Nothing else a
     *         {@code close()} throws is returned: it was logged. If combining them fails, as it can when it runs out
     *         of memory, the first fatal error met (see {@link Causes#isFatal}) is returned alone, or else what
     *         combining threw.
     */
    // Any Throwable: the copy must be given back however closing the values fails, as a finally would.
    private static Throwable endRun(CopyPool rules, CopyPool.Copy copy, boolean waited, RunFacts facts,
                                    Failures.Teardown teardown) {
        // The four steps, each adding what it threw: ending the scope, taking its values, closing them and giving
        // back the copy, or leaving the rule set.
        Throwable endFailed = null;
        Throwable taking = null;
        List<AutoCloseable> values = List.of();
        try {
            RunScope scope = facts.evaluation().run().scope();
            try {
                values = scope.end();
            } catch (Throwable t) {
                // It handed nothing over: once more, and what that throws is caught below, as it is the first time.
                endFailed = t;
                values = scope.end();
            }
            Faults.at(Faults.Step.RUN_VALUES_CLOSING);
        } catch (Throwable t) {
            // The values handed over are closed all the same.
            taking = t;
        }
        teardown.add(endFailed);
        teardown.add(taking);
        Throwable closing;
        try {
            closing = Closing.runValues(values);
        } catch (Throwable t) {
            closing = t;
        }
        teardown.add(closing);
        Throwable releasing = null;
        try {
            if (copy != null) {
                releasing = rules.release(copy);
            } else if (waited) {
                releasing = rules.leaveAfterStop();
            }
        } catch (Throwable t) {
            releasing = t;
        }
        teardown.add(releasing);
        return teardown.result();
    }

    /**
     * Chooses what a run that failed throws once it has ended: what it failed with, or what ending it threw (see
     * {@link #endRun}), as {@link Failures#insteadOf} decides. A fatal {@link Error} is never dropped for a failure
     * that isn't fatal: a fatal error from ending the run replaces a failure that isn't fatal, and a fatal failure of
     * the run comes before anything else ending it threw: the failure is thrown when the error is in its cause chain,
     * and the error itself, the failure logged at WARN, when it's only suppressed on it. Of two that aren't fatal,
     * what ending threw is thrown, as what giving back the copy threw was before the run's values were closed with
     * it. What is thrown carries the other as a suppressed exception (see {@link Failures#keepAlso}), except that a
     * fatal error only suppressed on the failure carries what ending threw, the failure itself being logged at WARN
     * instead.
     *
     * @param failure What the run failed with
     * @param ending  What {@link #endRun} returned, or {@code null}
     * @return What the run throws in place of {@code failure}, or {@code null} if it throws {@code failure}
     */
    private static Throwable failedRunEnding(Throwable failure, Throwable ending) {
        // endRun returns a fatal error itself, never one that carries it.
        return Failures.insteadOf(failure, ending);
    }

    /**
     * Sets the thread's interrupt status again if an interrupt stopped the run, which a listener told of the stop, or
     * a language's {@code close()}, may have cleared. Called before the run closes the values its languages kept for
     * it and gives back its copy or leaves the rules, so the closing that starts sees it set, and again as
     * {@code run()} returns, so the caller sees it. It isn't set again between one {@code close()} and the next: a
     * language whose {@code close()} clears it hides it from the values, sessions and compilers closed after it.
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
     * with what the check threw; so does a run that stopped before it got one, because it was interrupted or passed its
     * deadline while it waited for one or read the engine's rules again, with the stop the caller reported. The stop
     * still carries the run's deadline and makes the run the parent, so a run a listener starts then is treated like
     * one started from any other callback of a run that stopped.
     *
     * @param copy            The copy the run borrowed, or {@code null} if its facts were rejected, or it stopped
     * @param facts           The run's facts, and what the run carries with them
     * @param stopOrRejection What the engine's checks of the facts threw before the run borrowed a copy, or the stop
     *                        of a run that stopped before it got one, or {@code null} if the run holds a copy
     */
    private RunResult<O> runWithCopy(CopyPool rules, CopyPool.Copy copy, RunFacts facts,
                                     RuntimeException stopOrRejection, RuleExecutor.RunBody<O> body) {
        EngineRunContext run = newRun(rules, facts);
        // Read before the scope is opened, and the scope opened inside the try, so it's closed even if opening it
        // fails part way, as it can when it runs out of stack.
        Deadline outerDeadline = Cancellation.current();
        try {
            enterRun(run, facts.deadline());
            RunResult<O> result;
            try {
                // Inside the try, so a fatal Error from a listener's beforeRun still closes every listener's run.
                notifier.notifyRun(facts.runs(), "beforeRun", new BeforeRun(run));
                if (stopOrRejection != null) {
                    throw stopOrRejection;
                }
                // With the copy held, so no compiler of the rule list the run uses is closed while it checks a name;
                // and with the rule list the run borrowed from, whose languages reserve their names and check them,
                // not the one it first read, which a reload may have replaced.
                LoadedRules loaded = rules.loaded();
                factIntake.checkFactNames(facts.values(), loaded.factChecks(), loaded.factNamesRead(),
                        loaded.reservedFactNames(), facts.runs());
                // Every run that returns passes here, a nested one too, so the result carries the run's tags and
                // start before afterRun or the caller sees it.
                result = body.run(rules, copy, facts).withRun(run);
            } catch (RuntimeException e) {
                notifier.notifyRunError(facts.runs(), run, e, e, facts.tally());
                throw e;
            } catch (Error e) {
                // A run that stopped before it got a copy stopped, though an error from beforeRun keeps the stop from
                // reaching onRunError, which records it for every other stop: its event says so.
                if (ReportedFailure.isStop(stopOrRejection)) {
                    facts.tally().markStopped();
                }
                // run() rethrows the error itself; listeners see what it failed with.
                notifier.notifyRunError(facts.runs(), run, notifier.runFailure(e), e, facts.tally());
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
                RuleExecutionException failure = NestedFailures.report(msg, t);
                Error fatal = Failures.fatalError(t);
                notifier.notifyRunError(facts.runs(), run, failure, fatal != null ? fatal : failure, facts.tally());
                Failures.throwIfPresent(fatal);
                throw failure;
            }
            notifier.notifyRun(facts.runs(), "afterRun", listener -> listener.afterRun(run, result));
            return result;
        } finally {
            leaveRun(facts.parent(), outerDeadline);
        }
    }

    /**
     * Opens the scope a run's listeners hear of it in: {@code run} becomes the thread's current run, which a run
     * started from one of its callbacks takes as its parent, and {@code deadline} the one such a run inherits. A fatal
     * failure a rule of an earlier run left recorded is cleared, so a run started from {@code onRunError} of a run a
     * fatal {@link Error} left isn't told of that run's rule. {@link #runInScope} has set the run's deadline already,
     * before it read the facts; it's set again here, and put back by {@link #leaveRun}, with the run's context, for a
     * run started while the facts were read or the copy borrowed that failed to put this one's back as it ended, as
     * setting a thread-local can when the stack or the heap runs out, where the code that started it caught that error
     * and went on: this run's rules and listeners still pass its own deadline on. The caller reads the deadline to put
     * back with {@link Cancellation#current()} first, and calls this inside the {@code try} whose {@code finally} calls
     * {@link #leaveRun}, so a scope opened part way is closed too.
     *
     * @param run      The run that is starting
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     */
    private void enterRun(EngineRunContext run, Deadline deadline) {
        currentRun.set(run);
        // Again, in case a run started while the facts were read or the copy borrowed left its own deadline behind.
        Cancellation.enter(deadline);
        notifier.clearFatalFailure();
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
            notifier.clearFatalFailure();
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
     * Returns what a rule of a run on this thread recorded as its fatal failure and {@link #leaveRun} hasn't cleared
     * yet. For tests, which read it once a run has ended to see that the run left nothing of its own on the thread, as
     * {@code FatalErrorLogTest#ruleFailureNotKeptAfterTheRun} does.
     *
     * @return The recorded failure, or {@code null} if none is recorded
     */
    RuleExecutionException recordedFatalFailure() {
        return notifier.recordedFatalFailure();
    }

    /** Creates the context one run is reported to listeners with. */
    private EngineRunContext newRun(CopyPool rules, RunFacts facts) {
        return new EngineRunContext(facts.runId(), facts.parent(), policy.label, rules.loaded().checksum(),
                facts.forListeners(), facts.selection().tags(), facts.selection().startedAt());
    }

    /**
     * Returns which rules this engine fires, whose label {@link RunContext#matchPolicy()} reports. For tests, which
     * check that each of the builder's methods builds an engine of its policy.
     *
     * @return The policy the engine was built with
     */
    MatchPolicy matchPolicy() {
        return policy;
    }

    /**
     * Reports a run that failed for a reason that belongs to no rule and no listener callback is open for, such as
     * more than one rule matching on an engine that allows one. Logged at ERROR, as the engine logs every failure it
     * throws; the caller throws the exception from the run's body, so the run's listeners get {@code onRunError}.
     *
     * @param msg What failed
     * @return The exception to throw, which belongs to no rule
     */
    static RuleExecutionException failedRun(String msg) {
        Failures.logError(log, msg);
        return NestedFailures.report(msg, null);
    }

    /**
     * Returns the rules this engine has loaded, their checksum and when they were loaded. A call made while
     * {@link #close()} runs may still return them, even once {@link #validate(List)} and {@link #load(List)} throw.
     *
     * @return The loaded rules, or an empty rule list with the checksum of no rules before the first
     *         {@link #load(List)}
     * @throws IllegalStateException if the engine is closed
     */
    @Override
    public RuleSetInfo rules() {
        CopyPool rules = ruleSets.currentIfOpen();
        if (rules == null) {
            return RuleSetInfo.of(List.of(), Checksums.ofRules(List.of()), null);
        }
        LoadedRules loaded = rules.loaded();
        return RuleSetInfo.of(loaded.rules().stream().map(CompiledRule::rule).toList(), loaded.checksum(),
                loaded.loadedAt());
    }

    /**
     * Returns the rule set runs start with: the one {@link #loadedRules()} returns, unless a test's {@link TestSeam}
     * returns another.
     *
     * @return The rule set
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
     */
    CopyPool currentRules() {
        // Read here rather than through loadedRules(), so a run's reading is no frame deeper than it was.
        return seam == null ? ruleSets.currentOrThrow(NOT_LOADED) : seam.currentRules(this);
    }

    /**
     * Returns the rule set the engine has loaded, which {@link #currentRules()} returns unless a test's
     * {@link TestSeam} returns another.
     *
     * @return The rule set
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
     */
    private CopyPool loadedRules() {
        return ruleSets.currentOrThrow(NOT_LOADED);
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
        ruleSets.stallWindow(millis);
    }

    /**
     * Reports a run whose wait for a copy of the rules stopped, as {@link #reportStop} reports it: interrupted while it
     * waited for a copy, or for a build slot to make one, or past its deadline while it waited for a copy under a
     * limit. {@link CopyPool#borrow(Deadline)} left the run counted on the rule set it waited on, so it reports the
     * stop first and leaves there only once the stop has reached its listeners, as {@link #endRun} ends the run: the
     * stop reaches the listeners and the log even when leaving closes a retired rule set that throws a fatal
     * {@link Error}. Leaving closes the rule set only if it was retired and this run was its last user; a fatal error
     * from that closing is thrown in place of the stop, carrying it as a suppressed exception, unless a listener threw
     * a fatal error while the stop was reported, which came first and is thrown instead, carrying the one from closing.
     * An interrupted run sets its thread's interrupt status again before it leaves, and {@code run()} again when it
     * returns (see {@link #keepInterruptOfStop}).
     *
     * @param rules The rule set the run waited on
     * @param facts The run's facts, and what the run carries with them
     * @param stop  What the wait stopped with: an {@link InterruptedException} or a {@link TimeoutException}
     * @return The stop, for the run to fail with in its scope
     */
    private RuleExecutionException waitStop(CopyPool rules, RunFacts facts, Exception stop) {
        Deadline deadline = facts.deadline();
        boolean interrupted = stop instanceof InterruptedException;
        String msg;
        if (interrupted) {
            // Without a limit, the only wait is for a build slot, on a virtual thread.
            msg = "run() was interrupted while waiting " + (rules.limit() == CopyLimit.UNLIMITED
                    ? "to make a compiled copy of the rules: every build slot was in use"
                    : "for a compiled copy of the rules: all " + rules.limit() + " were in use");
        } else {
            msg = "run() passed its deadline of " + IsoInstant.text(deadline.instant())
                    + " while waiting for a compiled copy of the rules: all " + rules.limit() + " were in use";
        }
        // The deadline passed, or none did when an interrupt stopped the run.
        return reportStop(facts, msg, stop, interrupted ? null : deadline);
    }

    /**
     * Stops a run that was interrupted, or passed its deadline, while reading the engine's rules again because the
     * rule set it read had been closed. Reported like a run whose wait for a copy stopped (see {@link #waitStop}): it
     * too stopped before it got one, and for the same two reasons. It isn't counted on the rule set it found closed, so
     * it leaves nothing.
     *
     * @param facts The run's facts, and what the run carries with them
     * @return The stop, for the run to fail with in its scope, or {@code null} if the thread isn't interrupted and the
     *         run's deadline hasn't passed
     */
    private RuleExecutionException stopIfCancelledWhileReading(RunFacts facts) {
        Deadline deadline = facts.deadline();
        String reading = " while reading the engine's rules again: the rules this run read had been closed by a"
                + " reload or by close()";
        Cancellation.Reason reason = Cancellation.reason(deadline);
        if (reason == Cancellation.Reason.INTERRUPTED) {
            return reportStop(facts, "run() was interrupted" + reading, new InterruptedException(), null);
        }
        if (reason == Cancellation.Reason.TIMED_OUT) {
            return reportStop(facts, "run() passed its deadline of " + IsoInstant.text(deadline.instant()) + reading,
                    Cancellation.timedOut(deadline), deadline);
        }
        return null;
    }

    /**
     * Reports a run that stopped before it got a copy of the rules, because it was interrupted or passed its deadline
     * while waiting. Logged at WARN, like the check between rules: a run the caller stopped isn't the rules or the
     * engine failing. The run then fails with the stop in its scope (see {@link #runWithCopy}), which tells its
     * listeners.
     *
     * @param facts  The run's facts, and what the run carries with them
     * @param msg    What to log and what the exception says
     * @param cause  An {@link InterruptedException} or a {@link TimeoutException}
     * @param passed The deadline the run passed, or {@code null} if it was interrupted instead
     * @return The stop, for the run to fail with in its scope, where {@link #runWithCopy} throws it
     */
    private RuleExecutionException reportStop(RunFacts facts, String msg, Exception cause, Deadline passed) {
        // Recorded before any listener is told, as a fatal error from beforeRun would keep the stop from reaching
        // onRunError, which records it for every other stop.
        if (cause instanceof InterruptedException) {
            facts.tally().markInterrupted();
        }
        Failures.logWarn(log, msg);
        return NestedFailures.stop(msg, cause, passed);
    }

    /**
     * Calls {@link RuleListener#beforeRun} for a run, one that stopped before it got a copy too, which may be the JVM's
     * first failure. A class of its own rather than a lambda, so that run links no call site, and loaded when the JVM's
     * first engine is built (see RunClasses), so it loads no class either, either of which could overflow deep in a
     * stack (#1093).
     */
    static final class BeforeRun implements Consumer<RuleListener> {
        private final RunContext run;

        BeforeRun(RunContext run) {
            this.run = run;
        }

        @Override
        public void accept(RuleListener listener) {
            listener.beforeRun(run);
        }
    }

    /**
     * What a test puts in place of the engine's own reading of its rules, or of the body of its runs, to reach a state
     * an engine otherwise reaches only in a race, such as a run that reads a rule set a reload has just closed. A test
     * subclasses it and overrides what it replaces; what it doesn't override is the engine's own. An engine
     * {@link Engines} creates has none, and the engine only ever calls through one, so its code makes no lambda for
     * it.
     *
     * @param <O> The output object type of the engine
     */
    // Abstract with no abstract method: a test overrides only what it replaces, and a seam that replaces nothing is
    // no seam, so none is created but a subclass.
    @SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
    abstract static class TestSeam<O> {

        /**
         * Returns the rule set a run starts with, and reads again when it finds the one it read closed. By default
         * the one the engine has loaded.
         *
         * @param engine The engine that reads its rules
         * @return The rule set
         * @throws IllegalStateException if {@link CoreRulesEngine#load(List)} has not been called, or the engine is
         *                               closed
         */
        CopyPool currentRules(CoreRulesEngine<O> engine) {
            return engine.loadedRules();
        }

        /**
         * Returns what a run does once it holds a copy of the rules, in place of evaluating and firing them. By
         * default nothing, so the run does what the engine's {@link MatchPolicy} does.
         *
         * @return The run's body, or {@code null} for the engine's own
         */
        RuleExecutor.RunBody<O> body() {
            return null;
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
     * the first rule that needed it (the rules written in it aren't compiled, and get no failure of their own); a
     * compiler that fails to tell which facts its rules read; and each declared fact name the languages reject, or
     * reserve only for the rule lists that use them, last. Its {@code failures()} has each. Its message counts them,
     * lists the first whole, and each next one while the list stays within
     * {@value Text#MAX_DESCRIPTION_LENGTH} characters, then counts the rest. A {@code null} rule or a duplicate
     * name is thrown at once, before anything is compiled.
     * </p>
     *
     * <p>
     * Every declared fact name is checked with the same languages a run checks names with, so a declared name no
     * language can refer to fails here rather than every run; a compiler that says which facts its rules read checks
     * only those, and is asked only once every rule has compiled. When rules failed, the compilers that were created
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
        ruleSets.load(ruleList);
    }

    /**
     * Closes the engine. The rule list is closed once no run is using it: a run holding a copy finishes, and so does
     * one waiting for a copy, because {@link CopyPool#borrow(Deadline)} counts the run before it waits, and a rule list
     * with a run counted on it can't close. Their sessions are closed as each one returns, unless a run still waiting
     * for a copy of the same rules is there to take them: then the last run to leave closes the copies it kept. The
     * languages' compilers are closed after them, once this method has closed the idle copies too. Afterwards,
     * {@code run()} and {@link #load(List)} throw {@link IllegalStateException} — as does a run that had read the rules
     * but had not yet begun to borrow a copy when this method closed the engine, unless another run is still using the
     * rules when it borrows: then it uses them, as a run that read rules a reload replaced does, and its rules may run
     * after this method has returned. Otherwise it reads the rules again and finds a closed engine. A run whose thread
     * is interrupted, or whose deadline has passed, stops before reading them again, with a
     * {@link RuleExecutionException}, as one stopped while waiting for a copy does. A {@code load()} that found the
     * engine open before this method closed it isn't stopped. If it fails, it throws what it would on an open engine,
     * such as {@link RuleCompilationException}. If it succeeds,
     * either it swapped its rules in first, and this method retires them like any others, or it finds the engine
     * closed, retires its rules rather than swapping them in, and throws {@link IllegalStateException}. Closing it
     * again does nothing, unless retiring a rule list failed part way, as it can when it runs out of stack, or a
     * {@code close()} threw before retiring began, leaving the rule list detached: then closing it again finishes
     * retiring it. So does a {@code close()} after a {@code load()} whose retiring of a
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
    @Override
    public void close() {
        ruleSets.close();
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
        ruleSets.checkOpen();
        List<RuleCompilationException> problems = compiler.listProblems(ruleList, RuleListCompiler.Mode.VALIDATE);
        RuleListCompiler.Compilation compilation = compiler.compilation(RuleListCompiler.Mode.VALIDATE);
        try {
            compilation.compile(RuleListCompiler.withoutNulls(ruleList));
        } catch (Throwable t) {
            Failures.throwIfPresent(Failures.closingInsteadOf(t, compilation.closeCompilers()));
            throw t;
        }
        Failures.throwIfPresent(compilation.closeCompilers());
        problems.addAll(compilation.foundFailures());
        return Collections.unmodifiableList(problems);
    }
}
