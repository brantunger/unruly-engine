package io.github.brantunger.unruly.core;

import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.util.ParseTools;

import java.io.Serializable;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

/**
 * The AbstractRulesEngine is an abstract implementation of the
 * {@link RulesEngine} interface. The RulesEngine fires the
 * action expression from a list of {@link Rule} objects when their conditions
 * evaluate to <strong>true</strong>.
 *
 * <p>
 * <b>MVEL optimizer:</b> loading this class switches MVEL's default accessor optimizer to
 * {@link OptimizerFactory#SAFE_REFLECTIVE} for the whole JVM. MVEL's JIT optimizer rewrites
 * compiled expressions during evaluation and, when the same fact name is bound to different
 * classes, races between concurrent {@code run()} calls. MVEL selects the optimizer from a
 * single global setting, so it cannot be scoped to this engine. Set the system property
 * {@value #JIT_PROPERTY}{@code =true} to leave MVEL's setting untouched; with the JIT on, facts
 * whose runtime class varies must not be run concurrently.
 * </p>
 *
 * @param <O> The output object to instantiate
 */
@Slf4j
public abstract class AbstractRulesEngine<O> implements RulesEngine<O> {

    /**
     * System property that, when {@code true}, keeps MVEL's JIT optimizer instead of switching to
     * the reflective one.
     */
    public static final String JIT_PROPERTY = "unruly.mvel.jit";

    static {
        configureMvel(System.getProperty(JIT_PROPERTY));
    }

    private static final String OUTPUT_KEYWORD = "output";
    // Package imports from addImport(s). Rules never compile against a shared MVEL context; see compileExpression.
    private final Set<String> packageImports = new LinkedHashSet<>();
    private final Set<Class<?>> classImports = new LinkedHashSet<>();
    // Copy-on-write: callbacks iterate a snapshot, so registering a listener from another thread
    // or from inside a callback can't throw ConcurrentModificationException out of run().
    private final List<RuleListener> listeners = new CopyOnWriteArrayList<>();
    // Volatile so a setRuleList() call on one thread is seen by run() on others. The list is fully built
    // before it is assigned and never modified afterwards, so a single volatile write is enough.
    private volatile List<CompiledRule> compiledRules;
    // Checks fact names against the imports the current rules were compiled with. Replaced alongside
    // compiledRules; a run racing a reload may use the other list's imports, which only changes whether an
    // imported class name is accepted.
    private volatile FactNames factNames = new FactNames(Imports.NONE);

    /**
     * Selects MVEL's reflective optimizer unless the JIT has been opted into. A separate method so both
     * outcomes can be tested; the static initializer only ever sees one value of the property.
     *
     * @param jitProperty The value of {@value #JIT_PROPERTY}, or {@code null} if unset
     */
    static void configureMvel(String jitProperty) {
        if (!Boolean.parseBoolean(jitProperty)) {
            OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        }
    }

    /**
     * Returns an unmodifiable view of the compiled rules list.
     * Returns {@code null} if {@link #setRuleList(List)} has not been called.
     *
     * @return An unmodifiable list of compiled rules, or {@code null}
     */
    protected List<CompiledRule> getCompiledRules() {
        return compiledRules != null ? Collections.unmodifiableList(compiledRules) : null;
    }

