package com.unruly.service;

import com.unruly.model.Rule;

import java.util.List;
import java.util.stream.Collectors;

public class RuleEngine<I, O> {

    private final RuleParser<I, O> ruleParser;
    private final Factory<O> outputFactory;

    public RuleEngine(RuleParser<I, O> ruleParser,
                      Factory<O> outputFactory) {
        this.ruleParser = ruleParser;
        this.outputFactory = outputFactory;
    }

    /**
     * Run engine on set of rules for given data.
     *
     * @param listOfRules
     * @param inputData
     * @return
     */
    public O run(List<Rule> listOfRules, I inputData) {
        if (null == listOfRules || listOfRules.isEmpty()) {
            return null;
        }

        //STEP 1 (MATCH) : Match the facts and data against the set of rules.
        List<Rule> ruleList = match(listOfRules, inputData);

        //STEP 2 (RESOLVE) : Resolve the conflict and give the selected one rule.
        Rule resolvedRule = resolve(ruleList);
        if (null == resolvedRule) {
            return null;
        }

        //STEP 3 (EXECUTE) : Run the action of the selected rule on given data and return the output.
        return executeRule(resolvedRule, inputData);
    }

    /**
     * <p>
     * We can use here any pattern matching algo:
     * 1. Rete
     * 2. Linear
     * 3. Treat
     * 4. Leaps
     * Here we are using Linear matching algorithm for pattern matching.
     * </p>
     *
     * @param listOfRules
     * @param inputData
     * @return
     */
    private List<Rule> match(List<Rule> listOfRules, I inputData) {
        return listOfRules.stream()
                .filter(rule -> ruleParser.parseCondition(rule.getCondition(), inputData))
                .collect(Collectors.toList());
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

    /**
     * Execute selected rule on input data.
     *
     * @param rule
     * @param inputData
     * @return
     */
    private O executeRule(Rule rule, I inputData) {
        O outputResult = outputFactory.create();
        return ruleParser.parseAction(rule.getAction(), inputData, outputResult);
    }
}
