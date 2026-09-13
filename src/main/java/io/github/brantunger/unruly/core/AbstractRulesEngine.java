package io.github.brantunger.unruly.core;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /** How much of an exception's message an error message includes; see {@link #describe}. */
    static final int MAX_DESCRIPTION_LENGTH = 1_000;
    // For a thread that has no context class loader. PMD asks for the context class loader instead, which is exactly
    // what is missing in that case.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static final ClassLoader LIBRARY_CLASS_LOADER = AbstractRulesEngine.class.getClassLoader();
    // The language rules are written in.
    private final ExpressionLanguage language = new MvelExpressionLanguage();
    // Packages and classes from addImport(s), passed to every compilation of the next rule list.
    private final Set<String> packageImports = new LinkedHashSet<>();
    private final Set<Class<?>> classImports = new LinkedHashSet<>();
    // Copy-on-write: callbacks iterate a snapshot, so registering a listener from another thread
    // or from inside a callback can't throw ConcurrentModificationException out of run().
    private final List<RuleListener> listeners = new CopyOnWriteArrayList<>();
    // Volatile so a setRuleList() call on one thread is seen by run() on others. The rule set is fully built
    // before it is assigned, so a single volatile write is enough.
    private volatile RuleSet ruleSet;
    // Checks fact names against the imports the current rules were compiled with: the current rule list's compiler,
    // or one with no imports before the first. Replaced alongside ruleSet; a run racing a reload may use the other
    // list's imports, which only changes whether an imported class name is accepted.
    private volatile ExpressionCompiler factNames = language.newCompiler(
            new EngineCompileContext(Set.of(), Set.of(), LIBRARY_CLASS_LOADER));

    /**
     * Returns an unmodifiable view of the compiled rules list.
     * Returns {@code null} if {@link #setRuleList(List)} has not been called.
     *
     * @return An unmodifiable list of compiled rules, or {@code null}
     */
    protected List<CompiledRule> getCompiledRules() {
        RuleSet rules = ruleSet;
        return rules != null ? rules.rules() : null;
    }

    /**
     * Calls {@code run} with a compiled copy of the rules that no concurrent run is using, and keeps the copy for
     * later runs once {@code run} returns or throws. MVEL's compiled expressions aren't safe to share between
     * threads when a fact name is bound to different kinds of objects; see {@link RuleSet}.
     *
     * <p>
     * A missing call to {@link #setRuleList(List)} (e.g. a forgotten {@code @PostConstruct}) used to make every run
     * return {@code null}, indistinguishable from "no rule matched", so it is reported instead.
     * </p>
     *
     * @param run The body of a run, given the compiled rules in priority order, possibly none
     * @param <T> The type {@code run} returns
     * @return What {@code run} returns
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called
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
                contextClassLoader());
        ExpressionCompiler compiler = language.newCompiler(context);
        List<CompiledRule> compiled = ruleList.stream()
                .sorted(Comparator.comparing(
                        Rule::getPriority,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .map(rule -> compileRule(rule, compiler))
                .collect(Collectors.toCollection(ArrayList::new));
        this.factNames = compiler;
        this.ruleSet = new RuleSet(compiled, AbstractRulesEngine::copy);
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
            Class<?> type = resolveImport(pkg);
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
     * <em>before</em> {@link #setRuleList(List)} for the imports to take effect.
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
     * Unwraps the FactStore into a Map of values to be used as context variables in MVEL evaluation.
     * This should be called once per engine run to avoid expensive allocations.
     *
     * @param facts The key/value fact store
     * @return A map of variable names to their values
     * @throws IllegalArgumentException if a fact is named {@code output}, which actions reserve
     *                                  for the output object, or has a name rules can't refer to: one that
     *                                  isn't a Java identifier, a reserved MVEL word, or a class name MVEL
     *                                  resolves instead of the fact
     */
    protected Map<String, Object> unwrapFacts(FactStore<Object> facts) {
        Map<String, Object> entryMap = new HashMap<>();
        for (Map.Entry<String, FactReference<Object>> entry : facts.entrySet()) {
            // Actions bind the output object to this name, silently hiding a fact of the same name.
            if (OUTPUT_KEYWORD.equals(entry.getKey())) {
                String msg = "'" + OUTPUT_KEYWORD + "' is reserved for the output object and cannot be used as a "
                        + "fact name";
                log.error(msg);
                throw new IllegalArgumentException(msg);
            }
            try {
                factNames.checkFactName(entry.getKey());
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
            throwIfPresent(fatalError(e));
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
                    + describe(e), e);
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
        ActionContext context = new EngineActionContext(Collections.unmodifiableMap(entryMap), outputResult);
        try {
            rule.compiledAction().execute(context);
        } catch (Exception | Error e) {
            throw failure(snapshot, rule, "Failed to execute action for rule '" + rule.displayName() + "': "
                    + describe(e), e);
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
                source.getDescription());
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
        throwIfPresent(notifyListeners(snapshot, callback, call));
    }

    /**
     * Calls every listener in {@code snapshot}, logging what a listener throws so a faulty listener can't interrupt a
     * run. A fatal {@link Error} (see {@link #fatalError}), thrown or found among the causes of what a listener
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
                Error found = fatal == null ? fatalError(e) : null;
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
        Error listenerFatal = reportFailure(snapshot, rule, error, nestedRunFailure(cause) == null);
        throwIfPresent(fatalError(cause));
        throwIfPresent(listenerFatal);
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
     * Tells whether {@code thrown} is an {@link Error} the engine must not absorb. A {@link StackOverflowError}
     * (runaway recursion) or {@link AssertionError} ({@code assert} in a rule or listener) comes from the code
     * being run and is handled like an exception. Any other error, such as {@link OutOfMemoryError}, is left
     * for the caller to see unchanged.
     *
     * @param thrown One throwable from a cause chain
     * @return {@code true} if {@code thrown} must be rethrown unchanged
     */
    private static boolean isFatal(Throwable thrown) {
        return thrown instanceof Error && !(thrown instanceof StackOverflowError || thrown instanceof AssertionError);
    }

    /**
     * Finds the fatal {@link Error} (see {@link #isFatal}) in what was caught: the throwable itself, or one of its
     * causes. An error from Java code a rule calls, such as a method, a getter or a lambda held in a fact, reaches the
     * engine inside MVEL's own exception, so checking only the outer exception let an {@link OutOfMemoryError} be
     * absorbed into a {@link RuleExecutionException}.
     *
     * @param thrown What was caught, or {@code null}
     * @return The first fatal error in {@code thrown}'s cause chain, or {@code null} if there is none
     */
    private static Error fatalError(Throwable thrown) {
        for (Throwable t : causeChain(thrown)) {
            if (isFatal(t)) {
                return (Error) t;
            }
        }
        return null;
    }

    private static void throwIfPresent(Error fatal) {
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Describes an exception for an error message:
     * <ul>
     *     <li>its message, or its class name if it has none (NPEs and bare RuntimeExceptions carry no message)</li>
     *     <li>the class of its root cause when that has no message either, since MVEL copies a cause's missing message
     *     into its own as {@code ": null"}</li>
     *     <li>at most {@value #MAX_DESCRIPTION_LENGTH} characters of the message: MVEL pads its messages with spaces up
     *     to the error's column, so a long expression produced messages hundreds of thousands of characters long</li>
     * </ul>
     * A failure of a {@code run()} started from a condition or action is described by that run's innermost failure
     * only, so a failure nested many runs deep isn't repeated once per level.
     *
     * @param e The exception to describe
     * @return A description of the exception for an error message
     */
    static String describe(Throwable e) {
        RuleExecutionException nested = nestedRunFailure(e);
        if (nested != null) {
            return "a nested run() failed: " + nested.getMessage();
        }
        String text = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        if (text.length() > MAX_DESCRIPTION_LENGTH) {
            text = text.substring(0, MAX_DESCRIPTION_LENGTH) + "... (" + (text.length() - MAX_DESCRIPTION_LENGTH)
                    + " more characters)";
        }
        List<Throwable> chain = causeChain(e);
        Throwable root = chain.get(chain.size() - 1);
        return chain.size() > 1 && root.getMessage() == null
                ? text + " (caused by " + root.getClass().getName() + ")"
                : text;
    }

    /**
     * Finds the innermost failure of a {@code run()} started by the code that threw {@code e}. That run already
     * logged it and told its listeners.
     *
     * @param e What was caught, or {@code null}
     * @return The innermost {@link RuleExecutionException} in {@code e}'s cause chain, or {@code null}
     */
    private static RuleExecutionException nestedRunFailure(Throwable e) {
        RuleExecutionException innermost = null;
        for (Throwable t : causeChain(e)) {
            if (t instanceof RuleExecutionException failure) {
                innermost = failure;
            }
        }
        return innermost;
    }

    private static Throwable rootCause(Throwable e) {
        List<Throwable> chain = causeChain(e);
        return chain.get(chain.size() - 1);
    }

    /** Lists {@code e} and its causes, stopping if the chain loops back on itself. */
    private static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
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
     * Logs an expression the language rejected at ERROR, and returns the exception for the caller to throw, caused by
     * the language's rejection.
     *
     * @param msg   What is wrong with the expression, naming the rule
     * @param cause The language's rejection
     * @return The exception to throw
     */
    private static RuleCompilationException compilationFailure(String msg, InvalidExpressionException cause) {
        log.error(msg);
        return new RuleCompilationException(msg, cause);
    }

    private CompiledRule compileRule(Rule rule, ExpressionCompiler compiler) {
        String ruleName = rule.getRuleName() != null ? rule.getRuleName() : "(unnamed)";
        if (rule.getCondition() == null || rule.getCondition().isBlank()) {
            throw compilationFailure("Rule '" + ruleName + "' has a null or blank condition expression");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            throw compilationFailure("Rule '" + ruleName + "' has a null or blank action expression");
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
                .build();
        return new CompiledRule(snapshot, ruleName, compiledCondition, compiledAction);
    }

    /**
     * Compiles one condition or action. An expression the language rejects is reported with the language's reason;
     * anything else the language throws, such as a syntax error, becomes the cause of the failure.
     *
     * @param ruleName    The rule's name for messages
     * @param expression  {@code Condition} or {@code Action}, for messages
     * @param compilation Compiles the expression
     * @param <T>         The type of compiled expression
     * @return The compiled expression
     * @throws RuleCompilationException if the expression doesn't compile
     */
    private static <T> T compile(String ruleName, String expression, Supplier<T> compilation) {
        try {
            return compilation.get();
        } catch (InvalidExpressionException e) {
            throw compilationFailure(expression + " for rule '" + ruleName + "' " + e.getMessage(), e);
        } catch (Exception e) {
            // MVEL's parser recurses once per operator, so a very long expression overflows the stack.
            String reason = rootCause(e) instanceof StackOverflowError
                    ? "the expression is too long or too deeply nested to compile"
                    : describe(e);
            String msg = "Can not compile rule '" + ruleName + "'. Error: " + reason;
            log.error(msg);
            throw new RuleCompilationException(msg, e);
        }
    }

    /**
     * Copies a rule for a concurrent run; see {@link RuleSet}. Each expression decides whether a copy needs compiling.
     */
    private static CompiledRule copy(CompiledRule rule) {
        return new CompiledRule(rule.rule(), rule.displayName(), rule.compiledCondition().copy(),
                rule.compiledAction().copy());
    }

    /**
     * Works out what an import string names. A class the context class loader can load is imported on its own, so
     * {@code addImport("java.time.LocalDate")} works; anything else must be a syntactically valid package name.
     * A nested class can be written as Java imports it ({@code java.util.Map.Entry}) or by its binary name
     * ({@code java.util.Map$Entry}), as in an inline MVEL {@code import}.
     *
     * @param name The string passed to {@code addImport}
     * @return The class, or {@code null} if {@code name} is a package name
     * @throws IllegalArgumentException if {@code name} is neither a loadable class nor a valid package name
     */
    private static Class<?> resolveImport(String name) {
        try {
            return loadImport(name, contextClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            if (!isPackageName(name)) {
                throw new IllegalArgumentException("'" + name + "' is neither a class nor a valid package name", e);
            }
            return null;
        }
    }

    /**
     * Loads an imported class as MVEL looks one up: by its name, then with each dot from the right replaced by
     * {@code $} in turn, so {@code java.util.Map.Entry} finds {@code java.util.Map$Entry}. The class isn't
     * initialized.
     *
     * @throws ClassNotFoundException the first lookup's exception, if no form of the name is a class
     */
    private static Class<?> loadImport(String name, ClassLoader loader) throws ClassNotFoundException {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException notFound) {
            String binaryName = name;
            for (int dot = name.lastIndexOf('.'); dot > 0; dot = binaryName.lastIndexOf('.')) {
                binaryName = binaryName.substring(0, dot) + '$' + binaryName.substring(dot + 1);
                Class<?> nested = loadOrNull(binaryName, loader);
                if (nested != null) {
                    return nested;
                }
            }
            throw notFound;
        }
    }

    private static Class<?> loadOrNull(String name, ClassLoader loader) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Returns the class loader to look up imported classes with: the calling thread's context class loader, or this
     * library's own loader when the thread has none. Left to itself, MVEL takes the context class loader of whichever
     * thread first needs one, so a lookup made from another thread could see different classes.
     *
     * @return The class loader for imports set up on this thread
     */
    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : LIBRARY_CLASS_LOADER;
    }

    private static boolean isPackageName(String name) {
        for (String part : name.split("\\.", -1)) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
