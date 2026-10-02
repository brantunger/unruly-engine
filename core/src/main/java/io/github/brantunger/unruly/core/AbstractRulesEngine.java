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
 * What every engine shares, whatever its match policy: loading rules, which {@link RuleListCompiler} compiles and
 * {@link RuleSetLifecycle} swaps in and retires, borrowing a compiled copy for each run, evaluating conditions and
 * running actions, cancellation and failure reporting, and listener callbacks and the reporting of rule and run
 * failures to listeners, which {@link ListenerNotifier} does. A subclass supplies the match policy through
 * {@link #untilFirst()} and {@link #fire(Matches, RuleSet, RuleSet.Copy, RunFacts)}, which
 * {@link #runRules(FactStore, Duration, Set)} calls, and names it in {@link #matchPolicy()}. The two hooks are one
 * policy, as {@code fire} describes.
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
    // Calls the listeners, and reports the failures of rules and runs to them.
    private final ListenerNotifier notifier;
    // Holds the rule set runs use: swaps in each one load() makes, and retires the ones the engine no longer uses.
    private final RuleSetLifecycle ruleSets;
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

    /**
     * Creates an engine with the builder's settings: takes or finds its languages and picks the default one, and
     * resolves its imports with the building thread's context class loader. A limit on copies means a run that finds
     * all of them in use waits for one; see {@link RuleSet}.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with
     * @param configuration The builder's settings
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options or
     *                                  imports are given for a language the engine doesn't have, as
     *                                  {@link io.github.brantunger.unruly.api.RulesEngineBuilder#build()} describes
     * @throws IllegalArgumentException if a language's own import has more than 1,000 characters; if an import has
     *                                  more than 1,000 characters or more than 64 dot-separated parts, checked
     *                                  before it is looked up; if it is neither a loadable class nor a valid package
     *                                  name; or if it names a class that exists but can't be loaded, with the linkage
     *                                  error's text cut to at most 1,000 characters, with a note of how many were left
     *                                  out, then escaped, and a root cause it hides named
     * @throws NullPointerException     if {@code outputFactory} is {@code null}, checked after the settings
     */
    AbstractRulesEngine(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        LanguageRegistry languages = LanguageRegistry.resolve(configuration.languages(),
                configuration.defaultLanguage(), ImportResolver.contextClassLoader());
        // Checked once the languages are known, so an option for a language that isn't found isn't silently ignored.
        for (String language : configuration.options().keySet()) {
            checkLanguageKnown(languages, language, "Options");
        }
        // A language's own imports aren't looked up, so only their length is checked.
        configuration.languageImports().forEach((language, names) -> {
            checkLanguageKnown(languages, language, "Imports");
            names.forEach(ImportResolver::checkLength);
        });
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
        this.notifier = new ListenerNotifier(log, configuration.listeners());
        this.runTimeout = configuration.runTimeout();
        this.clock = configuration.clock();
        this.outputWriter = configuration.outputWriter();
        Map<String, Class<?>> declaredFacts = configuration.declaredFacts();
        boolean allFactsDeclared = configuration.allFactsDeclared();
        this.factIntake = new FactIntake(log, declaredFacts, allFactsDeclared);
        this.compiler = new RuleListCompiler(log, languages, Collections.unmodifiableSet(packages),
                Collections.unmodifiableSet(classes), configuration.outputType(), configuration.options(),
                declaredFacts, allFactsDeclared, configuration.languageImports());
        this.ruleSets = new RuleSetLifecycle(log, compiler, configuration.copyLimit(), configuration.copiesAtLoad());
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Fails unless a language that options or imports are given for is one of the engine's languages.
     *
     * @param languages The engine's languages
     * @param language  The name the setting was given for
     * @param setting   What was given for it, {@code Options} or {@code Imports}, which starts the message
     * @throws IllegalStateException if the engine has no language named {@code language}
     */
    private static void checkLanguageKnown(LanguageRegistry languages, String language, String setting) {
        if (!languages.languages().containsKey(language)) {
            throw new IllegalStateException(setting + " are given for the expression language '"
                    + Failures.quote(language) + "', which isn't one of the engine's expression languages: "
                    + Failures.quoteAll(new TreeSet<>(languages.languages().keySet())));
        }
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
        RuleSet rules = ruleSets.current();
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
            // outlive its rule lists, so a permit that isn't returned would lower its limit for good. Before that, once
            // every listener has been told how the run ended, the values its languages kept to be closed are closed.
            // A fatal Error from closing them, or the rules as the copy is given back, replaces a failure of the run
            // that isn't fatal, and never the other way round. A run whose facts were rejected holds no copy, and
            // gives nothing back.
            RunResult<O> result;
            try {
                result = runWithCopy(rules, copy, runFacts, rejected, body);
            } catch (Throwable t) {
                // Given back even if setting the interrupt status again fails, as it can when it runs out of stack.
                try {
                    keepInterruptOfStop(tally);
                } finally {
                    Failures.rethrowUnchecked(failedRunEnding(t, endRun(rules, copy, runFacts)));
                }
                throw t;
            }
            Failures.rethrowUnchecked(endRun(rules, copy, runFacts));
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
     * Ends a run once every listener has been told how it ended: closes the values its languages kept with
     * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScopedClosing}, then gives back its copy of
     * the rules. It throws nothing: what closing the values or giving back the copy throws, as logging what a
     * {@code close()} threw can when it runs out of stack, is returned for the run to throw, and the copy is given back
     * whatever closing the values threw. The values are handed over first, so none can be made once the run is ending.
     * Each step's failure is only stored where it's caught, which can't fail again; they are combined last.
     *
     * @param rules The rule set the run borrowed its copy from
     * @param copy  The run's copy, or {@code null} if its facts were rejected and it holds none
     * @param facts The run's facts, whose evaluation context holds the run's values
     * @return What the run throws, or {@code null} if nothing is to be thrown: the first fatal {@link Error} closing
     *         the values or the rules threw, or else the first other throwable that escaped them, as logging one can,
     *         either carrying the rest as suppressed exceptions (see {@link Failures#fatalFirst}). Nothing else a
     *         {@code close()} throws is returned: it was logged. If combining them fails, as it can when it runs out
     *         of memory, the first {@link VirtualMachineError} other than a {@link StackOverflowError} met is
     *         returned alone, or else what combining threw.
     */
    // Any Throwable: the copy must be given back however closing the values fails, as a finally would.
    private static Throwable endRun(RuleSet rules, RuleSet.Copy copy, RunFacts facts) {
        // The first fatal error the JVM threw, a VirtualMachineError but a StackOverflowError, while the run ended,
        // kept with instanceof checks and assignments only, for the last resort below.
        Throwable fatal = null;
        Throwable taking = null;
        List<AutoCloseable> values = List.of();
        try {
            values = facts.evaluation().runScope().end();
            Faults.at(Faults.Step.RUN_VALUES_CLOSING);
        } catch (Throwable t) {
            // The values handed over are closed all the same.
            taking = t;
        }
        if (taking instanceof VirtualMachineError && !(taking instanceof StackOverflowError)) {
            fatal = taking;
        }
        Throwable closing;
        try {
            closing = Closing.runValues(values);
        } catch (Throwable t) {
            closing = t;
        }
        if (fatal == null && closing instanceof VirtualMachineError && !(closing instanceof StackOverflowError)) {
            fatal = closing;
        }
        Throwable releasing = null;
        try {
            if (copy != null) {
                releasing = rules.release(copy);
            }
        } catch (Throwable t) {
            releasing = t;
        }
        if (fatal == null && releasing instanceof VirtualMachineError && !(releasing instanceof StackOverflowError)) {
            fatal = releasing;
        }
        try {
            Faults.at(Faults.Step.RUN_ENDING_COMBINED);
            Throwable ending = Failures.fatalFirst(Failures.fatalFirst(taking, closing), releasing);
            // The fatal error itself, not a throwable that carries it, as a run that fails with one throws it.
            Error carried = Failures.fatalError(ending);
            if (carried != null) {
                Failures.keepAlso(carried, ending);
                return carried;
            }
            return ending;
        } catch (Throwable t) {
            // The last resort, which can't fail again: the fatal error the JVM threw, if any, and else what combining
            // threw, the rest being lost.
            return fatal != null ? fatal : t;
        }
    }

    /**
     * Chooses what a run that failed throws once it has ended: what it failed with, or what ending it threw (see
     * {@link #endRun}). A fatal {@link Error} is never dropped for a failure that isn't fatal: a fatal error from
     * ending the run replaces a failure that isn't fatal, as {@link Failures#fatalInsteadOf} decides, and a fatal
     * failure of the run comes before anything else ending it threw, as {@link Failures#laterInsteadOf} decides: the
     * failure is thrown when the error is in its cause chain, and the error itself, the failure logged at WARN, when
     * it's only suppressed on it. Of two that aren't fatal, what ending threw is thrown, as what giving back the copy
     * threw was before the run's values were closed with it. What is thrown carries the other as a suppressed
     * exception (see {@link Failures#keepAlso}), except that a fatal error only suppressed on the failure carries what
     * ending threw, the failure itself being logged at WARN instead.
     *
     * @param failure What the run failed with
     * @param ending  What {@link #endRun} returned, or {@code null}
     * @return What the run throws in place of {@code failure}, or {@code null} if it throws {@code failure}
     */
    private static Throwable failedRunEnding(Throwable failure, Throwable ending) {
        if (ending == null) {
            return null;
        }
        // endRun returns a fatal error itself, never one that carries it.
        Error fatal = Failures.fatalError(ending);
        return fatal != null ? Failures.fatalInsteadOf(failure, fatal) : Failures.laterInsteadOf(failure, ending);
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
                notifier.notifyRun("beforeRun", listener -> listener.beforeRun(run));
                if (rejected != null) {
                    throw rejected;
                }
                // With the copy held, so no compiler of the rule list the run uses is closed while it checks a name.
                factIntake.checkFactNames(facts.values(), rules.factChecks());
                // Every run that returns passes here, a nested one too, so the result carries the run's tags and
                // start before afterRun or the caller sees it.
                result = body.run(rules, copy, facts).withRun(run);
            } catch (RuntimeException e) {
                notifier.notifyRunError(run, e, e, facts.tally());
                throw e;
            } catch (Error e) {
                // run() rethrows the error itself; listeners see what it failed with.
                notifier.notifyRunError(run, notifier.runFailure(e), e, facts.tally());
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
                notifier.notifyRunError(run, failure, fatal != null ? fatal : failure, facts.tally());
                Failures.throwIfPresent(fatal);
                throw failure;
            }
            notifier.notifyRun("afterRun", listener -> listener.afterRun(run, result));
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
    private EngineRunContext newRun(RuleSet rules, RunFacts facts) {
        return new EngineRunContext(facts.runId(), facts.parent(), matchPolicy(), rules.checksum(),
                facts.forListeners(), facts.selection().tags(), facts.selection().startedAt());
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
     * Returns the rules this engine has loaded, their checksum and when they were loaded. A call made while
     * {@link #close()} runs may still return them, even once {@link #validate(List)} and {@link #load(List)} throw.
     *
     * @return The loaded rules, or an empty rule list with the checksum of no rules before the first
     *         {@link #load(List)}
     * @throws IllegalStateException if the engine is closed
     */
    @Override
    public RuleSetInfo rules() {
        RuleSet rules = ruleSets.currentIfOpen();
        if (rules == null) {
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
        return ruleSets.currentOrThrow("load() must be called before run()");
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
                notifier.notifyRun("beforeRun", listener -> listener.beforeRun(run));
            } catch (Error e) {
                // The run stopped, though the error keeps the stop from reaching onRunError: its event says so.
                tally.markStopped();
                notifier.notifyRunError(run, notifier.runFailure(e), e, tally);
                throw e;
            }
            notifier.notifyRunError(run, failure, failure, tally);
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
        ruleSets.load(ruleList);
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
        return notifier.failure(rule, kind, what + " for rule '" + rule.displayName() + "': "
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
        // An interrupt the expression caught and wrapped, or left suppressed, is put back first, so it counts as one
        // here too, and an Error inside what it threw is the rule's failure as it always is, cancelled or not.
        Failures.keepInterruptStatus(thrown);
        ReportedFailure stop = Failures.errorInChain(thrown) == null
                ? cancellation(rule, DURING_RULE, deadline, thrown) : null;
        if (stop == null) {
            return failed.get();
        }
        stop.addSuppressedByEngine(thrown);
        return notifier.closedWithStop(rule, stop);
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
            throw notifier.closedWithStop(rule, stop);
        }
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
        notifier.notifyBefore(rule, "beforeEvaluate", listener -> listener.beforeEvaluate(rule.rule(), listenerFacts));

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
            throw notifier.failure(rule, ExpressionKind.CONDITION, wrongResult, null);
        }

        notifier.notifyAfter(rule, "afterEvaluate",
                listener -> listener.afterEvaluate(rule.rule(), listenerFacts, result));

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
     * the error while closing a stop, which {@link ListenerNotifier#closedWithStop} recorded for {@code onRunError};
     * otherwise the rule failed.
     *
     * @return {@link RuleEvent#STOPPED} or {@link RuleEvent#FAILED}
     */
    private String fatalOutcome() {
        return ReportedFailure.isStop(notifier.recordedFatalFailure()) ? RuleEvent.STOPPED : RuleEvent.FAILED;
    }

    private O executeAction(CompiledRule rule, RuleSet.Copy copy, O outputResult, RunFacts facts) {
        notifier.notifyBefore(rule, "beforeExecute", listener -> listener.beforeExecute(rule.rule(), outputResult));

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
            throw notifier.failure(rule, ExpressionKind.ACTION, wrongResult, null);
        }
        for (Map.Entry<String, Object> property : result.properties().entrySet()) {
            setProperty(rule, outputResult, property.getKey(), property.getValue(), facts.deadline());
        }
        // After them too: a writer that took the run past its deadline stops it, though what it set stays set.
        stopIfCancelled(rule, ExpressionKind.ACTION, facts.deadline(), null);

        notifier.notifyAfter(rule, "afterExecute", listener -> listener.afterExecute(rule.rule(), outputResult));

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
        return notifier.failure(rule, ExpressionKind.ACTION, "Failed to set '" + Failures.quote(property)
                + "' on the output for rule '" + rule.displayName() + "': " + Failures.describe(cause), cause);
    }
}
