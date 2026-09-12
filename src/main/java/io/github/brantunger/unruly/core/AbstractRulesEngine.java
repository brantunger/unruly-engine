package io.github.brantunger.unruly.core;

import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final ParserContext parserContext = new ParserContext(new ParserConfiguration());
    // Copy-on-write: callbacks iterate a snapshot, so registering a listener from another thread
    // or from inside a callback can't throw ConcurrentModificationException out of run().
    private final List<RuleListener> listeners = new CopyOnWriteArrayList<>();
    // Volatile so a setRuleList() call on one thread is seen by run() on others. The list is fully built
    // before it is assigned and never modified afterwards, so a single volatile write is enough.
    private volatile List<CompiledRule> compiledRules;

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
     * rules are
     * compiled against the current {@link org.mvel2.ParserContext} at the time of
     * this call.
     * </p>
     *
     * @param ruleList The List of {@link Rule} objects to compile.
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
        this.compiledRules = ruleList.stream()
                .sorted(Comparator.comparing(
                        Rule::getPriority,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .map(this::compileRule)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * This method adds package imports to the {@link org.mvel2.ParserContext} in
     * order to speed up the execution of rules and simplify the rule expression.
     * You may want to use this if many of your rules require the same packages.
     *
     * <p>
     * Imports are accumulated across multiple calls. This method must be called
     * <em>before</em> {@link #setRuleList(List)} for the imports to take effect.
     * </p>
     *
     * @param packages A set of packages to import
     * @return A reference to this {@link RulesEngine}
     */
    @Override
    public RulesEngine<O> addImports(Set<String> packages) {
        Objects.requireNonNull(packages, "packages must not be null");
        for (String pkg : packages) {
            Objects.requireNonNull(pkg, "package element must not be null");
            parserContext.getParserConfiguration().addPackageImport(pkg);
        }
        return this;
    }

    /**
     * This adds a single package to the {@link org.mvel2.ParserContext} in order to
     * speed up the execution of rules and simplify the rule expression. Add the
     * fully qualified name of the package as a string. You may want to use this if
     * many of your rules require the same packages.
     *
     * <p>
     * Imports are accumulated across multiple calls. This method must be called
     * <em>before</em> {@link #setRuleList(List)} for the imports to take effect.
     * </p>
     *
     * @param packageString The package to import. Example: "java.util"
     * @return A reference to this {@link RulesEngine}
     */
    @Override
    public RulesEngine<O> addImport(String packageString) {
        Objects.requireNonNull(packageString, "packageString must not be null");
        parserContext.getParserConfiguration().addPackageImport(packageString);
        return this;
    }

    /**
     * Unwraps the FactStore into a Map of values to be used as context variables in MVEL evaluation.
     * This should be called once per engine run to avoid expensive allocations.
     *
     * @param facts The key/value fact store
     * @return A map of variable names to their values
     * @throws IllegalArgumentException if a fact is named {@code output}, which actions reserve
     *                                  for the output object
     */
    protected Map<String, Object> unwrapFacts(FactStore<Object> facts) {
        Map<String, Object> entryMap = new HashMap<>();
        for (Map.Entry<String, FactReference<Object>> entry : facts.entrySet()) {
            // Actions bind the output object to this name, silently hiding a fact of the same name.
            if (OUTPUT_KEYWORD.equals(entry.getKey())) {
                throw new IllegalArgumentException("'" + OUTPUT_KEYWORD
                        + "' is reserved for the output object and cannot be used as a fact name");
            }
            if (entry.getValue() != null) {
                entryMap.put(entry.getKey(), entry.getValue().getValue());
            }
        }
        return entryMap;
    }

    /**
     * <p>
     * We can use here any pattern matching algorithm:
     * 1. Rete
     * 2. Linear
     * 3. Treat
     * 4. Leaps
     * Here we are using Linear matching algorithm for pattern matching.
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
     * @param rule         The rule object to obtain the action expression to fire
     *                     the rule for
     * @param outputObject an empty output object to set output data into
     * @param entryMap     The pre-built map of unwrapped facts to use as execution context.
     * @return The object that is the result of the action getting fired against the
     *         given {@link CompiledRule}
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
     * @throws RuleExecutionException if the factory throws or returns {@code null}
     */
    protected O createOutput(Supplier<O> outputFactory) {
        O output;
        try {
            output = outputFactory.get();
        } catch (Exception e) {
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
        Map<String, Object> readOnlyFacts = new ReadOnlyFacts(entryMap);
        for (RuleListener listener : listeners) {
            try {
                listener.beforeEvaluate(rule.rule(), readOnlyFacts);
            } catch (Exception e) {
                log.warn("Listener threw exception in beforeEvaluate", e);
            }
        }

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing.
        Object evaluated;
        try {
            evaluated = MVEL.executeExpression(rule.compiledCondition(), (Object) null, readOnlyFacts);
        } catch (Exception e) {
            throw failure(rule, "Failed to evaluate condition for rule '" + rule.displayName() + "': "
                    + describe(e), e);
        }

        // Unboxing a null here would surface as an internal NPE naming MVEL's own
        // signature, which tells the caller nothing about their rule.
        if (evaluated == null) {
            throw failure(rule, "Condition for rule '" + rule.displayName()
                    + "' evaluated to null. A condition expression must evaluate to a boolean.", null);
        }

        if (!(evaluated instanceof Boolean result)) {
            throw failure(rule, "Condition for rule '" + rule.displayName() + "' evaluated to a "
                    + evaluated.getClass().getName() + ". A condition expression must evaluate to a boolean.", null);
        }

        for (RuleListener listener : listeners) {
            try {
                listener.afterEvaluate(rule.rule(), readOnlyFacts, result);
            } catch (Exception e) {
                log.warn("Listener threw exception in afterEvaluate", e);
            }
        }

        return result;
    }

    private O parseAction(CompiledRule rule, O outputResult, Map<String, Object> entryMap) {
        for (RuleListener listener : listeners) {
            try {
                listener.beforeExecute(rule.rule(), outputResult);
            } catch (Exception e) {
                log.warn("Listener threw exception in beforeExecute", e);
            }
        }

        // Create a copy so we don't mutate the shared fact map with the output keyword
        Map<String, Object> input = new HashMap<>(entryMap);
        input.put(OUTPUT_KEYWORD, outputResult);
        try {
            MVEL.executeExpression(rule.compiledAction(), (Object) null, input);
        } catch (Exception e) {
            throw failure(rule, "Failed to execute action for rule '" + rule.displayName() + "': "
                    + describe(e), e);
        }

        for (RuleListener listener : listeners) {
            try {
                listener.afterExecute(rule.rule(), outputResult);
            } catch (Exception e) {
                log.warn("Listener threw exception in afterExecute", e);
            }
        }

        return outputResult;
    }

    /**
     * Logs a run-time failure and tells every listener through {@link RuleListener#onError}, so each
     * {@code before*} callback still gets a closing call. Returns the exception for the caller to throw.
     */
    private RuleExecutionException failure(CompiledRule rule, String msg, Exception cause) {
        log.error(msg);
        RuleExecutionException error = cause == null
                ? new RuleExecutionException(msg)
                : new RuleExecutionException(msg, cause);
        for (RuleListener listener : listeners) {
            try {
                listener.onError(rule.rule(), error);
            } catch (Exception e) {
                log.warn("Listener threw exception in onError", e);
            }
        }
        return error;
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

    private CompiledRule compileRule(Rule rule) {
        String ruleName = rule.getRuleName() != null ? rule.getRuleName() : "(unnamed)";
        if (rule.getCondition() == null || rule.getCondition().isBlank()) {
            throw new RuleCompilationException(
                    "Rule '" + ruleName + "' has a null or blank condition expression");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            throw new RuleCompilationException(
                    "Rule '" + ruleName + "' has a null or blank action expression");
        }
        try {
            Serializable compiledCondition = compileExpression(rule.getCondition());
            Serializable compiledAction = compileExpression(rule.getAction());
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

    private Serializable compileExpression(String expression) {
        // compileExpression alone accepts some malformed input (e.g. `x == == 1`) and defers the error to
        // run(). The analysis pass catches more of it up front. It gets its own ParserContext because
        // analysis records variables on the context, but it shares the configuration so imports resolve.
        MVEL.analysisCompile(expression, new ParserContext(parserContext.getParserConfiguration()));
        return MVEL.compileExpression(expression, parserContext);
    }
}