    /**
     * Returns an unmodifiable view of the compiled rules for {@code run()}. A missing call to
     * {@link #setRuleList(List)} (e.g. a forgotten {@code @PostConstruct}) used to make every run return
     * {@code null}, indistinguishable from "no rule matched", so it is reported instead.
     *
     * @return An unmodifiable list of compiled rules, possibly empty
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called
     */
    protected List<CompiledRule> requireCompiledRules() {
        List<CompiledRule> rules = getCompiledRules();
        if (rules == null) {
            throw new IllegalStateException("setRuleList() must be called before run()");
        }
        return rules;
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
                throw new RuleCompilationException("Rule at index " + i + " of the rule list is null");
            }
            // Duplicate names would make error messages and listener logs ambiguous. Unnamed rules are allowed.
            if (rule.getRuleName() != null && !ruleNames.add(rule.getRuleName())) {
                throw new RuleCompilationException("Duplicate rule name '" + rule.getRuleName() + "'");
            }
        }
        Imports imports = new Imports(Set.copyOf(packageImports), Set.copyOf(classImports),
                Imports.contextClassLoader());
        List<CompiledRule> compiled = ruleList.stream()
                .sorted(Comparator.comparing(
                        Rule::getPriority,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .map(rule -> compileRule(rule, imports))
                .collect(Collectors.toCollection(ArrayList::new));
        this.factNames = new FactNames(imports);
        this.compiledRules = compiled;
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
                throw new IllegalArgumentException("'" + OUTPUT_KEYWORD
                        + "' is reserved for the output object and cannot be used as a fact name");
            }
            factNames.check(entry.getKey());
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
     *         assigning to {@code output} fails with a {@link RuleExecutionException}.
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
     *                                {@link StackOverflowError} or {@link AssertionError} is rethrown unchanged.
     */
    protected O createOutput(Supplier<O> outputFactory) {
        O output;
        try {
            output = outputFactory.get();
        } catch (Exception | Error e) {
            rethrowIfFatal(e);
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
        notifyListeners(snapshot, "beforeEvaluate", listener -> listener.beforeEvaluate(listenerCopy(rule), listenerFacts));

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing.
        Object evaluated;
        try {
            evaluated = MVEL.executeExpression(rule.compiledCondition(), (Object) null, conditionFacts);
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

        notifyListeners(snapshot, "afterEvaluate", listener -> listener.afterEvaluate(listenerCopy(rule), listenerFacts, result));

        return result;
    }

    private O parseAction(CompiledRule rule, O outputResult, Map<String, Object> entryMap) {
        List<RuleListener> snapshot = listenerSnapshot();
        notifyListeners(snapshot, "beforeExecute", listener -> listener.beforeExecute(listenerCopy(rule), outputResult));

        // Create a copy so we don't mutate the shared fact map with the output keyword
        Map<String, Object> input = new ActionVariables(entryMap, outputResult);
        try {
            MVEL.executeExpression(rule.compiledAction(), (Object) null, input);
        } catch (Exception | Error e) {
            throw failure(snapshot, rule, "Failed to execute action for rule '" + rule.displayName() + "': "
                    + describe(e), e);
        }

        notifyListeners(snapshot, "afterExecute", listener -> listener.afterExecute(listenerCopy(rule), outputResult));

        return outputResult;
    }

    /**
     * The variables an action runs against: a copy of the facts, so an action's assignments stay local to it,
     * plus the output object. MVEL writes an assignment such as {@code output = new HashMap()} into this map and
     * the engine never reads it back, so the replacement would be silently discarded. The write is rejected
     * instead; actions change the output object in place.
     */
    private static final class ActionVariables extends HashMap<String, Object> {
        private static final long serialVersionUID = 1L;

        ActionVariables(Map<String, Object> facts, Object output) {
            super(facts);
            super.put(OUTPUT_KEYWORD, output);
        }

        @Override
        public Object put(String key, Object value) {
            if (OUTPUT_KEYWORD.equals(key)) {
                throw new UnsupportedOperationException("Cannot assign '" + OUTPUT_KEYWORD
                        + "': an action changes the output object in place (e.g. output.put(...)) but can't replace it.");
            }
            return super.put(key, value);
        }
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
     * Calls every listener in {@code snapshot}, logging what a listener throws so a faulty listener can't
     * interrupt a run. A fatal {@link Error} is the exception: it propagates (see {@link #rethrowIfFatal}).
     */
    private void notifyListeners(List<RuleListener> snapshot, String callback, Consumer<RuleListener> call) {
        for (RuleListener listener : snapshot) {
            try {
                call.accept(listener);
            } catch (Exception | Error e) {
                rethrowIfFatal(e);
                log.warn("Listener threw exception in {}", callback, e);
            }
        }
    }

    /**
     * Logs a run-time failure and tells every listener through {@link RuleListener#onError}, so each
     * {@code before*} callback still gets a closing call. Returns the exception for the caller to throw, unless
     * the cause is a fatal {@link Error}, which is rethrown unchanged once listeners have been told.
     */
    private RuleExecutionException failure(List<RuleListener> snapshot, CompiledRule rule, String msg,
                                           Throwable cause) {
        log.error(msg);
        RuleExecutionException error = cause == null
                ? new RuleExecutionException(msg)
                : new RuleExecutionException(msg, cause);
        notifyListeners(snapshot, "onError", listener -> listener.onError(listenerCopy(rule), error));
        rethrowIfFatal(cause);
        return error;
    }

    /**
     * Rethrows {@code thrown} if it is an {@link Error} the engine must not absorb. A {@link StackOverflowError}
     * (runaway recursion) or {@link AssertionError} ({@code assert} in a rule or listener) comes from the code
     * being run and is handled like an exception. Any other error, such as {@link OutOfMemoryError}, is left
     * for the caller to see unchanged.
     *
     * @param thrown What was caught, or {@code null}
     */
    private static void rethrowIfFatal(Throwable thrown) {
        if (thrown instanceof Error error && !(error instanceof StackOverflowError || error instanceof AssertionError)) {
            throw error;
        }
    }

    /**
     * Describes an exception for an error message. Many exceptions (NPEs, bare RuntimeExceptions) carry no
     * message, which would otherwise end the engine's message in {@code ": null"}.
     *
     * @param e The exception to describe
     * @return The exception's message, or its class name if it has none
     */
    static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    private CompiledRule compileRule(Rule rule, Imports imports) {
        String ruleName = rule.getRuleName() != null ? rule.getRuleName() : "(unnamed)";
        if (rule.getCondition() == null || rule.getCondition().isBlank()) {
            throw new RuleCompilationException(
                    "Rule '" + ruleName + "' has a null or blank condition expression");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            throw new RuleCompilationException(
                    "Rule '" + ruleName + "' has a null or blank action expression");
        }
        // ReadOnlyFacts only stops writes to a bare variable at run time. A property write such as
        // `claim.approved = true` goes through the fact's own setter, so assignments are rejected here instead.
        ConditionAssignments.Write write = ConditionAssignments.find(rule.getCondition());
        String condition = "Condition for rule '" + ruleName + "'";
        if (write != null && write.isStaticImport()) {
            throw new RuleCompilationException(condition + " uses import_static (at position " + write.position()
                    + "), which declares the method as a variable, and conditions can't declare variables. Call the "
                    + "method through its class instead, such as Math.max(a, b).");
        }
        if (write != null) {
            throw new RuleCompilationException(condition + " contains an assignment (" + write
                    + "). Conditions can't change facts or declare variables; use == to compare.");
        }
        try {
            Serializable compiledCondition = compileExpression(rule.getCondition(), imports);
            Serializable compiledAction = compileExpression(rule.getAction(), imports);
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
        } catch (Exception e) {
            String msg = "Can not compile rule '" + ruleName + "'. Error: " + describe(e);
            log.error(msg);
            throw new RuleCompilationException(msg, e);
        }
    }

    private static Serializable compileExpression(String expression, Imports imports) {
        // compileExpression alone accepts some malformed input (e.g. `x == == 1`) and defers the error to
        // run(). The analysis pass catches more of it up front.
        MVEL.analysisCompile(expression, newParserContext(imports));
        return MVEL.compileExpression(expression, newParserContext(imports));
    }

    /**
     * Creates a context used by exactly one compilation. MVEL records variables, their types and inline
     * {@code import} statements on the context and its configuration, and a compiled expression goes on using
     * its context when it first runs. A context shared across rules let one rule change how another compiled,
     * and let {@link #setRuleList(List)} modify it while a concurrent {@code run()} was still reading it.
     */
    private static ParserContext newParserContext(Imports imports) {
        ParserConfiguration configuration = new ParserConfiguration();
        imports.applyTo(configuration);
        return new ParserContext(configuration);
    }

    /**
     * Works out what an import string names. A class MVEL can load is imported on its own, so
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
            return ParseTools.forNameWithInner(name, Imports.contextClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            if (!isPackageName(name)) {
                throw new IllegalArgumentException("'" + name + "' is neither a class nor a valid package name", e);
            }
            return null;
        }
    }

    private static boolean isPackageName(String name) {
        for (String part : name.split("\\.", -1)) {
            if (!FactNames.isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }
}
