package io.github.brantunger.unruly.core;

import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * @param <O> The output object to instantiate
 */
@Slf4j
public abstract class AbstractRulesEngine<O> implements RulesEngine<O> {

    private static final String OUTPUT_KEYWORD = "output";
    private final ParserContext parserContext = new ParserContext(new ParserConfiguration());
    private final List<RuleListener> listeners = new ArrayList<>();
    private List<CompiledRule> compiledRules;

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
     * Registers a single {@link RuleListener} to monitor rule evaluation and execution.
     *
     * @param listener The listener to register.
     * @return A reference to this {@link RulesEngine}
     */
    @Override
    public RulesEngine<O> registerListener(RuleListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
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
        if (listeners != null) {
            this.listeners.addAll(listeners);
        }
        return this;
    }

    /**
     * Set the list of rules used for processing in the Rules Engine.
     * Rules are sorted by priority in descending order (highest priority first).
     * Rules with a {@code null} priority are treated as lowest priority.
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
     */
    protected Map<String, Object> unwrapFacts(FactStore<Object> facts) {
        Map<String, Object> entryMap = new HashMap<>();
        for (Map.Entry<String, FactReference<Object>> entry : facts.entrySet()) {
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

    private boolean parseCondition(CompiledRule rule, Map<String, Object> entryMap) {
        Map<String, Object> unmodifiableMap = Collections.unmodifiableMap(entryMap);
        for (RuleListener listener : listeners) {
            try {
                listener.beforeEvaluate(rule.rule(), unmodifiableMap);
            } catch (Exception e) {
                log.warn("Listener threw exception in beforeEvaluate", e);
            }
        }

        boolean result;
        try {
            result = MVEL.executeExpression(rule.compiledCondition(), (Object) null, entryMap, Boolean.class);
        } catch (Exception e) {
            String msg = "Failed to evaluate condition for rule '" + rule.rule().getRuleName() + "': " + e.getMessage();
            log.error(msg);
            throw new RuleExecutionException(msg, e);
        }

        for (RuleListener listener : listeners) {
            try {
                listener.afterEvaluate(rule.rule(), unmodifiableMap, result);
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
            String msg = "Failed to execute action for rule '" + rule.rule().getRuleName() + "': " + e.getMessage();
            log.error(msg);
            throw new RuleExecutionException(msg, e);
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

    private CompiledRule compileRule(Rule rule) {
        String ruleName = rule.getRuleName() != null ? rule.getRuleName() : "(unnamed)";
        if (rule.getCondition() == null || rule.getCondition().isBlank()) {
            throw new IllegalArgumentException(
                    "Rule '" + ruleName + "' has a null or blank condition expression");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            throw new IllegalArgumentException(
                    "Rule '" + ruleName + "' has a null or blank action expression");
        }
        try {
            Serializable compiledCondition = MVEL.compileExpression(rule.getCondition(), parserContext);
            Serializable compiledAction = MVEL.compileExpression(rule.getAction(), parserContext);
            return new CompiledRule(rule, compiledCondition, compiledAction);
        } catch (Exception e) {
            String msg = "Can not compile rule '" + ruleName + "'. Error: " + e.getMessage();
            log.error(msg);
            throw new RuleCompilationException(msg, e);
        }
    }
}
