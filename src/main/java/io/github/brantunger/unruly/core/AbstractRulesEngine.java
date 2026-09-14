package io.github.brantunger.unruly.core;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;

/**
 * The AbstractRulesEngine is an abstract implementation of the
 * {@link RulesEngine} interface. The RulesEngine fires the
 * action expression from a list of {@link Rule} objects when their conditions
 * evaluate to <strong>true</strong>.
 *
 * <p>
 * <b>MVEL optimizer:</b> the engine leaves MVEL's global optimizer setting alone. Concurrent
 * {@code run()} calls never share a compiled expression, because MVEL replaces the accessors cached
 * in one without synchronization when a fact's runtime class changes, so they are safe with any
 * optimizer, including MVEL's default JIT optimizer.
 * </p>
 *
 * @param <O> The output object to instantiate
 */
@Slf4j
public abstract class AbstractRulesEngine<O> implements RulesEngine<O> {

    /**
     * System property that, when {@code true}, kept MVEL's JIT optimizer instead of switching the whole JVM to the
     * reflective one.
     *
     * @deprecated The engine no longer changes MVEL's optimizer, so this property is ignored.
     */
    @Deprecated(forRemoval = true)
    public static final String JIT_PROPERTY = "unruly.mvel.jit";

    private static final String OUTPUT_KEYWORD = ActionContext.OUTPUT_NAME;
    // The language of a rule whose language is null.
    private static final String DEFAULT_LANGUAGE = MvelExpressionLanguage.LANGUAGE_NAME;
    // Registered languages by name: MVEL, unless replaced, and those from registerLanguage().
    private final Map<String, ExpressionLanguage> languages =
            new ConcurrentHashMap<>(Map.of(DEFAULT_LANGUAGE, new MvelExpressionLanguage()));
    // Packages and classes from addImport(s), passed to every compilation of the next rule list.
    private final Set<String> packageImports = new LinkedHashSet<>();
    private final Set<Class<?>> classImports = new LinkedHashSet<>();
    // Copy-on-write: callbacks iterate a snapshot, so registering a listener from another thread
    // or from inside a callback can't throw ConcurrentModificationException out of run().
    private final List<RuleListener> listeners = new CopyOnWriteArrayList<>();
    // Volatile so a setRuleList() call on one thread is seen by run() on others. The rule set holds the rules and the
    // fact-name checks of the languages they use, and is fully built before it is assigned, so one volatile write
    // swaps in both.
    private volatile RuleSet ruleSet;
    // Checks fact names before the first rule list is loaded: the default language's compiler, with no imports.
    private final List<ExpressionCompiler> defaultFactChecks = List.of(languages.get(DEFAULT_LANGUAGE).newCompiler(
            new EngineCompileContext(Set.of(), Set.of(), ImportResolver.LIBRARY_CLASS_LOADER)));

    /**
     * Returns the rules as {@link #setRuleList(List)} compiled them, or {@code null} if it has not been called. The
     * engine never evaluates this list: each run uses its own copy, from {@link #withCompiledRules(Function)}. Use it
     * to inspect the rules; to evaluate them, use {@code withCompiledRules} too.
     *
     * <p>
     * <b>Note:</b> {@link CompiledRule} is package-private, so only the engines in this package can use this method,
     * {@link #withCompiledRules(Function)}, {@link #match(List, Map)} and
     * {@link #executeRule(CompiledRule, Object, Map)}. They are expected to become package-private in 2.0.
     * </p>
     *
     * @return An unmodifiable list of compiled rules, or {@code null}
     */
    protected List<CompiledRule> getCompiledRules() {
        RuleSet rules = ruleSet;
        return rules != null ? rules.rules() : null;
    }

