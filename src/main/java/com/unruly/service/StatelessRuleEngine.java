package com.unruly.service;

import com.unruly.model.Rule;

import java.util.List;

public class StatelessRuleEngine<I, O> extends AbstractRulesEngine<I, O> {

    private final Factory<O> outputFactory;

    public StatelessRuleEngine(RuleParser<I, O> ruleParser,
                               Factory<O> outputFactory) {
        super(ruleParser);
        this.outputFactory = outputFactory;
    }

    /**
     * Run engine on set of rules for given data.
     *
     * @param ruleList
     * @param inputData
     * @return
     */
    @Override
    public O run(List<Rule> ruleList, I inputData) {
        if (null == ruleList || ruleList.isEmpty()) {
            return null;
        }

        // Match the facts and data against the set of rules with highest priority first.
        this.prioritySort(ruleList);
        List<Rule> matchedRuleList = this.match(ruleList, inputData);

        // Resolve any conflicts and give the selected one rule.
        Rule resolvedRule = resolve(matchedRuleList);
        if (null == resolvedRule) {
            return null;
        }

        // Run the action of the selected rule on given data and return the output.
        return this.executeRule(resolvedRule, inputData, outputFactory.create());
    }


    /**
     * <p>
     * We can use here any resolving techniques:
     * 1. Lex
     * 2. Recency
     * 3. MEA
     * 4. Refactor
     * 5. Priority wise
     * Here we are using find first rule logic.
     * </p>
     *
     * @param ruleList
     * @return
     */
    private Rule resolve(List<Rule> ruleList) {
        return ruleList.stream().findFirst().orElse(null);
    }
}
