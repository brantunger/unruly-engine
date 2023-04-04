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
     * Sort rules by priority descending
     *
     * @param ruleList
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
     * @param ruleList
     * @param inputData
     * @return
     */
    protected List<Rule> match(List<Rule> ruleList, I inputData) {
        return ruleList.stream()
                .filter(rule -> ruleParser.parseCondition(rule.getCondition(), inputData))
                .collect(Collectors.toList());
    }


    /**
     * Execute selected rule on input data.
     *
     * @param rule
     * @param inputData
     * @return
     */
    protected O executeRule(Rule rule, I inputData, O outputObject) {
        return ruleParser.parseAction(rule.getAction(), inputData, outputObject);
    }

    public abstract O run(List<Rule> ruleList, I inputData);
}