    /**
     * Calls {@code run} with a compiled copy of the rules that no concurrent run is using, and keeps the copy for
     * later runs once {@code run} returns or throws. The first run after {@link #setRuleList(List)}, and a run that
     * starts while every copy is in use, makes a new copy. MVEL's compiled expressions aren't safe to share between
     * threads when a fact name is bound to different kinds of objects; see {@link RuleSet}.
     *
     * <p>
     * A missing call to {@link #setRuleList(List)} (e.g. a forgotten {@code @PostConstruct}) used to make every run
     * return {@code null}, indistinguishable from "no rule matched", so it is reported instead.
     * </p>
     *
     * <p>
     * <b>Note:</b> only the engines in this package can use this method; see {@link #getCompiledRules()}.
     * </p>
     *
     * @param run The body of a run, given the compiled rules in priority order, possibly none
     * @param <T> The type {@code run} returns
     * @return What {@code run} returns
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called
     * @throws RuleExecutionException if a new copy is needed and a compiled condition or action throws or returns
     *                                {@code null} from {@code copy()}. A fatal {@link Error} is logged, then
     *                                rethrown unchanged.
     */
    protected <T> T withCompiledRules(Function<List<CompiledRule>, T> run) {
        RuleSet rules = ruleSet;
        if (rules == null) {
            throw new IllegalStateException("setRuleList() must be called before run()");
        }
        List<CompiledRule> copy = rules.borrow();
        try {
            return run.apply(copy);
        } finally {
            rules.release(copy);
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
     * that is {@code null}, using the languages registered when this method is called. {@code run()} checks fact names
     * against every language the rules use.
     * </p>
     *
     * @param ruleList The List of {@link Rule} objects to compile.
     * @throws RuleCompilationException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public void setRuleList(List<Rule> ruleList) {
        Objects.requireNonNull(ruleList, "ruleList must not be null");
        // Checked before sorting, which would otherwise fail with a bare NPE from Rule::getPriority.
        Set<String> ruleNames = new HashSet<>();
        for (int i = 0; i < ruleList.size(); i++) {
            Rule rule = ruleList.get(i);
            if (rule == null) {
                throw compilationFailure("Rule at index " + i + " of the rule list is null");
            }
            // Duplicate names would make error messages and listener logs ambiguous. Unnamed rules are allowed.
            if (rule.getRuleName() != null && !ruleNames.add(rule.getRuleName())) {
                throw compilationFailure("Duplicate rule name '" + rule.getRuleName() + "'");
            }
        }
        CompileContext context = new EngineCompileContext(Set.copyOf(packageImports), Set.copyOf(classImports),
                ImportResolver.contextClassLoader());
        LanguageCompilers compilers = new LanguageCompilers(Map.copyOf(languages),
                (name, language) -> newCompiler(name, language, context));
        List<CompiledRule> compiled = ruleList.stream()
                .sorted(Comparator.comparing(
                        Rule::getPriority,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .map(rule -> compileRule(rule, compilers))
                .collect(Collectors.toCollection(ArrayList::new));
        this.ruleSet = new RuleSet(compiled, compilers.used(DEFAULT_LANGUAGE), AbstractRulesEngine::copy);
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
     * Registers an expression language that rules can be written in. MVEL is registered from the start; a language
     * with the same name as a registered one replaces it. Takes effect at the next {@link #setRuleList(List)}.
     *
     * @param language The language to register
     * @return A reference to this {@link RulesEngine}
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public RulesEngine<O> registerLanguage(ExpressionLanguage language) {
        Objects.requireNonNull(language, "language must not be null");
        String name = language.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An expression language's name must not be null or blank: "
                    + language.getClass().getName());
        }
        languages.put(name, language);
        return this;
    }

    /**
     * Unwraps the FactStore into a Map of values to be used as context variables when rules are evaluated.
     * This should be called once per engine run to avoid expensive allocations.
     *
     * @param facts The key/value fact store
     * @return A map of variable names to their values
     * @throws IllegalArgumentException if a fact is named {@code null} or {@code output}, which actions reserve
     *                                  for the output object, or has a name the language of a loaded rule can't
     *                                  refer to, such as a reserved MVEL word
     */
    protected Map<String, Object> unwrapFacts(FactStore<Object> facts) {
        Map<String, Object> entryMap = new HashMap<>();
        // Read apart from the rules a run borrowed, so a run racing a reload may check names with the other list's
        // checks. That only changes whether a name one of the lists can't use is accepted, for that run.
        RuleSet rules = ruleSet;
        List<ExpressionCompiler> checks = rules != null ? rules.factChecks() : defaultFactChecks;
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
            try {
                for (ExpressionCompiler check : checks) {
                    check.checkFactName(entry.getKey());
                }
            } catch (IllegalArgumentException e) {
                log.error(e.getMessage());
                throw e;
            }
            // A null reference is bound as null, like a Fact holding null. Skipping it left the name
            // unresolvable, so `x == null` failed instead of matching.
            FactReference<Object> fact = entry.getValue();
            entryMap.put(entry.getKey(), fact != null ? fact.getValue() : null);
        }
        return entryMap;
    }

    /**
     * Evaluates the rules' conditions one after another, in list order, and keeps the rules that matched.
     *
     * <p>
     * <b>Note:</b> only the engines in this package can use this method; see {@link #getCompiledRules()}.
     * </p>
     *
     * @param ruleList This is a list of {@link CompiledRule} objects to filter
     *                 based on when condition expression parses to
     *                 true
     * @param entryMap The pre-built map of unwrapped facts to use as execution context.
     * @return List of {@link CompiledRule} objects where their condition evaluated
     *         to <b>true</b>
     */
    protected List<CompiledRule> match(List<CompiledRule> ruleList, Map<String, Object> entryMap) {
        return ruleList.stream()
                .filter(rule -> parseCondition(rule, entryMap))
                .toList();
    }

    /**
     * Execute a single {@link CompiledRule} object's action field against the input
     * data
     *
     * <p>
     * <b>Note:</b> only the engines in this package can use this method; see {@link #getCompiledRules()}.
     * </p>
     *
     * @param rule         The rule object to obtain the action expression to fire
     *                     the rule for
     * @param outputObject an empty output object to set output data into
     * @param entryMap     The pre-built map of unwrapped facts to use as execution context.
     * @return {@code outputObject}, which the action changes in place. An action can't replace it:
     *         assigning to {@code output} fails with a {@link RuleExecutionException}, except inside a
     *         {@code def} function, where it creates a variable local to the function.
     */
    protected O executeRule(CompiledRule rule, O outputObject, Map<String, Object> entryMap) {
        return parseAction(rule, outputObject, entryMap);
    }

    /**
     * Creates a fresh output object from the engine's factory. Without this, a factory that throws
     * escaped {@code run()} unwrapped, and one that returned {@code null} surfaced later as an action
     * failure blamed on whichever rule ran first.
     *
     * @param outputFactory The factory supplied to the engine's constructor
     * @return The new output object, never {@code null}
     * @throws RuleExecutionException if the factory throws or returns {@code null}. An {@link Error} other than
     *                                {@link StackOverflowError} or {@link AssertionError} is rethrown unchanged,
     *                                also when it is the cause of what the factory throws.
     */
    protected O createOutput(Supplier<O> outputFactory) {
        O output;
        try {
            output = outputFactory.get();
        } catch (Exception | Error e) {
            Failures.throwIfPresent(Failures.fatalError(e));
            String msg = "Output factory threw " + e;
            log.error(msg);
            throw new RuleExecutionException(msg, e);
        }
        if (output == null) {
            String msg = "Output factory returned null. It must return a new output object on every call.";
            log.error(msg);
            throw new RuleExecutionException(msg);
        }
        return output;
    }

    private boolean parseCondition(CompiledRule rule, Map<String, Object> entryMap) {
        Map<String, Object> conditionFacts = ReadOnlyFacts.forConditions(entryMap);
        // A separate view, so a listener that writes to the facts isn't told about conditions.
        Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(entryMap);
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeEvaluate", listener -> listener.beforeEvaluate(listenerCopy(rule), listenerFacts));

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing.
        Object evaluated;
        try {
            evaluated = rule.compiledCondition().evaluate(new EngineEvaluationContext(conditionFacts));
        } catch (Exception | Error e) {
            throw failure(snapshot, rule, "Failed to evaluate condition for rule '" + rule.displayName() + "': "
                    + Failures.describe(e), e);
        }

        // Unboxing a null here would surface as an internal NPE naming MVEL's own
        // signature, which tells the caller nothing about their rule.
        if (evaluated == null) {
            throw failure(snapshot, rule, "Condition for rule '" + rule.displayName()
                    + "' evaluated to null. A condition expression must evaluate to a boolean.", null);
        }

        if (!(evaluated instanceof Boolean result)) {
            throw failure(snapshot, rule, "Condition for rule '" + rule.displayName() + "' evaluated to a "
                    + evaluated.getClass().getName() + ". A condition expression must evaluate to a boolean.", null);
        }

        notifyAfter(snapshot, "afterEvaluate", listener -> listener.afterEvaluate(listenerCopy(rule), listenerFacts, result));

        return result;
    }

    private O parseAction(CompiledRule rule, O outputResult, Map<String, Object> entryMap) {
        List<RuleListener> snapshot = listenerSnapshot();
        notifyBefore(snapshot, rule, "beforeExecute", listener -> listener.beforeExecute(listenerCopy(rule), outputResult));

        // A read-only view: an action changes the output object, never the facts other rules see.
        ActionContext context = new EngineActionContext(ReadOnlyFacts.forActions(entryMap), outputResult);
        try {
            rule.compiledAction().execute(context);
        } catch (Exception | Error e) {
            throw failure(snapshot, rule, "Failed to execute action for rule '" + rule.displayName() + "': "
                    + Failures.describe(e), e);
        }

        notifyAfter(snapshot, "afterExecute", listener -> listener.afterExecute(listenerCopy(rule), outputResult));

        return outputResult;
    }

    /**
     * Copies a rule for one listener callback. Every callback gets its own copy, so a listener that calls a
     * setter can't change what the engine, other listeners, later callbacks or other threads see.
     *
     * @param rule The compiled rule being evaluated or executed
     * @return A new {@link Rule} with the same field values
     */
    private static Rule listenerCopy(CompiledRule rule) {
        Rule source = rule.rule();
        return new Rule(source.getRuleName(), source.getCondition(), source.getAction(), source.getPriority(),
                source.getDescription(), source.getLanguage());
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
            reportFailure(snapshot, rule, new RuleExecutionException("A listener threw " + fatal.getClass().getName()
                    + " in " + callback + " for rule '" + rule.displayName() + "'", fatal), true);
            throw fatal;
        }
    }

    /** Calls an {@code after*} callback on every listener, then rethrows the first fatal {@link Error} one threw. */
    private void notifyAfter(List<RuleListener> snapshot, String callback, Consumer<RuleListener> call) {
        Failures.throwIfPresent(notifyListeners(snapshot, callback, call));
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
     * {@code before*} callback still gets a closing call. Returns the exception for the caller to throw, unless
     * the cause is or wraps a fatal {@link Error}, which is rethrown unchanged once listeners have been told, or a
     * listener threw a fatal error from {@code onError}, which is rethrown once every listener has been told.
     */
    private RuleExecutionException failure(List<RuleListener> snapshot, CompiledRule rule, String msg,
                                           Throwable cause) {
        RuleExecutionException error = cause == null
                ? new RuleExecutionException(msg)
                : new RuleExecutionException(msg, cause);
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
     * Logs a rejected rule list at ERROR, as the engine logs every failure it throws, and returns the exception for
     * the caller to throw.
     *
     * @param msg What is wrong with the rule list
     * @return The exception to throw
     */
    private static RuleCompilationException compilationFailure(String msg) {
        log.error(msg);
        return new RuleCompilationException(msg);
    }

    /**
     * Logs a failure to compile at ERROR, as the engine logs every failure it throws. Then rethrows the fatal
     * {@link Error} in {@code cause}'s cause chain, if there is one (see {@link Failures#fatalError}), or returns the
     * exception for the caller to throw.
     *
     * @param msg   What failed, naming the rule or the language
     * @param cause What the expression language threw
     * @return The exception to throw, caused by {@code cause}
     */
    private static RuleCompilationException compilationFailure(String msg, Throwable cause) {
        log.error(msg);
        Failures.throwIfPresent(Failures.fatalError(cause));
        return new RuleCompilationException(msg, cause);
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
            throw compilationFailure("The '" + name + "' expression language failed to create a compiler: "
                    + Failures.describe(e), e);
        }
        if (compiler == null) {
            throw compilationFailure("The '" + name + "' expression language returned no compiler");
        }
        return compiler;
    }

    private CompiledRule compileRule(Rule rule, LanguageCompilers compilers) {
        String ruleName = rule.getRuleName() != null ? rule.getRuleName() : "(unnamed)";
        if (rule.getCondition() == null || rule.getCondition().isBlank()) {
            throw compilationFailure("Rule '" + ruleName + "' has a null or blank condition expression");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            throw compilationFailure("Rule '" + ruleName + "' has a null or blank action expression");
        }
        String language = rule.getLanguage() != null ? rule.getLanguage() : DEFAULT_LANGUAGE;
        ExpressionCompiler compiler = compilers.forLanguage(language);
        if (compiler == null) {
            throw compilationFailure("Rule '" + ruleName + "' is written in '" + language
                    + "', which isn't a registered expression language. Registered languages: "
                    + compilers.languageNames());
        }
        CompiledCondition compiledCondition = compile(ruleName, "Condition",
                () -> compiler.compileCondition(rule.getCondition()));
        CompiledAction compiledAction = compile(ruleName, "Action", () -> compiler.compileAction(rule.getAction()));
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
        return new CompiledRule(snapshot, ruleName, compiledCondition, compiledAction);
    }

    /**
     * Compiles one condition or action. An expression the language rejects is reported with the language's reason;
     * anything else the language throws, such as a syntax error, becomes the cause of the failure. A fatal
     * {@link Error}, also one the language wraps in its own exception, is logged like any failure and then rethrown.
     *
     * @param ruleName    The rule's name for messages
     * @param expression  {@code Condition} or {@code Action}, for messages
     * @param compilation Compiles the expression
     * @param <T>         The type of compiled expression
     * @return The compiled expression
     * @throws RuleCompilationException if the expression doesn't compile, or the language returns {@code null}
     */
    private static <T> T compile(String ruleName, String expression, Supplier<T> compilation) {
        T compiled;
        try {
            compiled = compilation.get();
        } catch (InvalidExpressionException e) {
            String reason = e.getMessage() != null
                    ? Failures.truncate(e.getMessage())
                    : "was rejected by its expression language";
            throw compilationFailure(expression + " for rule '" + ruleName + "' " + reason, e);
        } catch (Exception | Error e) {
            // MVEL's parser recurses once per operator, so a very long expression overflows the stack.
            String reason = Failures.rootCause(e) instanceof StackOverflowError
                    ? "the expression is too long or too deeply nested to compile"
                    : Failures.describe(e);
            throw compilationFailure("Can not compile rule '" + ruleName + "'. Error: " + reason, e);
        }
        if (compiled == null) {
            throw compilationFailure(expression + " for rule '" + ruleName
                    + "' wasn't compiled: its expression language returned null");
        }
        return compiled;
    }

    /**
     * Copies a rule for a run; see {@link RuleSet}. Each expression decides whether a copy needs compiling.
     *
     * @param rule The rule as {@code setRuleList()} compiled it
     * @return The copy
     * @throws RuleExecutionException if the condition or action throws or returns {@code null} from {@code copy()}
     */
    private static CompiledRule copy(CompiledRule rule) {
        CompiledCondition condition = copyOf(rule, "condition", () -> rule.compiledCondition().copy());
        CompiledAction action = copyOf(rule, "action", () -> rule.compiledAction().copy());
        return new CompiledRule(rule.rule(), rule.displayName(), condition, action);
    }

    /**
     * Copies a rule's condition or action. A failure fails the run that needed the copy, naming the rule, and is logged
     * at ERROR first. A fatal {@link Error}, thrown or found among the causes of what {@code copy()} throws, is then
     * rethrown unchanged. No listener is told: no callback has been sent for the rule yet.
     *
     * @param rule       The rule being copied
     * @param expression {@code condition} or {@code action}, for messages
     * @param copy       Calls the expression's {@code copy()}
     * @param <T>        The type of compiled expression
     * @return The copy
     * @throws RuleExecutionException if {@code copy()} throws or returns {@code null}
     */
    private static <T> T copyOf(CompiledRule rule, String expression, Supplier<T> copy) {
        String failed = "Failed to copy the " + expression + " of rule '" + rule.displayName() + "': ";
        T copied;
        try {
            copied = copy.get();
        } catch (Exception | Error e) {
            String msg = failed + Failures.describe(e);
            log.error(msg);
            Failures.throwIfPresent(Failures.fatalError(e));
            throw new RuleExecutionException(msg, e);
        }
        if (copied == null) {
            String msg = failed + "copy() returned null";
            log.error(msg);
            throw new RuleExecutionException(msg);
        }
        return copied;
    }
}
