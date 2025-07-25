package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;

/**
 * A StatefulRulesEngine is a concrete implementation that extends the {@link AbstractRulesEngine} class. In the
 * <strong>STATEFUL</strong> implementation the rules engine fires all the actions of the rules when the condition
 * field of the {@link Rule} condition returns true. In the stateful rules engine, the rules are sorted by priority.
 * The highest priority wins. The output object saves state in between each rule, so rules with lower priority may
 * override the fields in the output object.
 *
 * @param <O> The output object type to instantiate when the rule's action expression is fired.
 */
public class StatefulRulesEngine<O> extends AbstractRulesEngine<O> {

    private final Factory<O> outputFactory;

    /**
     * Construct a StatefulRulesEngine.
     *
     * @param outputFactory The {@link Factory} to use to instantiate the output object with
     */
    public StatefulRulesEngine(Factory<O> outputFactory) {
        this.outputFactory = outputFactory;
    }

    /**
     * Run all the rules through a <b>STATEFUL</b> rules engine and fire all the actions of the rules when the condition
     * field of the {@link Rule} condition returns true. In the stateful rules engine the rules are sorted by priority.
     * The highest priority wins. The output object saves state in between each rule, so rules with lower priority may
     * override the fields in the output object.
     *
     * @param facts The input fact store to run rules against
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
    @Override
    public O run(FactStore<Object> facts) {
        if (null == this.ruleList || ruleList.isEmpty()) {
            return null;
        }

        // Match the facts and data against the set of rules with the highest priority first.
        List<Rule> matchedRuleList = this.match(ruleList, facts);

        O outputObject = outputFactory.create();

        // Run the action of every rule on given data, saving state each time
        for (Rule rule : matchedRuleList) {
            outputObject = this.executeRule(rule, outputObject);
        }

        return outputObject;
    }
}
