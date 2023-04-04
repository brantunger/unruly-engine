package com.unruly.service;

import com.unruly.model.Rule;

import java.util.List;

public class StatefulRulesEngine<I, O> extends AbstractRulesEngine<I, O> {

    private final Factory<O> outputFactory;

    public StatefulRulesEngine(RuleParser<I, O> ruleParser,
                                  Factory<O> outputFactory) {
        super(ruleParser);
        this.outputFactory = outputFactory;
    }

    @Override
    public O run(List<Rule> ruleList, I inputData) {
        if (null == ruleList || ruleList.isEmpty()) {
            return null;
        }

        // Match the facts and data against the set of rules with highest priority first.
        this.prioritySort(ruleList);
        List<Rule> matchedRuleList = this.match(ruleList, inputData);

        O outputObject = outputFactory.create();

        // Run the action of every rule on given data saving state each time
        for(Rule rule : matchedRuleList) {
            outputObject = this.executeRule(rule, inputData, outputObject);
        }

        return outputObject;
    }
}
