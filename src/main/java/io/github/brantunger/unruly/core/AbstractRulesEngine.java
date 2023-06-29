package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The AbstractRulesEngine is an abstract implementation of the {@link RulesEngine} interface. The RulesEngine fires the
 * action expression from a list of {@link Rule} objects when their conditions evaluate to <strong>true</strong>.
 *
 * @param <O> The output object to instantiate
 */
public abstract class AbstractRulesEngine<O> implements RulesEngine<O> {

    private final Parser<O> ruleParser;

    protected AbstractRulesEngine(Parser<O> ruleParser) {
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
     * @param ruleList This is a list of {@link Rule} objects to filter based on when condition expression parses to
     *                 true
     * @param facts    The key/value fact store to run the rule engine against.
     * @return List of {@link Rule} objects where their condition evaluated to <b>true</b>
     */
    protected List<Rule> match(List<Rule> ruleList, FactStore<Object> facts) {
        return ruleList.stream()
                .filter(rule -> ruleParser.parseCondition(rule.getSerializedCondition(), facts))
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
        return ruleParser.parseAction(rule.getSerializedAction(), outputObject);
    }

    /**
     * The method to implement to tell the concrete rules engine how to fire rules against the input data object
     *
     * @param ruleList This is a list of {@link Rule} objects to run through the rules engine
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
    public abstract O run(List<Rule> ruleList, FactStore<Object> facts);
}
