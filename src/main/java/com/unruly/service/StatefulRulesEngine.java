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

    /**
     * Run all the rules through a <b>STATEFUL</b> rules engine and fire all the actions of the rules when the condition
     * field of the {@link Rule} where the condition returns true. In the stateful rules engine the rules are sorted by
     * priority. The highest priority wins. The output object saves state in between each rule, so rules with lower
     * priority may override the fields in the output object.
     *
     * @param ruleList  This is a list of {@link Rule} objects to run through the rules engine
     * @param inputData The set of input data to fire the rules engine against
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
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
        for (Rule rule : matchedRuleList) {
            outputObject = this.executeRule(rule, inputData, outputObject);
        }

        return outputObject;
    }
}
