package io.github.brantunger.unruly.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.brantunger.unruly.api.FactReference;
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
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

/**
 * What every engine shares, whatever its match policy: loading and compiling rules, borrowing a compiled copy for
 * each run, evaluating conditions and running actions, listener callbacks, cancellation and failure reporting. A
 * subclass supplies the match policy in {@link #runRules(FactStore, Duration, Set)} and names it in
 * {@link #matchPolicy()}.
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

    private static final String OUTPUT_KEYWORD = ActionContext.OUTPUT_NAME;
    private static final String CLOSED_MESSAGE = "The engine is closed";
    // Where a cancelled run stopped, as its message says: before a rule's condition or action, or while one ran.
    private static final String BEFORE_RULE = "before";
    private static final String DURING_RULE = "during";
    // How many times one run may read the engine's rules in all: its first reading, and one more for each time it
    // finds the set it read closed before it could borrow from it. Reading again settles the one race that can
    // cause that — a reload, or close(), retires the set the run had read in between its reading and its borrow —
    // and each concurrent reload can stale a run once, so the bound is far above one. Past it, the invariant a run
    // relies on has broken, and a run that spun instead would leave no evidence.
    private static final int RULE_READS_PER_RUN = 64;
    // The engine's languages and its default language, fixed when it's built.
    private final LanguageRegistry languages;
    // The imported packages and classes, resolved when the engine is built and passed to every compilation.
    private final Set<String> packageImports;
    private final Set<Class<?>> classImports;
    // Fixed when the engine is built, so every callback of a run goes to the same listeners.
    private final List<RuleListener> listeners;
    // Volatile so a load() call on one thread is seen by run() on others. The rule set holds the rules and the
    // compilers of the languages they use, and is fully built before it is assigned, so one volatile write swaps in
    // both. Assigned while holding lifecycle, so each rule set replaced is retired once, and none is assigned after
    // close().
    private volatile RuleSet ruleSet;
    private volatile boolean closed;
    private final Object lifecycle = new Object();
    // How many compiled copies of the rules runs hold at once, and which runs that applies to.
    private final CopyLimit copyLimit;
    // The permits for copyLimit, which every rule list this engine loads shares, so a reload can't raise the limit.
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
    // The output type languages are told about, and what sets the properties actions return.
    private final Class<?> outputType;
    private final OutputWriter<? super O> outputWriter;
    // The declared type of each fact, by name, a primitive type as it was declared, and whether a run may supply only
    // those facts.
    private final Map<String, Class<?>> declaredFacts;
    private final boolean allFactsDeclared;
    // The facts declared with a primitive type, by name, which a run widens a boxed primitive to. Empty for most
    // engines, which then convert nothing.
    private final Map<String, Class<?>> primitiveFacts;
    // Each language's options, by language name.
    private final Map<String, Map<String, String>> options;
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
     * @param configuration The builder's settings
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are given
     *                                  for a language the engine doesn't have, as
     *                                  {@link io.github.brantunger.unruly.api.RulesEngineBuilder#build()} describes
     * @throws IllegalArgumentException if an import is neither a loadable class nor a valid package name, or names a
     *                                  class that exists but can't be loaded
     */
    AbstractRulesEngine(EngineConfiguration<O> configuration) {
        this.languages = LanguageRegistry.resolve(configuration.languages(), configuration.defaultLanguage(),
                ImportResolver.contextClassLoader());
        // Checked once the languages are known, so an option for a language that isn't found isn't silently ignored.
        for (String language : configuration.options().keySet()) {
            if (!languages.languages().containsKey(language)) {
                throw new IllegalStateException("Options are given for the expression language '"
                        + Failures.quote(language) + "', which isn't one of the engine's expression languages: "
                        + new TreeSet<>(languages.languages().keySet()));
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
        this.packageImports = Collections.unmodifiableSet(packages);
        this.classImports = Collections.unmodifiableSet(classes);
        this.listeners = configuration.listeners();
        this.copyLimit = configuration.copyLimit();
        this.copyPermits = new CopyPermits(copyLimit.maxCopies());
        this.copiesAtLoad = configuration.copiesAtLoad();
        this.runTimeout = configuration.runTimeout();
        this.clock = configuration.clock();
        this.outputType = configuration.outputType();
        this.outputWriter = configuration.outputWriter();
        this.declaredFacts = configuration.declaredFacts();
        this.allFactsDeclared = configuration.allFactsDeclared();
        Map<String, Class<?>> primitives = new HashMap<>();
        declaredFacts.forEach((name, type) -> {
            // void is primitive, but nothing widens to it, and no value is a Void.
            if (type.isPrimitive() && type != void.class) {
                primitives.put(name, type);
            }
        });
        this.primitiveFacts = Map.copyOf(primitives);
        this.options = configuration.options();
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
        Objects.requireNonNull(options, "options must not be null");
        Duration timeout = options.timeout();
        return runRules(facts, timeout == null ? runTimeout : timeout, options.tags());
    }

    /**
     * Fires the rules the way this engine fires them.
     *
     * @param facts   The facts the run was given
     * @param timeout How long the run may take, or {@code null} if it has no deadline
     * @param tags    The tags that choose the rules the run uses, or none to use rules whatever their tags
     * @return What the run did
     */
    abstract RunResult<O> runRules(FactStore<?> facts, Duration timeout, Set<String> tags);

    /**
     * Runs one run inside its listener scope: every listener gets {@link RuleListener#beforeRun}, then the rule
     * callbacks, then exactly one of {@link RuleListener#afterRun} and {@link RuleListener#onRunError}.
     *
     * <p>
     * The fact values are collected before the run borrows a copy of the rules, so the scope can carry them, and their
     * names are checked inside the scope, so a name no language can refer to reaches {@code onRunError}. A run that
     * waits for a copy opens its scope when the wait ends; an interrupt while waiting opens and closes a scope of its
     * own. Failing because no rules are loaded, or because the engine is closed, is misuse and reaches no listener.
     * Failing because the engine's rule list was closed over and over while the run was borrowing a copy reaches
     * none either: that means an engine invariant has broken rather than that the call was wrong.
     * </p>
     *
     * @param facts   The facts the run was given
     * @param timeout How long the run may take, or {@code null} if it has no timeout of its own. The deadline is
     *                taken from when the run starts, so waiting for a copy of the rules counts towards it, and a run
     *                started from inside another run on this thread stops no later than that run's deadline.
     * @param tags    The tags that choose the rules the run uses, or none to use rules whatever their tags
     * @param body    What the engine does once it holds a copy of the rules
     * @return What the run did
     * @throws IllegalStateException if {@link #load(List)} has not been called, the engine is closed, or the run read
     *                               a closed rule list {@value #RULE_READS_PER_RUN} times in a row, which means the
     *                               engine's own invariant has broken
     */
    // Any Throwable: the copy must be given back however the run ends, as a finally would, and a failure that isn't
    // fatal is kept under a fatal Error from closing.
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
        try {
            Instant deadline = Cancellation.deadlineFrom(timeout);
            // Read once, so every rule's validity window is judged at the same time, however long the run takes.
            // A clock that returns null fails the run here, like one that throws, before any listener hears of it.
            RuleSelection selection = new RuleSelection(
                    Objects.requireNonNull(clock.instant(), "the engine's clock returned a null instant"), tags);
            Map<String, Object> values = factValues(facts);
            Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(values);
            RuleSet.Copy copy = borrow(rules, listenerFacts, deadline, runId, parent, tally, selection);
            for (int read = 1; copy == null; read++) {
                // The rule set the run read was closed before it could borrow from it, which a reload does to the
                // set it replaced, and close() to the set it detaches: reading again finds the set that replaced it,
                // or reports the closed engine. A run the caller has stopped meanwhile stops here rather than
                // reading again, and one that keeps finding closed sets fails rather than spinning for ever.
                stopIfCancelled(rules, listenerFacts, deadline, runId, parent, tally, selection);
                if (read == RULE_READS_PER_RUN) {
                    throw new IllegalStateException("The engine's rule list was closed " + RULE_READS_PER_RUN
                            + " times in a row while this run was borrowing a copy of it. A rule list is closed only"
                            + " after it has been retired, and only a rule list that is no longer the engine's"
                            + " current one is retired, so the list a run reads can never already be closed: that"
                            + " invariant has broken. Please report this stack trace at"
                            + " https://github.com/brantunger/unruly-engine/issues");
                }
                rules = currentRules();
                copy = borrow(rules, listenerFacts, deadline, runId, parent, tally, selection);
            }
            // The copy is given back however the run ends, even when setting it up fails: the engine's permits
            // outlive its rule lists, so a permit that isn't returned would lower its limit for good. A fatal Error
            // from closing the rules as it's given back replaces a failure of the run that isn't fatal.
            RunResult<O> result;
            try {
                result = runWithCopy(rules, copy, values, listenerFacts, deadline, runId, parent, tally, selection,
                        body);
            } catch (Throwable t) {
                keepInterruptOfStop(tally);
                Failures.throwIfPresent(Failures.fatalInsteadOf(t, rules.release(copy)));
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
            // Again on the way out, as a language's close() may have cleared it too.
            keepInterruptOfStop(tally);
            if (event != null) {
                event.commit(engineId, runId, parentRunId, matchPolicy(), tally, rules.checksum(), outcome);
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
        if (tally.wasInterrupted()) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs the rules with a copy the caller borrowed and gives back, inside the run's listener and deadline scope. */
    private RunResult<O> runWithCopy(RuleSet rules, RuleSet.Copy copy, Map<String, Object> values,
                                     Map<String, Object> listenerFacts, Instant deadline, long runId,
                                     RunContext parent, RunTally tally, RuleSelection selection,
                                     RunBody<O> body) {
        List<RuleListener> snapshot = listenerSnapshot();
        EngineRunContext run = newRun(runId, rules, listenerFacts, parent, selection);
        currentRun.set(run);
        Instant outerDeadline = Cancellation.enter(deadline);
        fatalFailure.remove();
        try {
            RunResult<O> result;
            try {
                // Inside the try, so a fatal Error from a listener's beforeRun still closes every listener's run.
                notifyRun(snapshot, "beforeRun", listener -> listener.beforeRun(run));
                checkFactNames(values, rules.factChecks());
                // Every run that returns passes here, a nested one too, so the result carries the run's tags and
                // start before afterRun or the caller sees it.
                result = body.run(rules, copy, RunFacts.of(values, listenerFacts, deadline, runId, tally, selection))
                        .withRun(run);
            } catch (RuntimeException e) {
                notifyRunError(snapshot, run, e, tally);
                throw e;
            } catch (Error e) {
                // run() rethrows the error itself; listeners see what it failed with.
                notifyRunError(snapshot, run, runFailure(e), tally);
                throw e;
            } catch (Throwable t) {
                // A backstop: every place the run calls a rule, a listener, a language or the output reports a
                // Throwable that is neither an Exception nor an Error as a failure of its own, so none should get
                // here. One that does is handled the same way: it fails the run like an exception, closing every
                // listener's run, and a fatal Error among its causes is then rethrown unchanged.
                Failures.keepInterruptStatus(t);
                String msg = "The run failed with " + Failures.describeWithClass(t);
                log.error(msg);
                RuleExecutionException failure = new ReportedFailure(msg, t);
                notifyRunError(snapshot, run, failure, tally);
                Failures.throwIfPresent(Failures.fatalError(t));
                throw failure;
            }
            notifyRun(snapshot, "afterRun", listener -> listener.afterRun(run, result));
            return result;
        } finally {
            fatalFailure.remove();
            Cancellation.leave(outerDeadline);
            if (parent == null) {
                currentRun.remove();
            } else {
                currentRun.set(parent);
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
     * before the copy is given back and when {@code run()} returns (see {@link #keepInterruptOfStop}).
     */
    private void notifyRunError(List<RuleListener> snapshot, RunContext run, RuntimeException error, RunTally tally) {
        if (ReportedFailure.isStop(error)) {
            tally.markStopped();
            if (error.getCause() instanceof InterruptedException) {
                tally.markInterrupted();
            }
        }
        notifyRun(snapshot, "onRunError", listener -> listener.onRunError(run, error));
    }

    /** Creates the context one run is reported to listeners with. */
    private EngineRunContext newRun(long runId, RuleSet rules, Map<String, Object> listenerFacts, RunContext parent,
                                    RuleSelection selection) {
        return new EngineRunContext(runId, parent, matchPolicy(), rules.checksum(), listenerFacts, selection.tags(),
                selection.startedAt());
    }

    /**
     * Calls one run callback on every listener, logging what a listener throws, like the rule callbacks. A fatal
     * {@link Error} a listener throws is rethrown once every listener has had the callback.
     */
    private void notifyRun(List<RuleListener> snapshot, String callback, Consumer<RuleListener> call) {
        Error fatal = notifyListeners(snapshot, callback, call);
        if (fatal != null) {
            log.error("A listener threw {} in {}", fatal.getClass().getName(), callback);
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
     * @return The copy, to give back with {@link RuleSet#release(RuleSet.Copy)}
     * @throws RuleExecutionException if the thread is interrupted while it waits for a copy, or for a build slot to
     *                                make one, or if the run's deadline passes while it waits for a copy under a
     *                                limit
     */
    private RuleSet.Copy borrow(RuleSet rules, Map<String, Object> listenerFacts, Instant deadline, long runId,
                                RunContext parent, RunTally tally, RuleSelection selection) {
        try {
            return rules.borrow(deadline);
        } catch (InterruptedException | TimeoutException e) {
            // Straight on, allocating nothing: the run is still counted on the rule set until it leaves there.
            throw stoppedThenLeft(rules, listenerFacts, e, deadline, runId, parent, tally, selection);
        }
    }

    /**
     * Reports a run that stopped waiting for a copy, as {@link #stoppedWaiting} does, and then leaves the rule set it
     * waited on, which {@link RuleSet#borrow(Instant)} left the run counted on for this. The caller always throws what
     * this returns, or what it throws. Leaving closes the rule set only if it was retired and this run was its last
     * user; a fatal {@link Error} from that closing is thrown in place of the stop, carrying it as a suppressed
     * exception, unless a listener threw a fatal error while the stop was reported, which came first and is thrown
     * instead. An interrupted run sets its thread's interrupt status again before it leaves, and {@code run()} again
     * when it returns (see {@link #keepInterruptOfStop}).
     *
     * @param stop What the wait stopped with: an {@link InterruptedException} or a {@link TimeoutException}
     * @return The stop, for the caller to throw
     */
    // Any Throwable: the run must leave however reporting the stop ends, as a finally would, and a failure that isn't
    // fatal is kept under a fatal Error from closing. Everything that allocates, the message too, is inside the try.
    private RuleExecutionException stoppedThenLeft(RuleSet rules, Map<String, Object> listenerFacts, Exception stop,
                                                   Instant deadline, long runId, RunContext parent, RunTally tally,
                                                   RuleSelection selection) {
        RuleExecutionException failure;
        try {
            boolean interrupted = stop instanceof InterruptedException;
            String msg;
            if (interrupted) {
                // Without a limit, the only wait is for a build slot, on a virtual thread.
                msg = "run() was interrupted while waiting " + (rules.limit() == RuleSet.UNLIMITED
                        ? "to make a compiled copy of the rules: every build slot was in use"
                        : "for a compiled copy of the rules: all " + rules.limit() + " were in use");
            } else {
                msg = "run() passed its deadline of " + deadline + " while waiting for a compiled copy of the rules:"
                        + " all " + rules.limit() + " were in use";
            }
            // The deadline passed, or none did when an interrupt stopped the run.
            failure = stoppedWaiting(rules, listenerFacts, msg, stop, deadline, interrupted ? null : deadline, runId,
                    parent, tally, selection);
        } catch (Throwable t) {
            keepInterruptOfStop(tally);
            Failures.throwIfPresent(Failures.fatalInsteadOf(t, rules.leaveAfterStop()));
            throw t;
        }
        keepInterruptOfStop(tally);
        Failures.throwIfPresent(Failures.fatalInsteadOf(failure, rules.leaveAfterStop()));
        return failure;
    }

    /**
     * Stops a run that was interrupted, or passed its deadline, while reading the engine's rules again because the
     * rule set it read had been closed. Reported like a run that stopped waiting for a copy: it too stopped before
     * it got one, and for the same two reasons.
     *
     * @param rules         The rule set the run found closed
     * @param listenerFacts The run's facts, as listeners see them
     * @param deadline      When the run must stop, or {@code null} if it has none
     * @param runId         The run's number
     * @param parent        The run this one was started from, or {@code null}
     * @param tally         The run's tally, which records the stop for the run's event
     * @param selection     The run's tags and start, which its context carries
     * @throws RuleExecutionException if the thread is interrupted, or the run's deadline has passed
     */
    private void stopIfCancelled(RuleSet rules, Map<String, Object> listenerFacts, Instant deadline, long runId,
                                 RunContext parent, RunTally tally, RuleSelection selection) {
        String reading = " while reading the engine's rules again: the rules this run read had been closed by a"
                + " reload or by close()";
        if (Thread.currentThread().isInterrupted()) {
            throw stoppedWaiting(rules, listenerFacts, "run() was interrupted" + reading, new InterruptedException(),
                    deadline, null, runId, parent, tally, selection);
        }
        if (Cancellation.hasPassed(deadline)) {
            throw stoppedWaiting(rules, listenerFacts, "run() passed its deadline of " + deadline + reading,
                    Cancellation.timedOut(deadline), deadline, deadline, runId, parent, tally, selection);
        }
    }

    /**
     * Reports a run that stopped before it got a copy of the rules, because it was interrupted or passed its deadline
     * while waiting. Logged at WARN, like the check between rules: a run the caller stopped isn't the rules or the
     * engine failing.
     *
     * @param rules         The rule set the run was waiting on
     * @param listenerFacts The run's facts, as listeners see them
     * @param msg           What to log and what the exception says
     * @param cause         An {@link InterruptedException} or a {@link TimeoutException}
     * @param deadline      When the run had to stop, or {@code null} if it had no deadline
     * @param passed        The deadline the run passed, or {@code null} if it was interrupted instead
     * @param runId         The run's number
     * @param parent        The run this one was started from, or {@code null}
     * @param tally         The run's tally, which records the stop for the run's event
     * @param selection     The run's tags and start, which its context carries
     * @return The exception to throw
     */
    private RuleExecutionException stoppedWaiting(RuleSet rules, Map<String, Object> listenerFacts, String msg,
                                                  Exception cause, Instant deadline, Instant passed, long runId,
                                                  RunContext parent, RunTally tally, RuleSelection selection) {
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
        List<RuleListener> snapshot = listenerSnapshot();
        EngineRunContext run = newRun(runId, rules, listenerFacts, parent, selection);
        currentRun.set(run);
        Instant outerDeadline = Cancellation.enter(deadline);
        try {
            try {
                notifyRun(snapshot, "beforeRun", listener -> listener.beforeRun(run));
            } catch (Error e) {
                // The run stopped, though the error keeps the stop from reaching onRunError: its event says so.
                tally.markStopped();
                notifyRunError(snapshot, run, runFailure(e), tally);
                throw e;
            }
            notifyRunError(snapshot, run, failure, tally);
            return failure;
        } finally {
            Cancellation.leave(outerDeadline);
            if (parent == null) {
                currentRun.remove();
            } else {
                currentRun.set(parent);
            }
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
     * each declared fact name the languages reject, last. Its {@code failures()} has each, and its message lists
     * them. A {@code null} rule or a duplicate name is thrown at once, before anything is compiled.
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
     * closed has been closed: the first, if there are several. A rule list that fails to load throws it in place of
     * its own failure, which the error carries as a suppressed exception, or which is logged at WARN if the error
     * can't carry one, as the JVM's own {@link OutOfMemoryError} can't; unless that failure is itself a fatal error,
     * which came first and is thrown instead. A reload throws it after swapping its rules in, when it closes the rule
     * list they replaced: the new rules stay loaded, and runs use them.
     * </p>
     *
     * @param ruleList The List of {@link Rule} objects to compile.
     * @throws RuleCompilationException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     * @throws IllegalStateException if the engine is closed; this is checked before anything in the list, so a closed
     *                               engine throws it even for a list that would fail to load
     */
    // The rule set closes the compilers, or this method does if the rule list fails to load.
    @Override
    public void load(List<Rule> ruleList) {
        Objects.requireNonNull(ruleList, "ruleList must not be null");
        // Checked here so a closed engine rejects any list, and again under the lock, for a close() that runs while
        // this compiles.
        if (closed) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        // A null rule or a duplicate name stops the load before anything is compiled: duplicate names would make
        // error messages, exceptions and listener logs ambiguous.
        List<RuleCompilationException> listProblems = listProblems(ruleList, true);
        if (!listProblems.isEmpty()) {
            RuleCompilationException first = listProblems.get(0);
            log.error(first.getMessage());
            throw first;
        }
        Compilation compilation = new Compilation(true);
        RuleSet loaded;
        try {
            compilation.compile(ruleList);
            throwIfAnyFailed(compilation.failures);
            loaded = new RuleSet(compilation.compiled, compilation.used, copyLimit, copyPermits, stallWindowMillis);
        } catch (Throwable t) {
            // Any Throwable, as in validate(): the compilers created must be closed however compiling ends.
            Failures.throwIfPresent(Failures.fatalInsteadOf(t, compilation.closeCompilers()));
            throw t;
        }
        prepareCopies(loaded);
        RuleSet replaced;
        synchronized (lifecycle) {
            if (closed) {
                IllegalStateException failure = new IllegalStateException(CLOSED_MESSAGE);
                retireBefore(loaded, failure);
                throw failure;
            }
            replaced = ruleSet;
            ruleSet = loaded;
        }
        if (replaced != null) {
            Failures.throwIfPresent(replaced.retire());
        }
    }

    /**
     * Makes the copies of the rules the engine was built to make at load. From here on the rule set owns the
     * compilers, so a failure retires it, which closes the copies made so far, the one that failed too, and then the
     * compilers, once.
     *
     * @param loaded The rule set, which no run can see yet
     * @throws RuleCompilationException if a language can't create or warm up a session: already logged, as
     *                                  {@code load()} logs every failure
     */
    // The cause is what the language threw, as when a language can't create its compiler: the ReportedFailure around
    // it is the engine's own wrapper for a run, and was logged when it was made.
    // Any Throwable: the rules must be closed however this ends, as a finally would, and a failure that isn't fatal is
    // kept under a fatal Error from closing.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void prepareCopies(RuleSet loaded) {
        try {
            loaded.prepareCopies(copiesAtLoad);
        } catch (ReportedFailure e) {
            RuleCompilationException failure = new RuleCompilationException(e.getMessage(), e.getCause());
            retireBefore(loaded, failure);
            throw failure;
        } catch (Throwable t) {
            retireBefore(loaded, t);
            throw t;
        }
    }

    /**
     * Retires a rule set that {@code load()} won't swap in, before the caller throws {@code failure}: closes its
     * copies, and then its compilers. A fatal {@link Error} from closing them is thrown here instead, carrying
     * {@code failure} as suppressed, or logging it at WARN if the error can't carry one, unless {@code failure} is a
     * fatal error itself, which came first (see {@link Failures#fatalInsteadOf}).
     *
     * @param loaded  The rule set, which no run can see
     * @param failure What the caller throws next
     */
    private static void retireBefore(RuleSet loaded, Throwable failure) {
        Failures.throwIfPresent(Failures.fatalInsteadOf(failure, loaded.retire()));
    }

    /**
     * Closes the engine. The rule list is closed once no run is using it: a run holding a copy finishes, and so does
     * one waiting for a copy, because {@link RuleSet#borrow(Instant)} counts the run before it waits, and a rule list
     * with a run counted on it can't close. Their sessions are closed as each one returns, and the languages'
     * compilers after the last one. Afterwards, {@code run()} and {@link #load(List)} throw
     * {@link IllegalStateException} — as does a run that had read the rules but had not yet begun to borrow a copy
     * when this method closed them, because it reads them again and finds a closed engine. A {@code load()} that
     * found the engine open before this method closed it isn't stopped. If it fails, it throws what it would on an
     * open engine, such as {@link RuleCompilationException}. If it succeeds, either it swapped its rules in first, and
     * this method retires them like any others, or it finds the engine closed, retires its rules rather than swapping
     * them in, and throws {@link IllegalStateException}. Closing it again does nothing.
     *
     * <p>
     * A fatal {@link Error} a language throws while closing a session or a compiler is rethrown once every idle copy,
     * and the compilers if no run holds a copy, has been closed: the first, if there are several. The engine is closed
     * all the same, so closing it again does nothing. A copy a run still holds is closed when the run gives it back,
     * and a fatal error from that reaches the run.
     * </p>
     */
    // A closed engine has no rule set.
    @SuppressWarnings("PMD.NullAssignment")
    @Override
    public void close() {
        RuleSet replaced;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            replaced = ruleSet;
            ruleSet = null;
        }
        if (replaced != null) {
            Failures.throwIfPresent(replaced.retire());
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
    // Any Throwable: the compilers must be closed however this ends, as a finally would, and a failure that isn't
    // fatal is kept under a fatal Error from closing.
    @Override
    public List<RuleCompilationException> validate(List<Rule> ruleList) {
        Objects.requireNonNull(ruleList, "ruleList must not be null");
        if (closed) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        List<RuleCompilationException> problems = listProblems(ruleList, false);
        Compilation compilation = new Compilation(false);
        try {
            compilation.compile(ruleList.stream().filter(Objects::nonNull).toList());
        } catch (Throwable t) {
            Failures.throwIfPresent(Failures.fatalInsteadOf(t, compilation.closeCompilers()));
            throw t;
        }
        Failures.throwIfPresent(compilation.closeCompilers());
        problems.addAll(compilation.failures);
        return Collections.unmodifiableList(problems);
    }

    /**
     * Finds what makes a rule list unusable before any rule is compiled: a {@code null} entry, and a name a rule
     * shares with an earlier one.
     *
     * @param ruleList  The list to check
     * @param firstOnly Whether to stop at the first problem, which is all {@code load()} reports
     * @return One failure for each such entry, in list order; none when the list is usable
     */
    private static List<RuleCompilationException> listProblems(List<Rule> ruleList, boolean firstOnly) {
        List<RuleCompilationException> problems = new ArrayList<>();
        Set<String> ruleNames = new HashSet<>();
        for (int i = 0; i < ruleList.size(); i++) {
            Rule rule = ruleList.get(i);
            if (rule == null) {
                problems.add(compilationFailure("Rule at index " + i + " of the rule list is null", null, null));
            } else if (!ruleNames.add(rule.getRuleName())) {
                problems.add(compilationFailure("Duplicate rule name '" + Failures.quote(rule.getRuleName()) + "'",
                        null, rule.getRuleName()));
            }
            if (firstOnly && !problems.isEmpty()) {
                return problems;
            }
        }
        return problems;
    }

    /**
     * One compilation of a rule list, for {@code load()} or {@code validate()}: compiles every rule with the languages'
     * compilers, checks the declared fact names, and collects every failure rather than throwing the first.
     */
    private final class Compilation {

        private final boolean logged;
        private final LanguageCompilers compilers;
        private final List<CompiledRule> compiled = new ArrayList<>();
        private final List<RuleCompilationException> failures = new ArrayList<>();
        private Map<String, ExpressionCompiler> used = Map.of();

        /**
         * Prepares a compilation with a compiler registry for the engine's languages.
         *
         * @param logged Whether each failure, and each warning a language reports, is logged: {@code load()} logs,
         *               {@code validate()} doesn't
         */
        Compilation(boolean logged) {
            this.logged = logged;
            ClassLoader loader = ImportResolver.contextClassLoader();
            // Each language gets its own options.
            compilers = new LanguageCompilers(languages.languages(), (name, language) -> newCompiler(name, language,
                    new EngineCompileContext(packageImports, classImports, loader, outputType,
                            options.getOrDefault(name, Map.of()), declaredFacts, allFactsDeclared, logged)));
        }

        /**
         * Compiles the rules in priority order, then checks the declared fact names. The list has no {@code null}
         * entry.
         *
         * @param ruleList The rules
         */
        void compile(List<Rule> ruleList) {
            List<Rule> sorted = ruleList.stream()
                    .sorted(Comparator.comparing(
                            Rule::getPriority,
                            Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
            for (Rule rule : sorted) {
                String language = languageOf(rule);
                // A language that can't create its compiler is reported once, for the first rule that needed it. The
                // rules written in it can't be compiled, and asking the language again would only repeat the failure.
                if (compilers.failed(language)) {
                    continue;
                }
                try {
                    compiled.add(compileRule(rule, compilers.forLanguage(language), compilers));
                } catch (RuleCompilationException e) {
                    failed(e);
                }
            }
            // The compilers every fact is checked with: the ones the rules used, or the default language's for an empty
            // list. When rules failed, only the compilers already created check the declared names.
            if (failures.isEmpty()) {
                try {
                    used = compilers.used(languages.defaultLanguage());
                } catch (RuleCompilationException e) {
                    failed(e);
                    used = compilers.created();
                }
            } else {
                used = compilers.created();
            }
            declaredNameFailures(used).forEach(this::failed);
        }

        private void failed(RuleCompilationException failure) {
            if (logged) {
                log.error(failure.getMessage());
            }
            failures.add(failure);
        }

        /**
         * Closes the compilers created, when the rule list isn't kept.
         *
         * @return The first fatal {@link Error} a compiler threw, for the caller to throw, or {@code null} if none did
         */
        Error closeCompilers() {
            return Closing.compilers(compilers.created());
        }
    }

    /**
     * Collects the fact values a run was given, without checking their names, so the run's listeners can be given the
     * facts before the names are checked. A fact declared with a primitive type whose value is a boxed primitive that
     * Java widens to that type, such as an {@link Integer} for a {@code long}, is widened here, so listeners and
     * languages see only the declared type; its value in the store isn't changed. Any other value is kept as it is,
     * for {@link #checkDeclaredType(String, Object)} to judge.
     *
     * @param facts The key/value fact store
     * @return A map of fact names to their values, which may hold a {@code null} name a custom store allowed
     */
    private Map<String, Object> factValues(FactStore<?> facts) {
        Map<String, Object> entryMap = new HashMap<>();
        for (Map.Entry<String, ? extends FactReference<?>> entry : facts.asMap().entrySet()) {
            // A null reference is bound as null, like a Fact holding null. Skipping it left the name
            // unresolvable, so `x == null` failed instead of matching.
            FactReference<?> fact = entry.getValue();
            entryMap.put(entry.getKey(), fact != null ? fact.getValue() : null);
        }
        if (primitiveFacts.isEmpty()) {
            return entryMap;
        }
        primitiveFacts.forEach((name, type) -> {
            // A null value, or a fact the run left out, stays as it is.
            Object value = entryMap.get(name);
            if (value != null) {
                entryMap.put(name, Widening.widen(value, type));
            }
        });
        return entryMap;
    }

    /**
     * Checks every fact name of a run: not {@code null}, not the output's name, and one the language of each loaded
     * rule can refer to.
     *
     * @param values The fact values by name
     * @param checks The compilers of the rule list the run uses, which check each name, by language name
     * @throws IllegalArgumentException if a fact is named {@code null} or {@code output}, has a name a language
     *                                  can't refer to, isn't an instance of the type it was declared with or of its
     *                                  wrapper, or, when
     *                                  the engine requires declared facts, was declared and left out or supplied
     *                                  without being declared; or if a language's check of the name throws anything
     *                                  else
     */
    private void checkFactNames(Map<String, Object> values, Map<String, ExpressionCompiler> checks) {
        for (String name : values.keySet()) {
            if (name == null) {
                String msg = "fact name must not be null";
                log.error(msg);
                throw new IllegalArgumentException(msg);
            }
            // Actions bind the output object to this name, silently hiding a fact of the same name.
            if (OUTPUT_KEYWORD.equals(name)) {
                String msg = "'" + OUTPUT_KEYWORD + "' is reserved for the output object and cannot be used as a "
                        + "fact name";
                log.error(msg);
                throw new IllegalArgumentException(msg);
            }
            IllegalArgumentException rejected = factNameRejection(name, checks, true);
            if (rejected != null) {
                throw rejected;
            }
            checkDeclaredType(name, values.get(name));
        }
        checkNothingWasLeftOut(values);
    }

    /**
     * Checks one fact's value against the type it was declared with, or the type's wrapper if it's primitive: a value
     * that Java widens to a primitive type was widened when the facts were collected. A {@code null} value passes:
     * nothing about it contradicts the declaration, and a language can't tell it from an absent fact either.
     *
     * @param name  The fact's name
     * @param value The fact's value, which may be {@code null}
     * @throws IllegalArgumentException if the fact was declared and its value isn't an instance of that type, or of its
     *                                  wrapper; for a primitive type and a number, a character or a boolean, the
     *                                  message says why the value wasn't widened
     */
    private void checkDeclaredType(String name, Object value) {
        Class<?> declared = declaredFacts.get(name);
        if (declared == null || value == null || Widening.wrap(declared).isInstance(value)) {
            return;
        }
        String msg = "Fact '%s' was declared as %s, but the run supplied a %s%s"
                .formatted(Failures.quote(name), declared.getName(), value.getClass().getName(),
                        primitiveFacts.containsKey(name) && Widening.isPrimitiveLike(value)
                                ? " (" + Widening.ONLY_WIDENED + ")" : "");
        log.error(msg);
        throw new IllegalArgumentException(msg);
    }

    /**
     * Checks that a run supplied every declared fact and nothing else, when the engine was built with
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#requireDeclaredFacts()}. Without it, a run may supply
     * whatever it likes, and a declared fact only says what its type is when it's there.
     *
     * @param values The fact values by name
     * @throws IllegalArgumentException if a declared fact is missing, or a fact nobody declared was supplied
     */
    private void checkNothingWasLeftOut(Map<String, Object> values) {
        if (!allFactsDeclared) {
            return;
        }
        for (String name : values.keySet()) {
            if (!declaredFacts.containsKey(name)) {
                String msg = "Fact '%s' wasn't declared, and this engine was built with requireDeclaredFacts()"
                        .formatted(Failures.quote(name));
                log.error(msg);
                throw new IllegalArgumentException(msg);
            }
        }
        for (String name : declaredFacts.keySet()) {
            if (!values.containsKey(name)) {
                String msg = ("Fact '%s' was declared, but the run didn't supply it, and this engine was built with "
                        + "requireDeclaredFacts()").formatted(Failures.quote(name));
                log.error(msg);
                throw new IllegalArgumentException(msg);
            }
        }
    }

    /**
     * Checks every declared fact name with the languages of a rule list being loaded, which are the ones its runs check
     * names with, and returns a failure for each name a language rejects or fails to check.
     *
     * @param checks The compilers to check the names with, by language name
     * @return One failure for each declared name that can't be used; none when every name passes
     */
    private List<RuleCompilationException> declaredNameFailures(Map<String, ExpressionCompiler> checks) {
        List<RuleCompilationException> failures = new ArrayList<>();
        for (String name : declaredFacts.keySet()) {
            IllegalArgumentException rejected = factNameRejection(name, checks, false);
            if (rejected != null) {
                failures.add(compilationFailure("Declared fact '" + Failures.quote(name) + "' can't be used: "
                        + Failures.describe(rejected), rejected, null));
            }
        }
        return failures;
    }

    /**
     * Checks a fact name with the language of each rule in use. A language rejects a name with an
     * {@link IllegalArgumentException}, which is returned as is. Anything else a language throws, a {@link Throwable}
     * that is neither an exception nor an error too, is returned as an {@code IllegalArgumentException} naming the fact
     * and the language, except a fatal {@link Error}, thrown or among the causes of what the language throws, which is
     * logged and rethrown.
     *
     * @param name   The fact's name
     * @param checks The compilers to check it with, by language name
     * @param logged Whether to log the rejection at ERROR, escaped; {@code false} when the caller logs its own message
     * @return The exception a language rejected the name with, or {@code null} if every language accepts it
     */
    private static IllegalArgumentException factNameRejection(String name, Map<String, ExpressionCompiler> checks,
                                                              boolean logged) {
        for (Map.Entry<String, ExpressionCompiler> check : checks.entrySet()) {
            try {
                check.getValue().checkFactName(name);
            } catch (IllegalArgumentException e) {
                // The language wrote this message and it names the fact, so it's escaped before it's logged. The
                // exception is returned as it came, so a caller still reads exactly what the language said.
                if (logged) {
                    log.error(Failures.describe(e));
                }
                return e;
            } catch (Throwable e) {
                Failures.keepInterruptStatus(e);
                String msg = "The '%s' expression language failed to check fact name '%s': %s"
                        .formatted(Failures.quote(check.getKey()), Failures.quote(name), Failures.describe(e));
                Error fatal = Failures.fatalError(e);
                if (logged || fatal != null) {
                    log.error(msg);
                }
                Failures.throwIfPresent(fatal);
                return new IllegalArgumentException(msg, e);
            }
        }
        return null;
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
     * @param deadline When the run must stop, or {@code null} if it has none
     * @throws RuleExecutionException if the run must stop
     */
    private void checkNotCancelled(CompiledRule rule, Instant deadline) {
        RuleExecutionException stop = cancellation(rule, BEFORE_RULE, deadline, null);
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
     * @param deadline When the run must stop, or {@code null} if it has none
     * @param thrown   What the expression threw, or {@code null}: when a run it started stopped for the same reason,
     *                 that run logged the stop, and it isn't logged again
     * @return The exception, or {@code null}
     */
    private RuleExecutionException cancellation(CompiledRule rule, String stage, Instant deadline, Throwable thrown) {
        // isInterrupted(), not interrupted(): the status stays set, so an executor shutting down still sees it.
        if (Thread.currentThread().isInterrupted()) {
            return cancelled("run() was interrupted " + stage + " rule '" + rule.displayName() + "'",
                    new InterruptedException(), null, thrown);
        }
        if (Cancellation.hasPassed(deadline)) {
            return cancelled("run() passed its deadline of " + deadline + " " + stage + " rule '" + rule.displayName()
                    + "'", Cancellation.timedOut(deadline), deadline, thrown);
        }
        return null;
    }

    /**
     * Reports a condition or action that threw as its rule's failure.
     *
     * @param snapshot The listeners the rule's callbacks went to
     * @param rule     The rule whose expression threw
     * @param kind     Whether the condition or the action threw
     * @param thrown   What it threw
     * @return The exception to throw
     */
    private RuleExecutionException expressionFailure(List<RuleListener> snapshot, CompiledRule rule,
                                                     ExpressionKind kind, Throwable thrown) {
        String what = kind == ExpressionKind.CONDITION ? "Failed to evaluate condition" : "Failed to execute action";
        return failure(snapshot, rule, kind, what + " for rule '" + rule.displayName() + "': "
                + Failures.describe(thrown), thrown);
    }

    /**
     * Decides what a condition or action that threw means. When the run has been cancelled by then, the throw is
     * taken as the expression giving up, as a run started from inside it does when it stops at the deadline it
     * inherited, so the run stops the way a cancelled run always does: no rule name, logged at WARN, with an
     * {@link InterruptedException} or a {@link TimeoutException} as the cause and what the expression threw kept as a
     * suppressed exception. The rule's open callback is closed with {@code onError}. When the run hasn't been
     * cancelled, the rule failed; an {@link Error} in what the expression threw makes that the answer even when it
     * has, because the code being run broke rather than gave up. Either way a fatal {@link Error} in what it threw
     * is rethrown as for any failure.
     *
     * @param snapshot The listeners the rule's callbacks went to
     * @param rule     The rule whose expression threw
     * @param deadline When the run must stop, or {@code null} if it has none
     * @param thrown   What the expression threw
     * @param failed   Reports the rule's failure, when the run wasn't cancelled or {@code thrown} has an
     *                 {@link Error} anywhere in its cause chain
     * @return The exception to throw
     */
    private RuleExecutionException stoppedOrFailed(List<RuleListener> snapshot, CompiledRule rule, Instant deadline,
                                                   Throwable thrown, Supplier<RuleExecutionException> failed) {
        // An interrupt the expression caught and wrapped is put back first, so it counts as one here too, and an
        // Error inside what it threw is the rule's failure as it always is, cancelled or not.
        Failures.keepInterruptStatus(thrown);
        RuleExecutionException stop = Failures.errorInChain(thrown) == null
                ? cancellation(rule, DURING_RULE, deadline, thrown) : null;
        if (stop == null) {
            return failed.get();
        }
        stop.addSuppressed(thrown);
        return closedWithStop(snapshot, rule, stop);
    }

    /**
     * Stops a run that was cancelled while a condition or action ran, once that expression has returned, so a run
     * past its deadline or interrupted never returns a result, even when the expression was its last one.
     *
     * @param snapshot    The listeners the rule's callbacks went to
     * @param rule        The rule whose expression returned
     * @param kind        Whether the condition or the action returned
     * @param deadline    When the run must stop, or {@code null} if it has none
     * @param wrongResult Why what the expression returned would have failed the rule, or {@code null} if it wouldn't.
     *                    A stopped run keeps it as a suppressed exception, as it keeps what an expression threw.
     * @throws RuleExecutionException if the run was cancelled
     */
    private void stopIfCancelled(List<RuleListener> snapshot, CompiledRule rule, ExpressionKind kind,
                                 Instant deadline, String wrongResult) {
        RuleExecutionException stop = cancellation(rule, DURING_RULE, deadline, null);
        if (stop != null) {
            if (wrongResult != null) {
                stop.addSuppressed(new RuleExecutionException(wrongResult, null, rule.rule().getRuleName(), kind));
            }
            throw closedWithStop(snapshot, rule, stop);
        }
    }

    /**
     * Closes the rule's open {@code before*} callback with {@code onError} and the exception a stopped run throws.
     *
     * @param snapshot The listeners the rule's callbacks went to
     * @param rule     The rule the run stopped in
     * @param stop     The exception the run stops with
     * @return {@code stop}, to throw
     */
    private RuleExecutionException closedWithStop(List<RuleListener> snapshot, CompiledRule rule,
                                                  RuleExecutionException stop) {
        Error fatal = notifyListeners(snapshot, "onError", listener -> listener.onError(rule.rule(), stop));
        if (fatal != null) {
            stop.addSuppressed(fatal);
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
    private RuleExecutionException cancelled(String msg, Exception cause, Instant deadline, Throwable thrown) {
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
     * @param outputFactory The factory supplied to the engine's constructor
     * @return The new output object, never {@code null}
     * @throws RuleExecutionException if the factory throws or returns {@code null}. A {@link VirtualMachineError}
     *                                other than {@link StackOverflowError} is logged, then rethrown unchanged, also
     *                                when it is the cause of what the factory throws; any other {@link Error}, and a
     *                                {@link Throwable} that is neither an exception nor an error, is wrapped like an
     *                                exception.
     */
    O createOutput(Supplier<O> outputFactory) {
        O output;
        try {
            output = outputFactory.get();
        } catch (Throwable e) {
            Failures.keepInterruptStatus(e);
            String msg = "Output factory threw " + Failures.describeWithClass(e);
            log.error(msg);
            Failures.throwIfPresent(Failures.fatalError(e));
            throw new ReportedFailure(msg, e);
        }
        if (output == null) {
            String msg = "Output factory returned null. It must return a new output object on every call.";
            log.error(msg);
            throw new ReportedFailure(msg, null);
        }
        return output;
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
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeEvaluate", listener -> listener.beforeEvaluate(rule.rule(), listenerFacts));

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing. Always with
        // evaluateWithDetail, never evaluate, or the detail of a language that explains its conditions is lost.
        ConditionResult condition;
        try {
            condition = rule.compiledCondition().evaluateWithDetail(facts.evaluation(),
                    copy.sessions().get(rule.language()));
        } catch (Throwable t) {
            throw stoppedOrFailed(snapshot, rule, facts.deadline(), t, () -> expressionFailure(snapshot, rule,
                    ExpressionKind.CONDITION, t));
        }
        // Unboxing a null here would surface as an internal NPE naming MVEL's own
        // signature, which tells the caller nothing about their rule. So would reading a null result.
        Object evaluated = condition == null ? null : condition.value();
        String wrongResult = evaluated instanceof Boolean ? null : "Condition for rule '" + rule.displayName()
                + (condition == null ? "' returned no result from evaluateWithDetail" : "' evaluated to "
                + (evaluated == null ? "null" : "a " + evaluated.getClass().getName()))
                + ". A condition expression must evaluate to a boolean.";
        stopIfCancelled(snapshot, rule, ExpressionKind.CONDITION, facts.deadline(), wrongResult);
        if (!(evaluated instanceof Boolean result)) {
            throw failure(snapshot, rule, ExpressionKind.CONDITION, wrongResult, null);
        }

        notifyAfter(snapshot, rule, "afterEvaluate",
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
     * the error while closing a stop, which {@link #closedWithStop} recorded for {@code onRunError}; otherwise the
     * rule failed.
     *
     * @return {@link RuleEvent#STOPPED} or {@link RuleEvent#FAILED}
     */
    private String fatalOutcome() {
        return ReportedFailure.isStop(fatalFailure.get()) ? RuleEvent.STOPPED : RuleEvent.FAILED;
    }

    private O executeAction(CompiledRule rule, RuleSet.Copy copy, O outputResult, RunFacts facts) {
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeExecute", listener -> listener.beforeExecute(rule.rule(), outputResult));

        // The context gives the action a read-only view: an action changes the output object, never the facts other
        // rules see.
        // Not shared like the evaluation context: a first-match engine builds a fresh output object per rule.
        ActionContext context = new EngineActionContext(facts.values(), outputResult, facts.deadline());
        ActionResult result;
        try {
            result = rule.compiledAction().execute(context, copy.sessions().get(rule.language()));
        } catch (Throwable t) {
            throw stoppedOrFailed(snapshot, rule, facts.deadline(), t, () -> expressionFailure(snapshot, rule,
                    ExpressionKind.ACTION, t));
        }
        String wrongResult = result != null ? null : "Action for rule '" + rule.displayName()
                + "' returned no result. An action returns ActionResult.done() or ActionResult.set(...).";
        // Before the properties it returned are set: a run past its deadline changes the output no further.
        stopIfCancelled(snapshot, rule, ExpressionKind.ACTION, facts.deadline(), wrongResult);
        if (result == null) {
            throw failure(snapshot, rule, ExpressionKind.ACTION, wrongResult, null);
        }
        for (Map.Entry<String, Object> property : result.properties().entrySet()) {
            setProperty(snapshot, rule, outputResult, property.getKey(), property.getValue());
        }

        notifyAfter(snapshot, rule, "afterExecute", listener -> listener.afterExecute(rule.rule(), outputResult));

        return outputResult;
    }

    /**
     * Sets one property an action returned on the output object. A failure fails the rule like a failing action.
     *
     * @throws RuleExecutionException if the property can't be set
     */
    private void setProperty(List<RuleListener> snapshot, CompiledRule rule, O output, String property, Object value) {
        try {
            outputWriter.set(output, property, value);
        } catch (InvocationTargetException e) {
            // A writer of its own may throw one with no cause, or one of its own whose getCause() throws.
            Throwable cause = Failures.causeOf(e);
            throw propertyFailure(snapshot, rule, property, cause != null ? cause : e);
        } catch (Throwable e) {
            throw propertyFailure(snapshot, rule, property, e);
        }
    }

    private RuleExecutionException propertyFailure(List<RuleListener> snapshot, CompiledRule rule, String property,
                                                   Throwable cause) {
        return failure(snapshot, rule, ExpressionKind.ACTION, "Failed to set '" + Failures.quote(property)
                + "' on the output for rule '" + rule.displayName() + "': " + Failures.describe(cause), cause);
    }

    /**
     * Returns the listeners for one condition evaluation or one action: the ones the engine was built with, which
     * can't change.
     *
     * @return The listeners
     */
    private List<RuleListener> listenerSnapshot() {
        return listeners;
    }

    /**
     * Calls a {@code before*} callback on every listener. If one throws a fatal {@link Error}, the condition or action
     * doesn't run: every listener gets {@link RuleListener#onError} to close the callback it received, and the error
     * is rethrown.
     */
    private void notifyBefore(List<RuleListener> snapshot, CompiledRule rule, String callback,
                              Consumer<RuleListener> call) {
        Error fatal = notifyListeners(snapshot, callback, call);
        if (fatal != null) {
            RuleExecutionException failure = new RuleExecutionException(listenerFatalMessage(fatal, callback, rule),
                    fatal, rule.rule().getRuleName());
            // Already on its way out of run(), so a second fatal error from onError can't replace it, but it's kept.
            keepSecondFatal(failure, reportFailure(snapshot, rule, failure, true));
            fatalFailure.set(failure);
            throw fatal;
        }
    }

    /**
     * Calls an {@code after*} callback on every listener, then logs the first fatal {@link Error} one threw at ERROR
     * and rethrows it. Every listener already closed its callback, so none gets {@code onError}.
     */
    private void notifyAfter(List<RuleListener> snapshot, CompiledRule rule, String callback,
                             Consumer<RuleListener> call) {
        Error fatal = notifyListeners(snapshot, callback, call);
        if (fatal != null) {
            String msg = listenerFatalMessage(fatal, callback, rule);
            log.error(msg);
            throw fatal;
        }
    }

    private static String listenerFatalMessage(Error fatal, String callback, CompiledRule rule) {
        return "A listener threw " + fatal.getClass().getName() + " in " + callback + " for rule '"
                + rule.displayName() + "'";
    }

    /**
     * Calls every listener in {@code snapshot}, logging what a listener throws so a faulty listener can't interrupt a
     * run. A fatal {@link Error} (see {@link Failures#fatalError}), thrown or found among the causes of what a listener
     * throws, doesn't stop the other listeners either, so each still gets the callback, and closes whatever it opened;
     * the error is returned for the caller to rethrow. A second fatal error in the same callback is logged like an
     * exception.
     *
     * @return The first fatal {@link Error} a listener threw, or {@code null}
     */
    private Error notifyListeners(List<RuleListener> snapshot, String callback, Consumer<RuleListener> call) {
        return notifyListeners(snapshot, callback, call, null);
    }

    /**
     * Calls every listener, ignoring what the run already reports: a listener that rethrows the reported exception, or
     * the fatal {@link Error} in it, has added nothing, so it doesn't count as the first fatal error, whichever
     * listener rethrows it. What a listener wrapped it in is still logged, because its own message says something.
     *
     * @param reported The exception listeners were told about, whose fatal {@link Error} the run is already
     *                 reporting, or {@code null}
     * @return The first fatal {@link Error} a listener threw that the run isn't reporting, or {@code null}
     */
    // Rethrowing the very same instance is what makes it nothing new; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private Error notifyListeners(List<RuleListener> snapshot, String callback, Consumer<RuleListener> call,
                                  RuleExecutionException reported) {
        Error reportedFatal = reported == null ? null : Failures.fatalError(reported);
        Error fatal = null;
        for (RuleListener listener : snapshot) {
            try {
                call.accept(listener);
            } catch (Throwable e) {
                Failures.keepInterruptStatus(e);
                Error found = Failures.fatalError(e);
                if (found != null && found == reportedFatal) {
                    if (e != reported && e != reportedFatal) {
                        logListenerException(callback, e);
                    }
                } else if (found != null && fatal == null) {
                    fatal = found;
                } else {
                    // A non-fatal exception, or a second fatal error in this callback, which the caller can't rethrow.
                    logListenerException(callback, e);
                }
            }
        }
        return fatal;
    }

    /**
     * Logs what a listener threw, where it isn't the error {@code run()} goes on to throw.
     *
     * @param callback The callback the listener threw from
     * @param thrown   What it threw
     */
    private static void logListenerException(String callback, Throwable thrown) {
        // Escaped, like every message the engine logs: a listener's message can quote request data. The stack trace,
        // which prints the message as it is, goes to DEBUG for whoever debugs the listener; it's left out if printing
        // it throws, as a listener's own exception can.
        log.warn("Listener threw exception in {}: {}", callback, Failures.describeWithClass(thrown));
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
     * {@link RuleListener#onRunError} finds it. The rethrown error isn't changed.
     *
     * @param reported     The exception every listener's {@code onError} got
     * @param fromListener The fatal error a listener threw from {@code onError}, or {@code null}
     */
    private static void keepSecondFatal(RuleExecutionException reported, Error fromListener) {
        if (fromListener != null) {
            reported.addSuppressed(fromListener);
            // Says which one onRunError can see: the loop has already logged any later fatal error.
            log.warn("Listener threw exception in onError, kept on the failure: {}",
                    Failures.describeWithClass(fromListener));
            logStackTrace(() -> log.debug("Listener threw exception in onError", fromListener));
        }
    }

    /**
     * Logs a run-time failure and tells every listener through {@link RuleListener#onError}, so each
     * {@code before*} callback still gets a closing call. An interrupt in {@code cause} sets the thread's interrupt
     * status again. Returns the exception for the caller to throw, unless
     * the cause is or wraps a fatal {@link Error}, which is rethrown unchanged once listeners have been told, or a
     * listener threw a fatal error from {@code onError}, which is rethrown once every listener has been told.
     */
    private RuleExecutionException failure(List<RuleListener> snapshot, CompiledRule rule, ExpressionKind kind,
                                           String msg, Throwable cause) {
        RuleExecutionException error = new ReportedFailure(msg, cause, rule.rule().getRuleName(), kind);
        Failures.keepInterruptStatus(cause);
        // A failed run() started by this rule has already logged its failure.
        Error listenerFatal = reportFailure(snapshot, rule, error, Failures.nestedRunFailure(cause) == null);
        // A fatal error in what the rule threw comes first; one from a listener's onError is rethrown otherwise.
        Error fatal = Failures.fatalError(cause);
        if (fatal == null && listenerFatal != null) {
            // Not among the causes, so onRunError can still find it on the failure.
            error.addSuppressed(listenerFatal);
            fatal = listenerFatal;
        } else if (listenerFatal != null) {
            keepSecondFatal(error, listenerFatal);
        }
        if (fatal != null) {
            fatalFailure.set(error);
            throw fatal;
        }
        return error;
    }

    /**
     * Logs a failure at ERROR, unless told not to, and tells every listener through {@link RuleListener#onError}.
     *
     * @return The first fatal {@link Error} a listener threw from {@code onError}, or {@code null}
     */
    private Error reportFailure(List<RuleListener> snapshot, CompiledRule rule, RuleExecutionException error,
                                boolean logged) {
        if (logged) {
            log.error(error.getMessage());
        }
        return notifyListeners(snapshot, "onError", listener -> listener.onError(rule.rule(), error), error);
    }

    /**
     * Logs a rejected rule list or a failure to compile at ERROR, as the engine logs every failure it throws. An
     * interrupt in {@code cause} sets the thread's interrupt status again. Then rethrows the fatal {@link Error} in
     * {@code cause}'s cause chain, if there is one (see {@link Failures#fatalError}), or returns the exception for the
     * caller to throw.
     *
     * @param msg      What failed, naming the rule or the language
     * @param cause    What the expression language threw, or {@code null}
     * @param ruleName The name of the rule that failed, or {@code null} if the failure isn't about one rule
     * @return The exception to throw, caused by {@code cause}
     */
    private static RuleCompilationException compilationFailure(String msg, Throwable cause, String ruleName) {
        return compilationFailure(msg, cause, ruleName, null, List.of());
    }

    /**
     * Logs a failure of a rule's condition or action, as {@link #compilationFailure(String, Throwable, String)} does.
     *
     * @param msg      What failed, naming the rule
     * @param cause    What the expression language threw, or {@code null}
     * @param ruleName The name of the rule that failed, or {@code null} if it has none
     * @param kind     Whether the condition or the action failed
     * @param issues   Where and what the language found wrong
     * @return The exception to throw, caused by {@code cause}
     */
    // Not logged here: load() logs each failure as it collects it, and validate() logs nothing. A fatal error is
    // the exception: it's logged, then rethrown, whichever is compiling.
    private static RuleCompilationException compilationFailure(String msg, Throwable cause, String ruleName,
                                                               ExpressionKind kind,
                                                               List<InvalidExpressionException.Issue> issues) {
        Failures.keepInterruptStatus(cause);
        Error fatal = Failures.fatalError(cause);
        if (fatal != null) {
            log.error(msg);
            throw fatal;
        }
        return new RuleCompilationException(msg, cause, ruleName, kind, issues);
    }

    /**
     * Throws the one failure there is, or one exception for several, whose message lists each. The message counts
     * rules when every failure is a rule's, and failures otherwise: a language that couldn't create its compiler and a
     * rejected declared fact name have no rule. Every failure was logged when it happened.
     *
     * @param failures The failures, in the order they were found
     * @throws RuleCompilationException if there are any
     */
    private static void throwIfAnyFailed(List<RuleCompilationException> failures) {
        if (failures.isEmpty()) {
            return;
        }
        throw failures.size() == 1 ? failures.get(0) : combined(failures);
    }

    private static RuleCompilationException combined(List<RuleCompilationException> failures) {
        String what = failures.stream().allMatch(failure -> failure.getRuleName() != null)
                ? " rules failed to compile: "
                : " failures while loading the rules: ";
        return new RuleCompilationException(failures.size() + what
                + failures.stream().map(Throwable::getMessage).collect(Collectors.joining("; ")), failures);
    }

    private String languageOf(Rule rule) {
        return rule.getLanguage() != null ? rule.getLanguage() : languages.defaultLanguage();
    }

    /**
     * Creates a language's compiler for one rule list. A language that throws or returns {@code null} fails the rule
     * list, like an expression that doesn't compile.
     *
     * @param name     The language's name
     * @param language The language
     * @param context  The imports and class loader the rule list is compiled with
     * @return The compiler
     * @throws RuleCompilationException if the language throws or returns {@code null}
     */
    private static ExpressionCompiler newCompiler(String name, ExpressionLanguage language, CompileContext context) {
        ExpressionCompiler compiler;
        try {
            compiler = language.newCompiler(context);
        } catch (Throwable e) {
            throw compilationFailure("The '" + Failures.quote(name) + "' expression language failed to create a "
                    + "compiler: " + Failures.describe(e), e, null);
        }
        if (compiler == null) {
            throw compilationFailure("The '" + Failures.quote(name) + "' expression language returned no compiler",
                    null, null);
        }
        return compiler;
    }

    private CompiledRule compileRule(Rule rule, ExpressionCompiler compiler, LanguageCompilers compilers) {
        String ruleName = rule.getRuleName();
        String displayName = Failures.quote(ruleName);
        if (rule.getCondition().isBlank()) {
            throw compilationFailure("Rule '" + displayName + "' has a blank condition expression", null,
                    ruleName, ExpressionKind.CONDITION, List.of());
        }
        if (rule.getAction().isBlank()) {
            throw compilationFailure("Rule '" + displayName + "' has a blank action expression", null,
                    ruleName, ExpressionKind.ACTION, List.of());
        }
        String language = languageOf(rule);
        if (compiler == null) {
            throw compilationFailure("Rule '" + displayName + "' is written in '" + Failures.quote(language)
                    + "', which isn't one of the engine's expression languages: " + compilers.languageNames(), null,
                    ruleName);
        }
        CompiledCondition compiledCondition = compile(
                new Expression(ruleName, ExpressionKind.CONDITION, rule.getCondition()), compiler::compileCondition);
        CompiledAction compiledAction = compile(
                new Expression(ruleName, ExpressionKind.ACTION, rule.getAction()), compiler::compileAction);
        return new CompiledRule(rule, displayName, language, compiledCondition, compiledAction);
    }

    /**
     * Compiles one condition or action. An expression the language rejects is reported with the language's reason and
     * issues, or with none if its exception can't give them (see {@link Failures#issuesOf}); anything else the
     * language throws, such as a syntax error it doesn't point to, becomes the cause of the failure. A fatal
     * {@link Error}, also one the language wraps in its own exception, is logged like any failure and then rethrown.
     *
     * @param source      The expression to compile
     * @param compilation Compiles it with the rule's language
     * @param <T>         The type of compiled expression
     * @return The compiled expression
     * @throws RuleCompilationException if the expression doesn't compile, or the language returns {@code null}
     */
    private static <T> T compile(Expression source, Function<Expression, T> compilation) {
        String expression = Failures.expression(source.kind(), source.ruleName());
        T compiled;
        try {
            compiled = compilation.apply(source);
        } catch (InvalidExpressionException e) {
            // A language's own subclass may have a getMessage() or an issues() that throws.
            String reason = Failures.escape(Failures.truncate(
                    Failures.messageOr(e, "was rejected by its expression language")));
            throw compilationFailure(expression + " " + reason, e, source.ruleName(), source.kind(),
                    Failures.issuesOf(e));
        } catch (Throwable e) {
            // MVEL's parser recurses once per operator, so a very long expression overflows the stack.
            String reason = Failures.rootCause(e) instanceof StackOverflowError
                    ? "the expression is too long or too deeply nested to compile"
                    : Failures.describe(e);
            throw compilationFailure(expression + " failed to compile: " + reason, e, source.ruleName(), source.kind(),
                    List.of());
        }
        if (compiled == null) {
            throw compilationFailure(expression + " wasn't compiled: its expression language returned null", null,
                    source.ruleName(), source.kind(), List.of());
        }
        return compiled;
    }
}
