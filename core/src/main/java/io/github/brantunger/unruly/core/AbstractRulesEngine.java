package io.github.brantunger.unruly.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
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
    // The smallest limit on compiled copies: one run at a time.
    private static final int MIN_COPIES = 1;
    // The language of a rule whose language is null: MVEL, which is found with ServiceLoader like any other language.
    private static final String DEFAULT_LANGUAGE = "mvel";
    private static final String CLOSED_MESSAGE = "The engine is closed";
    // The languages found with ServiceLoader, and those from registerLanguage().
    private final LanguageRegistry languages = new LanguageRegistry();
    // Packages and classes from addImport(s), passed to every compilation of the next rule list.
    private final Set<String> packageImports = new LinkedHashSet<>();
    private final Set<Class<?>> classImports = new LinkedHashSet<>();
    // Copy-on-write: callbacks iterate a snapshot, so registering a listener from another thread
    // or from inside a callback can't throw ConcurrentModificationException out of run().
    private final List<RuleListener> listeners = new CopyOnWriteArrayList<>();
    // Volatile so a setRuleList() call on one thread is seen by run() on others. The rule set holds the rules and the
    // compilers of the languages they use, and is fully built before it is assigned, so one volatile write swaps in
    // both. Assigned while holding lifecycle, so each rule set replaced is retired once, and none is assigned after
    // close().
    private volatile RuleSet ruleSet;
    private volatile boolean closed;
    private final Object lifecycle = new Object();
    // The most compiled copies of the rules that runs hold at once, or RuleSet.UNLIMITED.
    private final int maxCopies;

    /**
     * Creates an engine that makes as many compiled copies of its rules as its runs need at once.
     */
    AbstractRulesEngine() {
        this.maxCopies = RuleSet.UNLIMITED;
    }

    /**
     * Creates an engine that keeps at most {@code maxCopies} compiled copies of its rules. A run that finds all of them
     * in use waits for one; see {@link RuleSet}.
     *
     * @param maxCopies The most copies, at least 1
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     */
    AbstractRulesEngine(int maxCopies) {
        if (maxCopies < MIN_COPIES) {
            throw new IllegalArgumentException(
                    "maxCopies must be at least " + MIN_COPIES + ", but was " + maxCopies);
        }
        this.maxCopies = maxCopies;
    }

    /**
     * Returns the rules as {@link #setRuleList(List)} compiled them, or {@code null} if it has not been called or the
     * engine is closed. Every run shares them, each with its own sessions, from {@link #withCompiledRules(BiFunction)}.
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
     * using, and gives the copy back once {@code run} returns or throws. The first run after {@link #setRuleList(List)},
     * and a run that starts while every copy is in use, makes a new copy. An engine created with a limit on copies
     * keeps at most that many: a run that finds all of them in use waits for one, unless it is nested in another run on
     * the same thread, which gets an extra copy that isn't kept. MVEL's compiled expressions aren't safe to share
     * between threads when a fact name is bound to different kinds of objects; see {@link RuleSet}. A run that reads a
     * rule set just as a reload or {@link #close()} closes it reads the rules again.
     *
     * <p>
     * A missing call to {@link #setRuleList(List)} (e.g. a forgotten {@code @PostConstruct}) used to make every run
     * return {@code null}, indistinguishable from "no rule matched", so it is reported instead.
     * </p>
     *
     * @param run The body of a run, given the rule set, whose rules are in priority order, possibly none, and the copy
     * @param <T> The type {@code run} returns
     * @return What {@code run} returns
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called, or the engine is closed
     * @throws RuleExecutionException if a new copy is needed and a language throws or returns {@code null} from
     *                                {@code newSession()}, or if the thread is interrupted while it waits for a copy,
     *                                which keeps its interrupt status set. Each is logged at ERROR; a fatal
     *                                {@link Error} from {@code newSession()} is then rethrown unchanged.
     */
    <T> T withCompiledRules(BiFunction<RuleSet, RuleSet.Copy, T> run) {
        RuleSet rules;
        RuleSet.Copy copy;
        do {
            rules = currentRules();
            copy = borrow(rules);
        } while (copy == null);
        try {
            return run.apply(rules, copy);
        } finally {
            rules.release(copy);
        }
    }

    /**
     * Returns the rule set runs start with.
     *
     * @return The rule set
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called, or the engine is closed
     */
    RuleSet currentRules() {
        RuleSet rules = ruleSet;
        if (rules == null) {
            throw new IllegalStateException(closed ? CLOSED_MESSAGE : "setRuleList() must be called before run()");
        }
        return rules;
    }

    /**
     * Borrows a copy of the rules for one run. An interrupt while waiting for one fails the run; the interrupt status
     * is set again, so the caller still sees it.
     *
     * @param rules The rule set to borrow from
     * @return The copy, to give back with {@link RuleSet#release(RuleSet.Copy)}
     * @throws RuleExecutionException if the thread is interrupted while waiting for a copy
     */
    private static RuleSet.Copy borrow(RuleSet rules) {
        try {
            return rules.borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String msg = "Interrupted while waiting for a compiled copy of the rules: all " + rules.limit()
                    + " were in use";
            log.error(msg);
            throw new RuleExecutionException(msg, e);
        }
    }

    /**
     * Registers a single {@link RuleListener} to monitor rule evaluation and execution.
     *
     * @param listener The listener to register.
     * @return A reference to this {@link RulesEngine}
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public RulesEngine<O> registerListener(RuleListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        this.listeners.add(listener);
        return this;
    }

    /**
     * Registers a list of {@link RuleListener} to monitor rule evaluation and execution.
     *
     * @param listeners The list of listeners to register.
     * @return A reference to this {@link RulesEngine}
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public RulesEngine<O> registerListeners(List<RuleListener> listeners) {
        Objects.requireNonNull(listeners, "listeners must not be null");
        // Checked up front so a list with a null registers nothing. A stored null would otherwise throw
        // inside every callback, logged as a misleading "Listener threw exception" warning on every run.
        for (RuleListener listener : listeners) {
            Objects.requireNonNull(listener, "listener element must not be null");
        }
        this.listeners.addAll(listeners);
        return this;
    }

    /**
     * Set the list of rules used for processing in the Rules Engine.
     * Rules are sorted by priority in descending order (highest priority first).
     * Rules with a {@code null} priority are treated as lowest priority.
     * Rules with equal priority keep their relative order from {@code ruleList}.
     *
     * <p>
     * <b>Note:</b> Any package imports configured via {@link #addImport(String)} or
     * {@link #addImports(Set)} must be set <em>before</em> calling this method, as
     * rules are compiled with the imports registered at the time of this call.
     * </p>
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
     * Each rule is compiled by the expression language its {@link Rule#getLanguage() language} names, or by MVEL if
     * that is {@code null}. The languages are found with {@link java.util.ServiceLoader} each time this method is
     * called, with this library's class loader and the thread's context class loader, and a language registered with
     * {@link #registerLanguage(ExpressionLanguage)} replaces a found one with the same name. {@code run()} checks fact
     * names against every language the rules use.
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
    public void setRuleList(List<Rule> ruleList) {
        Objects.requireNonNull(ruleList, "ruleList must not be null");
        // Checked before sorting, which would otherwise fail with a bare NPE from Rule::getPriority.
        Set<String> ruleNames = new HashSet<>();
        for (int i = 0; i < ruleList.size(); i++) {
            Rule rule = ruleList.get(i);
            if (rule == null) {
                throw compilationFailure("Rule at index " + i + " of the rule list is null", null, null);
            }
            // Duplicate names would make error messages and listener logs ambiguous. Unnamed rules are allowed.
            if (rule.getRuleName() != null && !ruleNames.add(rule.getRuleName())) {
                throw compilationFailure("Duplicate rule name '" + Failures.quote(rule.getRuleName()) + "'", null,
                        rule.getRuleName());
            }
        }
        CompileContext context = new EngineCompileContext(packageImports, classImports,
                ImportResolver.contextClassLoader());
        LanguageCompilers compilers = new LanguageCompilers(availableLanguages(context.classLoader()),
                (name, language) -> newCompiler(name, language, context));
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
            loaded = new RuleSet(compiled, compilers.used(DEFAULT_LANGUAGE), maxCopies);
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
     * {@link #setRuleList(List)} throw {@link IllegalStateException}. Closing it again does nothing.
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
     * Adds imports that rules are compiled with, so rule expressions can refer to classes by their simple names.
     * Each string is a fully qualified package name ({@code "java.util"}) or class name
     * ({@code "java.time.LocalDate"}, or {@code "java.util.Map.Entry"} for a nested class).
     *
     * <p>
     * Imports are accumulated across multiple calls. This method must be called
     * <em>before</em> {@link #setRuleList(List)} for the imports to take effect.
     * </p>
     *
     * <p>
     * Whether a string names a class is decided here, by the calling thread's context class loader, or this library's
     * class loader if the thread has none. A string that loader can't load as a class, but that is a valid package
     * name, is imported as a package. Classes in imported packages are looked up by {@link #setRuleList(List)}, with
     * its thread's context class loader.
     * </p>
     *
     * @param packages A set of packages or classes to import
     * @return A reference to this {@link RulesEngine}
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public RulesEngine<O> addImports(Set<String> packages) {
        Objects.requireNonNull(packages, "packages must not be null");
        // Every name is resolved before any is registered, so an invalid one leaves the imports unchanged.
        Set<String> newPackages = new LinkedHashSet<>();
        Set<Class<?>> newClasses = new LinkedHashSet<>();
        for (String pkg : packages) {
            Objects.requireNonNull(pkg, "package element must not be null");
            Class<?> type = ImportResolver.resolve(pkg);
            if (type != null) {
                newClasses.add(type);
            } else {
                newPackages.add(pkg);
            }
        }
        classImports.addAll(newClasses);
        packageImports.addAll(newPackages);
        return this;
    }

    /**
     * Adds a single import that rules are compiled with: a fully qualified package name ({@code "java.util"}) or
     * class name ({@code "java.time.LocalDate"}).
     *
     * <p>
     * Imports are accumulated across multiple calls. This method must be called
     * <em>before</em> {@link #setRuleList(List)} for the imports to take effect. A class name is resolved as
     * {@link #addImports(Set)} describes.
     * </p>
     *
     * @param packageString The package or class to import. Example: "java.util"
     * @return A reference to this {@link RulesEngine}
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public RulesEngine<O> addImport(String packageString) {
        Objects.requireNonNull(packageString, "packageString must not be null");
        return addImports(Set.of(packageString));
    }

    /**
     * Registers an expression language that rules can be written in. It replaces a registered language, or a language
     * found with {@link java.util.ServiceLoader} such as MVEL, with the same name. Takes effect at the next
     * {@link #setRuleList(List)}.
     *
     * @param language The language to register
     * @return A reference to this {@link RulesEngine}
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public RulesEngine<O> registerLanguage(ExpressionLanguage language) {
        languages.register(language);
        return this;
    }

    /**
     * Unwraps the FactStore into a Map of values to be used as context variables when rules are evaluated.
     * This should be called once per engine run to avoid expensive allocations.
     *
     * @param facts  The key/value fact store
     * @param checks The compilers of the rule list the run uses, which check each name, by language name
     * @return A map of variable names to their values
     * @throws IllegalArgumentException if a fact is named {@code null} or {@code output}, which actions reserve
     *                                  for the output object, or has a name the language of a loaded rule can't
     *                                  refer to, such as a reserved MVEL word, or if a language's check of the name
     *                                  throws anything else. A fatal {@link Error} is logged, then rethrown unchanged.
     */
    Map<String, Object> unwrapFacts(FactStore<Object> facts, Map<String, ExpressionCompiler> checks) {
        Map<String, Object> entryMap = new HashMap<>();
        for (Map.Entry<String, FactReference<Object>> entry : facts.entrySet()) {
            if (entry.getKey() == null) {
                String msg = "fact name must not be null";
                log.error(msg);
                throw new IllegalArgumentException(msg);
            }
            // Actions bind the output object to this name, silently hiding a fact of the same name.
            if (OUTPUT_KEYWORD.equals(entry.getKey())) {
                String msg = "'" + OUTPUT_KEYWORD + "' is reserved for the output object and cannot be used as a "
                        + "fact name";
                log.error(msg);
                throw new IllegalArgumentException(msg);
            }
            checkFactName(entry.getKey(), checks);
            // A null reference is bound as null, like a Fact holding null. Skipping it left the name
            // unresolvable, so `x == null` failed instead of matching.
            FactReference<Object> fact = entry.getValue();
            entryMap.put(entry.getKey(), fact != null ? fact.getValue() : null);
        }
        return entryMap;
    }

    /**
     * Checks a fact name with the language of each rule in use. A language rejects a name with an
     * {@link IllegalArgumentException}, which is logged and thrown as is. Anything else a language throws is logged
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
                log.error(e.getMessage());
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
     * @return List of {@link CompiledRule} objects where their condition evaluated
     *         to <b>true</b>
     */
    List<CompiledRule> match(List<CompiledRule> ruleList, RuleSet.Copy copy, Map<String, Object> entryMap) {
        return ruleList.stream()
                .filter(rule -> parseCondition(rule, copy, entryMap))
                .toList();
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
     * @return {@code outputObject}, which the action changed in place or whose properties were set from the action's
     *         result. An action can't replace it:
     *         assigning to {@code output} fails with a {@link RuleExecutionException}, except inside a
     *         {@code def} function, where it creates a variable local to the function.
     */
    O executeRule(CompiledRule rule, RuleSet.Copy copy, O outputObject, Map<String, Object> entryMap) {
        return parseAction(rule, copy, outputObject, entryMap);
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

    private boolean parseCondition(CompiledRule rule, RuleSet.Copy copy, Map<String, Object> entryMap) {
        // The evaluation context makes its own read-only view, whose messages are about conditions, so a listener
        // that writes to the facts isn't told about conditions.
        Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(entryMap);
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeEvaluate", listener -> listener.beforeEvaluate(listenerCopy(rule), listenerFacts));

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing.
        Object evaluated;
        try {
            evaluated = rule.compiledCondition().evaluate(new EngineEvaluationContext(entryMap),
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
                listener -> listener.afterEvaluate(listenerCopy(rule), listenerFacts, result));

        return result;
    }

    private O parseAction(CompiledRule rule, RuleSet.Copy copy, O outputResult, Map<String, Object> entryMap) {
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeExecute", listener -> listener.beforeExecute(listenerCopy(rule), outputResult));

        // The context gives the action a read-only view: an action changes the output object, never the facts other
        // rules see.
        ActionContext context = new EngineActionContext(entryMap, outputResult);
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

        notifyAfter(snapshot, rule, "afterExecute", listener -> listener.afterExecute(listenerCopy(rule), outputResult));

        return outputResult;
    }

    /**
     * Sets one property an action returned on the output object. A failure fails the rule like a failing action.
     *
     * @throws RuleExecutionException if the property can't be set
     */
    private void setProperty(List<RuleListener> snapshot, CompiledRule rule, O output, String property, Object value) {
        try {
            PropertyWriter.set(output, property, value);
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
     * Copies a rule for one listener callback. Every callback gets its own copy, so a listener that calls a
     * setter can't change what the engine, other listeners, later callbacks or other threads see.
     *
     * @param rule The compiled rule being evaluated or executed
     * @return A new {@link Rule} with the same field values
     */
    private static Rule listenerCopy(CompiledRule rule) {
        return rule.rule().toBuilder().build();
    }

    /**
     * Takes the listeners for one condition evaluation or one action. The same snapshot serves the
     * {@code before*} callback and the {@code after*} or {@code onError} that closes it, so a listener registered
     * in between, even from inside a callback, starts with the next {@code before*} instead of receiving a closing
     * call without its opening one.
     *
     * @return The listeners registered right now
     */
    private List<RuleListener> listenerSnapshot() {
        return List.copyOf(listeners);
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
        return notifyListeners(snapshot, "onError", listener -> listener.onError(listenerCopy(rule), error));
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

    private static String languageOf(Rule rule) {
        return rule.getLanguage() != null ? rule.getLanguage() : DEFAULT_LANGUAGE;
    }

    /**
     * Finds the languages a rule list is compiled with. A failure to find them fails the rule list.
     *
     * @param loader The class loader the rule list is compiled with
     * @return The languages by name
     * @throws RuleCompilationException if finding the languages throws
     */
    private Map<String, ExpressionLanguage> availableLanguages(ClassLoader loader) {
        try {
            return languages.available(loader);
        } catch (Exception | Error e) {
            throw compilationFailure("Failed to find the expression languages: " + Failures.describe(e), e, null);
        }
    }

    /**
     * Creates a language's compiler for one rule list. A language that throws or returns {@code null} fails the rule
     * list, like an expression that doesn't compile.
     *
     * @param name     The name the language is registered under
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
        String displayName = Failures.displayName(ruleName);
        if (rule.getCondition() == null || rule.getCondition().isBlank()) {
            throw compilationFailure("Rule '" + displayName + "' has a null or blank condition expression", null,
                    ruleName, ExpressionKind.CONDITION, List.of());
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            throw compilationFailure("Rule '" + displayName + "' has a null or blank action expression", null,
                    ruleName, ExpressionKind.ACTION, List.of());
        }
        String language = languageOf(rule);
        if (compiler == null) {
            throw compilationFailure("Rule '" + displayName + "' is written in '" + Failures.quote(language)
                    + "', which isn't a registered expression language. Registered languages: "
                    + compilers.languageNames(), null, ruleName);
        }
        CompiledCondition compiledCondition = compile(
                new Expression(ruleName, ExpressionKind.CONDITION, rule.getCondition()), compiler::compileCondition);
        CompiledAction compiledAction = compile(
                new Expression(ruleName, ExpressionKind.ACTION, rule.getAction()), compiler::compileAction);
        // Rule is mutable and owned by the caller. Keeping their instance would let a later edit change what
        // listeners and error messages report while the compiled expressions kept running the old rule.
        Rule snapshot = Rule.builder()
                .ruleName(rule.getRuleName())
                .condition(rule.getCondition())
                .action(rule.getAction())
                .priority(rule.getPriority())
                .description(rule.getDescription())
                .language(rule.getLanguage())
                .build();
        return new CompiledRule(snapshot, displayName, language, compiledCondition, compiledAction);
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
