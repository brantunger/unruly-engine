package com.unruly.service;

import com.unruly.model.Rule;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractRulesEngine<I, O> implements RulesEngine<I, O> {

    private final RuleParser<I, O> ruleParser;

    protected AbstractRulesEngine(RuleParser<I, O> ruleParser) {
        this.ruleParser = ruleParser;
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
     * @param ruleList  This is a list of {@link Rule} objects to filter based on when condition expression parses to
     *                  true
     * @param inputData The input data to match condition logic against
     * @return List of {@link Rule} objects where their condition evaluated to <b>true</b>
     */
    protected List<Rule> match(List<Rule> ruleList, I inputData) {
        return ruleList.stream()
                .filter(rule -> ruleParser.parseCondition(rule.getCondition(), inputData))
                .collect(Collectors.toList());
    }


    /**
     * Execute a single {@link Rule} object's action field against the input data
     *
     * @param rule         The rule object to obtain the action expression to fire the rule for
     * @param inputData    The input data to execute the rule against
     * @param outputObject an empty output object to set output data into
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
    protected O executeRule(Rule rule, I inputData, O outputObject) {
        return ruleParser.parseAction(rule.getAction(), inputData, outputObject);
    }

    /**
     * The method to implement to tell the concrete rules engine how to fire rules against the input data object
     *
     * @param ruleList  This is a list of {@link Rule} objects to run through the rules engine
     * @param inputData The set of input data to fire the rules engine against
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
    public abstract O run(List<Rule> ruleList, I inputData);
}
