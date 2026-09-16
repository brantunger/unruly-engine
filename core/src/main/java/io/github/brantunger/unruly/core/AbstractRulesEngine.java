package io.github.brantunger.unruly.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
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
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RuleSetInfo;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RunContext;
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
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

/**
 * The AbstractRulesEngine is an abstract implementation of the
 * {@link RulesEngine} interface. The RulesEngine fires the
 * action expression from a list of {@link Rule} objects when their conditions
 * evaluate to <strong>true</strong>.
 *
 * <p>
 * <b>MVEL optimizer:</b> the engine leaves MVEL's global optimizer setting alone. Concurrent
 * {@code run()} calls never share a session, and MVEL's session holds the run's own compiled expressions, because MVEL
 * replaces the accessors cached in one without synchronization when a fact's runtime class changes, so they are safe
 * with any optimizer, including MVEL's default JIT optimizer.
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
    // How long a run may take, or null if runs have no deadline. A run() call can pass one of its own.
    private final Duration runTimeout;
    // The output type languages are told about, and what sets the properties actions return.
    private final Class<?> outputType;
    private final OutputWriter<? super O> outputWriter;
    // Each language's options, by language name.
    private final Map<String, Map<String, String>> options;
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
        this.runTimeout = configuration.runTimeout();
        this.outputType = configuration.outputType();
        this.outputWriter = configuration.outputWriter();
        this.options = configuration.options();
    }

    /**
     * Returns the rules as {@link #load(List)} compiled them, or {@code null} if it has not been called or the
     * engine is closed. Every run shares them, each with its own sessions, from
     * {@link #runInScope(FactStore, Duration, RunBody)}.
     *
     * @return An unmodifiable list of compiled rules, or {@code null}
     */
    // null, not an empty list: an empty rule list is loaded, and null means none is.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    List<CompiledRule> getCompiledRules() {
        RuleSet rules = ruleSet;
        return rules != null ? rules.rules() : null;
    }

    /**
     * Calls {@code run} with the rules and a copy of them, one session for each language, that no concurrent run is
     * using, and gives the copy back once {@code run} returns or throws. The first run after {@link #load(List)},
     * and a run that starts while every copy is in use, makes a new copy. An engine created with a limit on copies
     * keeps at most that many: a run that finds all of them in use waits for one, unless it is nested in another run on
     * the same thread, which gets an extra copy that isn't kept. MVEL's compiled expressions aren't safe to share
     * between threads when a fact name is bound to different kinds of objects; see {@link RuleSet}. A run that reads a
     * rule set just as a reload or {@link #close()} closes it reads the rules again.
     *
     * <p>
     * A missing call to {@link #load(List)} (e.g. a forgotten {@code @PostConstruct}) used to make every run
     * return {@code null}, indistinguishable from "no rule matched", so it is reported instead.
     * </p>
     *
     * @param run The body of a run, given the rule set, whose rules are in priority order, possibly none, and the copy
     * @param <T> The type {@code run} returns
     * @return What {@code run} returns
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
     * @throws RuleExecutionException if a new copy is needed and a language throws or returns {@code null} from
     *                                {@code newSession()}, or if the thread is interrupted while it waits for a copy,
     *                                which keeps its interrupt status set. Each is logged at ERROR; a fatal
     *                                {@link Error} from {@code newSession()} is then rethrown unchanged.
     */
    /** The body of one run: what the engine does with the rules, its copy of them and the run's facts. */
    @FunctionalInterface
    interface RunBody<O> {

        /**
         * Runs the rules.
         *
         * @param rules    The rule set the run uses
         * @param copy     The run's copy of the rules
         * @param facts    The run's fact values, already checked
         * @param deadline When the run must stop, or {@code null} if it has none
         * @return What the run did
         */
        RunResult<O> run(RuleSet rules, RuleSet.Copy copy, Map<String, Object> facts, Instant deadline);
    }

    /**
     * Fires the rules against {@code facts}, with the timeout the engine was built with, if any.
     *
     * @param facts {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public final RunResult<O> runWithResult(FactStore<?> facts) {
        return runRules(facts, runTimeout);
    }

    /**
     * Fires the rules against {@code facts}, giving this run {@code timeout} instead of the engine's.
     *
     * @param facts   {@inheritDoc}
     * @param timeout {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public final RunResult<O> runWithResult(FactStore<?> facts, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("timeout must be positive, but was " + timeout);
        }
        return runRules(facts, timeout);
    }

    /**
     * Fires the rules the way this engine fires them.
     *
     * @param facts   The facts the run was given
     * @param timeout How long the run may take, or {@code null} if it has no deadline
     * @return What the run did
     */
    abstract RunResult<O> runRules(FactStore<?> facts, Duration timeout);

    /**
     * Runs one run inside its listener scope: every listener gets {@link RuleListener#beforeRun}, then the rule
     * callbacks, then exactly one of {@link RuleListener#afterRun} and {@link RuleListener#onRunError}.
     *
     * <p>
     * The fact values are collected before the run borrows a copy of the rules, so the scope can carry them, and their
     * names are checked inside the scope, so a name no language can refer to reaches {@code onRunError}. A run that
     * waits for a copy opens its scope when the wait ends; an interrupt while waiting opens and closes a scope of its
     * own. Failing because no rules are loaded, or because the engine is closed, is misuse and reaches no listener.
     * </p>
     *
     * @param facts   The facts the run was given
     * @param timeout How long the run may take, or {@code null} if it has no deadline. The deadline is taken from
     *                when the run starts, so waiting for a copy of the rules counts towards it.
     * @param body    What the engine does once it holds a copy of the rules
     * @return What the run did
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
     */
    RunResult<O> runInScope(FactStore<?> facts, Duration timeout, RunBody<O> body) {
        Objects.requireNonNull(facts, "facts must not be null");
        Instant deadline = Cancellation.deadlineFrom(timeout);
        Map<String, Object> values = factValues(facts);
        Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(values);
        RuleSet rules;
        RuleSet.Copy copy;
        do {
            rules = currentRules();
            copy = borrow(rules, listenerFacts);
        } while (copy == null);
        List<RuleListener> snapshot = listenerSnapshot();
        RunContext parent = currentRun.get();
        EngineRunContext run = newRun(rules, listenerFacts, parent);
        currentRun.set(run);
        try {
            notifyRun(snapshot, "beforeRun", listener -> listener.beforeRun(run));
            RunResult<O> result;
            try {
                checkFactNames(values, rules.factChecks());
                result = body.run(rules, copy, values, deadline);
            } catch (RuntimeException e) {
                notifyRun(snapshot, "onRunError", listener -> listener.onRunError(run, e));
                throw e;
            } catch (Error e) {
                // run() rethrows the error itself; listeners see what it failed with.
                RuleExecutionException wrapped = new RuleExecutionException("The run failed with " + e, e);
                notifyRun(snapshot, "onRunError", listener -> listener.onRunError(run, wrapped));
                throw e;
            }
            notifyRun(snapshot, "afterRun", listener -> listener.afterRun(run, result));
            return result;
        } finally {
            if (parent == null) {
                currentRun.remove();
            } else {
                currentRun.set(parent);
            }
            rules.release(copy);
        }
    }

    /** Creates the context one run is reported to listeners with. */
    private EngineRunContext newRun(RuleSet rules, Map<String, Object> listenerFacts, RunContext parent) {
        return new EngineRunContext(runIds.incrementAndGet(), parent, matchPolicy(), rules.checksum(), listenerFacts);
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
     * @return {@code "firstMatch"} or {@code "allMatches"}
     */
    abstract String matchPolicy();

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
     * Borrows a copy of the rules for one run. An interrupt while waiting for one fails the run; the interrupt status
     * is set again, so the caller still sees it. A thread whose status is already set doesn't wait, and gets a free
     * copy: the run then stops at its first rule, the same way it does without a limit on copies.
     *
     * @param rules The rule set to borrow from
     * @return The copy, to give back with {@link RuleSet#release(RuleSet.Copy)}
     * @throws RuleExecutionException if the thread is interrupted while it waits for a copy
     */
    private RuleSet.Copy borrow(RuleSet rules, Map<String, Object> listenerFacts) {
        try {
            return rules.borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String msg = "run() was interrupted while waiting for a compiled copy of the rules: all " + rules.limit()
                    + " were in use";
            // WARN, like the check between rules: a run the caller stopped isn't the rules or the engine failing.
            log.warn(msg);
            RuleExecutionException failure = new RuleExecutionException(msg, e);
            // The run never started, so it opens and closes a scope of its own for listeners.
            List<RuleListener> snapshot = listenerSnapshot();
            EngineRunContext run = newRun(rules, listenerFacts, currentRun.get());
            notifyRun(snapshot, "beforeRun", listener -> listener.beforeRun(run));
            notifyRun(snapshot, "onRunError", listener -> listener.onRunError(run, failure));
            throw failure;
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
     * Every rule is compiled before a failure is thrown, so one {@link RuleCompilationException} reports every broken
     * rule: its {@code failures()} has each rule's failure, and its message lists them. A failure that isn't about one
     * rule, such as a {@code null} rule, a duplicate name or a language that can't create its compiler, is thrown at
     * once.
     * </p>
     *
     * <p>
     * The rule list it replaces is closed once no run is using it: its languages' sessions and compilers are closed. A
     * rule list that fails to load closes the compilers it created.
     * </p>
     *
     * @param ruleList The List of {@link Rule} objects to compile.
     * @throws RuleCompilationException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     * @throws IllegalStateException if the engine is closed
     */
    // The rule set closes the compilers, or this method does if the rule list fails to load.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void load(List<Rule> ruleList) {
        Objects.requireNonNull(ruleList, "ruleList must not be null");
        // Checked before sorting, which would otherwise fail with a bare NPE from Rule::getPriority.
        Set<String> ruleNames = new HashSet<>();
        for (int i = 0; i < ruleList.size(); i++) {
            Rule rule = ruleList.get(i);
            if (rule == null) {
                throw compilationFailure("Rule at index " + i + " of the rule list is null", null, null);
            }
            // Duplicate names would make error messages, exceptions and listener logs ambiguous.
            if (!ruleNames.add(rule.getRuleName())) {
                throw compilationFailure("Duplicate rule name '" + Failures.quote(rule.getRuleName()) + "'", null,
                        rule.getRuleName());
            }
        }
        ClassLoader loader = ImportResolver.contextClassLoader();
        // Each language gets its own options.
        LanguageCompilers compilers = new LanguageCompilers(languages.languages(),
                (name, language) -> newCompiler(name, language, new EngineCompileContext(packageImports, classImports,
                        loader, outputType, options.getOrDefault(name, Map.of()))));
        RuleSet loaded;
        try {
            List<Rule> sorted = ruleList.stream()
                    .sorted(Comparator.comparing(
                            Rule::getPriority,
                            Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
            List<CompiledRule> compiled = new ArrayList<>();
            List<RuleCompilationException> failures = new ArrayList<>();
            for (Rule rule : sorted) {
                // A language that can't create its compiler fails the rule list at once, not every rule written in it.
                ExpressionCompiler compiler = compilers.forLanguage(languageOf(rule));
                try {
                    compiled.add(compileRule(rule, compiler, compilers));
                } catch (RuleCompilationException e) {
                    failures.add(e);
                }
            }
            throwIfAnyFailed(failures);
            loaded = new RuleSet(compiled, compilers.used(languages.defaultLanguage()), copyLimit);
        } catch (RuntimeException | Error e) {
            Closing.compilers(compilers.created());
            throw e;
        }
        RuleSet replaced;
        synchronized (lifecycle) {
            if (closed) {
                loaded.retire();
                throw new IllegalStateException(CLOSED_MESSAGE);
            }
            replaced = ruleSet;
            ruleSet = loaded;
        }
        if (replaced != null) {
            replaced.retire();
        }
    }

    /**
     * Closes the engine. The rule list is closed once no run is using it: runs in progress finish, their sessions are
     * closed as each one returns, and the languages' compilers after the last one. Afterwards, {@code run()} and
     * {@link #load(List)} throw {@link IllegalStateException}. Closing it again does nothing.
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
            replaced.retire();
        }
    }

    /**
     * Collects the fact values a run was given, without checking their names, so the run's listeners can be given the
     * facts before the names are checked.
     *
     * @param facts The key/value fact store
     * @return A map of fact names to their values, which may hold a {@code null} name a custom store allowed
     */
    private static Map<String, Object> factValues(FactStore<?> facts) {
        Map<String, Object> entryMap = new HashMap<>();
        for (Map.Entry<String, ? extends FactReference<?>> entry : facts.asMap().entrySet()) {
            // A null reference is bound as null, like a Fact holding null. Skipping it left the name
            // unresolvable, so `x == null` failed instead of matching.
            FactReference<?> fact = entry.getValue();
            entryMap.put(entry.getKey(), fact != null ? fact.getValue() : null);
        }
        return entryMap;
    }

    /**
     * Checks every fact name of a run: not {@code null}, not the output's name, and one the language of each loaded
     * rule can refer to.
     *
     * @param values The fact values by name
     * @param checks The compilers of the rule list the run uses, which check each name, by language name
     * @throws IllegalArgumentException if a fact is named {@code null} or {@code output}, or has a name a language
     *                                  can't refer to, or if a language's check of the name throws anything else
     */
    private static void checkFactNames(Map<String, Object> values, Map<String, ExpressionCompiler> checks) {
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
            checkFactName(name, checks);
        }
    }

    /**
     * Checks a fact name with the language of each rule in use. A language rejects a name with an
     * {@link IllegalArgumentException}, whose message is logged escaped, and which is thrown as is. Anything else a language throws is logged
     * and thrown as an {@code IllegalArgumentException} naming the fact and the language, except a fatal
     * {@link Error}, thrown or among the causes of what the language throws, which is rethrown after logging.
     *
     * @param name   The fact's name
     * @param checks The compilers to check it with, by language name
     * @throws IllegalArgumentException if a language rejects the name or fails to check it
     */
    private static void checkFactName(String name, Map<String, ExpressionCompiler> checks) {
        for (Map.Entry<String, ExpressionCompiler> check : checks.entrySet()) {
            try {
                check.getValue().checkFactName(name);
            } catch (IllegalArgumentException e) {
                // The language wrote this message and it names the fact, so it's escaped before it's logged. The
                // exception is thrown as it came, so a caller still reads exactly what the language said.
                log.error(Failures.describe(e));
                throw e;
            } catch (Exception | Error e) {
                Failures.keepInterruptStatus(e);
                String msg = "The '%s' expression language failed to check fact name '%s': %s"
                        .formatted(Failures.quote(check.getKey()), Failures.quote(name), Failures.describe(e));
                log.error(msg);
                Failures.throwIfPresent(Failures.fatalError(e));
                throw new IllegalArgumentException(msg, e);
            }
        }
    }

    /**
     * Evaluates the rules' conditions one after another, in list order, and keeps the rules that matched.
     *
     * @param ruleList This is a list of {@link CompiledRule} objects to filter
     *                 based on when condition expression parses to
     *                 true
     * @param copy     The run's copy of the rules, whose sessions the conditions run with
     * @param entryMap The pre-built map of unwrapped facts to use as execution context.
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return List of {@link CompiledRule} objects where their condition evaluated
     *         to <b>true</b>
     */
    List<CompiledRule> match(List<CompiledRule> ruleList, RuleSet.Copy copy, Map<String, Object> entryMap,
                             Instant deadline) {
        return ruleList.stream()
                .filter(rule -> matches(rule, copy, entryMap, deadline))
                .toList();
    }

    /**
     * Evaluates one rule's condition, telling the listeners about it.
     *
     * @param rule     The rule whose condition to evaluate
     * @param copy     The run's copy of the rules, whose sessions the condition runs with
     * @param entryMap The run's facts
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return Whether the condition was true
     * @throws RuleExecutionException if the run was cancelled before this rule
     */
    boolean matches(CompiledRule rule, RuleSet.Copy copy, Map<String, Object> entryMap, Instant deadline) {
        checkNotCancelled(rule, deadline);
        return parseCondition(rule, copy, entryMap, deadline);
    }

    /**
     * Stops a run that must not go on to {@code rule}, because its thread has been interrupted or it has passed its
     * deadline. Checked before each condition and before each action, which is as often as the engine gets control
     * back: an expression that doesn't return can only be stopped by a language that can stop inside one, through
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
        // isInterrupted(), not interrupted(): the status stays set, so an executor shutting down still sees it.
        if (Thread.currentThread().isInterrupted()) {
            throw cancelled("run() was interrupted before rule '" + rule.displayName() + "'",
                    new InterruptedException());
        }
        if (Cancellation.hasPassed(deadline)) {
            throw cancelled("run() passed its deadline of " + deadline + " before rule '" + rule.displayName() + "'",
                    new TimeoutException("The run's deadline of " + deadline + " has passed"));
        }
    }

    /**
     * Reports a run that stopped because it was cancelled. Logged at WARN, not ERROR: nothing failed, and the caller
     * asked for it, whether by interrupting the thread or by setting a timeout.
     *
     * @param msg   What to log and what the exception says
     * @param cause An {@link InterruptedException} or a {@link TimeoutException}, so a caller can tell which happened
     * @return The exception to throw, which belongs to no rule
     */
    private RuleExecutionException cancelled(String msg, Exception cause) {
        log.warn(msg);
        return new RuleExecutionException(msg, cause);
    }

    /**
     * Execute a single {@link CompiledRule} object's action field against the input
     * data
     *
     * @param rule         The rule object to obtain the action expression to fire
     *                     the rule for
     * @param copy         The run's copy of the rules, whose session the action runs with
     * @param outputObject an empty output object to set output data into
     * @param entryMap     The pre-built map of unwrapped facts to use as execution context.
     * @param deadline     When the run must stop, or {@code null} if it has none
     * @return {@code outputObject}, which the action changed in place or whose properties were set from the action's
     *         result. An action can't replace it:
     *         assigning to {@code output} fails with a {@link RuleExecutionException}, except inside a
     *         {@code def} function, where it creates a variable local to the function.
     * @throws RuleExecutionException if the run was cancelled before this rule
     */
    O executeRule(CompiledRule rule, RuleSet.Copy copy, O outputObject, Map<String, Object> entryMap,
                  Instant deadline) {
        checkNotCancelled(rule, deadline);
        return parseAction(rule, copy, outputObject, entryMap, deadline);
    }

    /**
     * Creates a fresh output object from the engine's factory. Without this, a factory that throws
     * escaped {@code run()} unwrapped, and one that returned {@code null} surfaced later as an action
     * failure blamed on whichever rule ran first.
     *
     * @param outputFactory The factory supplied to the engine's constructor
     * @return The new output object, never {@code null}
     * @throws RuleExecutionException if the factory throws or returns {@code null}. An {@link Error} other than
     *                                {@link StackOverflowError} or {@link AssertionError} is logged, then rethrown
     *                                unchanged, also when it is the cause of what the factory throws.
     */
    O createOutput(Supplier<O> outputFactory) {
        O output;
        try {
            output = outputFactory.get();
        } catch (Exception | Error e) {
            Failures.keepInterruptStatus(e);
            String msg = "Output factory threw " + e;
            log.error(msg);
            Failures.throwIfPresent(Failures.fatalError(e));
            throw new RuleExecutionException(msg, e);
        }
        if (output == null) {
            String msg = "Output factory returned null. It must return a new output object on every call.";
            log.error(msg);
            throw new RuleExecutionException(msg);
        }
        return output;
    }

    private boolean parseCondition(CompiledRule rule, RuleSet.Copy copy, Map<String, Object> entryMap,
                                   Instant deadline) {
        // The evaluation context makes its own read-only view, whose messages are about conditions, so a listener
        // that writes to the facts isn't told about conditions.
        Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(entryMap);
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeEvaluate", listener -> listener.beforeEvaluate(rule.rule(), listenerFacts));

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing.
        Object evaluated;
        try {
            evaluated = rule.compiledCondition().evaluate(new EngineEvaluationContext(entryMap, deadline),
                    copy.sessions().get(rule.language()));
        } catch (Exception | Error e) {
            throw failure(snapshot, rule, ExpressionKind.CONDITION, "Failed to evaluate condition for rule '" + rule.displayName() + "': "
                    + Failures.describe(e), e);
        }

        // Unboxing a null here would surface as an internal NPE naming MVEL's own
        // signature, which tells the caller nothing about their rule.
        if (evaluated == null) {
            throw failure(snapshot, rule, ExpressionKind.CONDITION, "Condition for rule '" + rule.displayName()
                    + "' evaluated to null. A condition expression must evaluate to a boolean.", null);
        }

        if (!(evaluated instanceof Boolean result)) {
            throw failure(snapshot, rule, ExpressionKind.CONDITION, "Condition for rule '" + rule.displayName() + "' evaluated to a "
                    + evaluated.getClass().getName() + ". A condition expression must evaluate to a boolean.", null);
        }

        notifyAfter(snapshot, rule, "afterEvaluate",
                listener -> listener.afterEvaluate(rule.rule(), listenerFacts, result));

        return result;
    }

    private O parseAction(CompiledRule rule, RuleSet.Copy copy, O outputResult, Map<String, Object> entryMap,
                          Instant deadline) {
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeExecute", listener -> listener.beforeExecute(rule.rule(), outputResult));

        // The context gives the action a read-only view: an action changes the output object, never the facts other
        // rules see.
        ActionContext context = new EngineActionContext(entryMap, outputResult, deadline);
        ActionResult result;
        try {
            result = rule.compiledAction().execute(context, copy.sessions().get(rule.language()));
        } catch (Exception | Error e) {
            throw failure(snapshot, rule, ExpressionKind.ACTION, "Failed to execute action for rule '"
                    + rule.displayName() + "': " + Failures.describe(e), e);
        }
        if (result == null) {
            throw failure(snapshot, rule, ExpressionKind.ACTION, "Action for rule '" + rule.displayName()
                    + "' returned no result. An action returns ActionResult.done() or ActionResult.set(...).", null);
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
            throw propertyFailure(snapshot, rule, property, e.getCause());
        } catch (Exception | Error e) {
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
            // Already on its way out of run(), so a second fatal error from onError can't replace it.
            reportFailure(snapshot, rule, new RuleExecutionException(listenerFatalMessage(fatal, callback, rule), fatal,
                    rule.rule().getRuleName()), true);
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
        Error fatal = null;
        for (RuleListener listener : snapshot) {
            try {
                call.accept(listener);
            } catch (Exception | Error e) {
                Failures.keepInterruptStatus(e);
                Error found = fatal == null ? Failures.fatalError(e) : null;
                if (found != null) {
                    fatal = found;
                } else {
                    log.warn("Listener threw exception in {}", callback, e);
                }
            }
        }
        return fatal;
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
        RuleExecutionException error = new RuleExecutionException(msg, cause, rule.rule().getRuleName(), kind);
        Failures.keepInterruptStatus(cause);
        // A failed run() started by this rule has already logged its failure.
        Error listenerFatal = reportFailure(snapshot, rule, error, Failures.nestedRunFailure(cause) == null);
        Failures.throwIfPresent(Failures.fatalError(cause));
        Failures.throwIfPresent(listenerFatal);
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
        return notifyListeners(snapshot, "onError", listener -> listener.onError(rule.rule(), error));
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
    private static RuleCompilationException compilationFailure(String msg, Throwable cause, String ruleName,
                                                               ExpressionKind kind,
                                                               List<InvalidExpressionException.Issue> issues) {
        log.error(msg);
        Failures.keepInterruptStatus(cause);
        Failures.throwIfPresent(Failures.fatalError(cause));
        return new RuleCompilationException(msg, cause, ruleName, kind, issues);
    }

    /**
     * Throws the failure of the one rule that failed to compile, or one exception for several, whose message lists
     * each. Every failure was logged when it happened.
     *
     * @param failures The failures, in the order the rules were compiled
     * @throws RuleCompilationException if there are any
     */
    private static void throwIfAnyFailed(List<RuleCompilationException> failures) {
        if (failures.isEmpty()) {
            return;
        }
        throw failures.size() == 1
                ? failures.get(0)
                : new RuleCompilationException(failures.size() + " rules failed to compile: "
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
        } catch (Exception | Error e) {
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
     * issues; anything else the language throws, such as a syntax error it doesn't point to, becomes the cause of the
     * failure. A fatal {@link Error}, also one the language wraps in its own exception, is logged like any failure and
     * then rethrown.
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
            String reason = e.getMessage() != null
                    ? Failures.truncate(e.getMessage())
                    : "was rejected by its expression language";
            throw compilationFailure(expression + " " + reason, e, source.ruleName(), source.kind(), e.issues());
        } catch (Exception | Error e) {
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
