package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The AbstractRulesEngine is an abstract implementation of the {@link RulesEngine} interface. The RulesEngine fires the
 * action expression from a list of {@link Rule} objects when their conditions evaluate to <strong>true</strong>.
 *
 * @param <O> The output object to instantiate
 */
@Slf4j
public abstract class AbstractRulesEngine<O> implements RulesEngine<O> {

    private static final String OUTPUT_KEYWORD = "output";
    protected List<Rule> ruleList;
    private ParserContext parserContext;

    /**
     * The method to implement to tell the concrete rules engine how to fire rules against the input data object
     *
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
    public abstract O run(FactStore<Object> facts);

    /**
     * Set the list of rules used for processing in the Rules Engine
     *
     * @param ruleList The List of {@link Rule} objects to compile.
     */
    @Override
    public void setRuleList(List<Rule> ruleList) {
        this.ruleList = new ArrayList<>(ruleList.stream()
                .peek(rule -> {
                    if (parserContext != null) {
                        rule.setSerializedCondition(MVEL.compileExpression(rule.getCondition(), parserContext));
                        rule.setSerializedAction(MVEL.compileExpression(rule.getAction(), parserContext));
                    } else {
                        rule.setSerializedCondition(MVEL.compileExpression(rule.getCondition()));
                        rule.setSerializedAction(MVEL.compileExpression(rule.getAction()));
                    }
                }).toList());

        prioritySort(ruleList);
    }

    /**
     * This method adds package imports to the {@link org.mvel2.ParserContext} in order to speed up the execution of
     * rules and simplify the rule expression. You may want to use this if many of your rules require the same packages.
     *
     * @param packages A set of packages to import
     * @return A reference to this {@link RulesEngine}
     */
    @Override
    public RulesEngine<O> addImports(Set<String> packages) {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setPackageImports(new HashSet<>(packages));
        parserContext = new ParserContext(parserConfiguration);
        return this;
    }

    /**
     * This adds a single package to the {@link org.mvel2.ParserContext} in order to speed up the execution of
     * rules and simplify the rule expression. Add the fully qualified name of the package as a string. You may want to
     * use this if many of your rules require the same packages.
     *
     * @param packageString The package to import. Example: "java.util.Set"
     * @return A reference to this {@link RulesEngine}
     */
    @Override
    public RulesEngine<O> addImport(String packageString) {
        if (parserContext != null) {
            parserContext.getParserConfiguration().addPackageImport(packageString);
        } else {
            ParserConfiguration parserConfiguration = new ParserConfiguration();
            parserConfiguration.addPackageImport(packageString);
            parserContext = new ParserContext(parserConfiguration);
        }

        return this;
    }

    /**
     * Sort rules by priority descending. Highest priority wins
     *
     * @param ruleList This is a list of {@link Rule} objects to sort
     */
    protected void prioritySort(List<Rule> ruleList) {
        ruleList.sort(Comparator.comparing(Rule::getPriority).reversed());
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
     * @param ruleList This is a list of {@link Rule} objects to filter based on when condition expression parses to
     *                 true
     * @param facts    The key/value fact store to run the rule engine against.
     * @return List of {@link Rule} objects where their condition evaluated to <b>true</b>
     */
    protected List<Rule> match(List<Rule> ruleList, FactStore<Object> facts) {
        return ruleList.stream()
                .filter(rule -> parseCondition(rule.getSerializedCondition(), facts))
                .collect(Collectors.toList());
    }


    /**
     * Execute a single {@link Rule} object's action field against the input data
     *
     * @param rule         The rule object to obtain the action expression to fire the rule for
     * @param outputObject an empty output object to set output data into
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
    protected O executeRule(Rule rule, O outputObject) {
        return parseAction(rule.getSerializedAction(), outputObject);
    }

    private boolean parseCondition(Serializable expression, FactStore<Object> facts) {
        Map<String, Object> entryMap = facts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getValue()));
        try {
            if (parserContext != null)
                return MVEL.executeExpression(expression, parserContext, entryMap, Boolean.class);
            else return MVEL.executeExpression(expression, entryMap, Boolean.class);
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression Error: {}", e.getMessage());
            throw e;
        }
    }

    private O parseAction(Serializable compiledExpression, O outputResult) {
        Map<String, Object> input = new HashMap<>();
        input.put(OUTPUT_KEYWORD, outputResult);
        try {
            if (parserContext != null) MVEL.executeExpression(compiledExpression, parserContext, input);
            else MVEL.executeExpression(compiledExpression, input);
            return outputResult;
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression Error: {}", e.getMessage());
            throw e;
        }
    }
}
