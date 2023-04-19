package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;

/**
 * A StatelessRulesEngine is a concrete implementation that extends the {@link AbstractRulesEngine} class. In the
 * <strong>STATELESS</strong> the {@link RulesEngine} fires the action of a single rule. All condition fields within the
 * ruleList are evaluated in the stateless rule engine. However, only a single action is fired. During conflict
 * resolution the {@link Rule} with the highest priority value is found first. The action field of the rule found first
 * will be the only action triggered. The output object is therefore generated based on only one rule. The rule with the
 * highest priority value.
 *
 * @param <O> The output object type to instantiate when the rule's action expression is fired.
 */
public class StatelessRulesEngine<O> extends AbstractRulesEngine<O> {

    private final Factory<O> outputFactory;

    public StatelessRulesEngine(Parser<O> ruleParser,
                                Factory<O> outputFactory) {
        super(ruleParser);
        this.outputFactory = outputFactory;
    }

    /**
     * Run all the rules through a <b>STATELESS</b> rules engine and fire the action of a single rule. All condition
     * fields within the ruleList are evaluated in the stateless rule engine. However, only a single action is fired.
     * During conflict resolution the {@link Rule} with the highest priority value is found first. The action field of
     * the rule found first will be the only action triggered. The output object is therefore generated based on only
     * one rule. The rule with the highest priority value.
     *
     * @param ruleList This is a list of {@link Rule} objects to run through the rules engine
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     */
    @Override
    public O run(List<Rule> ruleList, FactStore<Object> facts) {
        if (null == ruleList || ruleList.isEmpty()) {
            return null;
        }

        // Match the facts and data against the set of rules with highest priority first.
        this.prioritySort(ruleList);
        List<Rule> matchedRuleList = this.match(ruleList, facts);

        // Resolve any conflicts and give the selected one rule.
        Rule resolvedRule = resolve(matchedRuleList);
        if (null == resolvedRule) {
            return null;
        }

        // Run the action of the selected rule on given data and return the output.
        return this.executeRule(resolvedRule, outputFactory.create());
    }


    /**
     * <pre>
     * We can use here any resolving techniques:
     * 1. Lex
     * 2. Recency
     * 3. MEA
     * 4. Refactor
     * 5. Priority wise
     * Here we are using find first rule logic.
     * </pre>
     *
     * @param ruleList The rule list to resolve the conflicts against
     * @return The {@link Rule} object found first (the rule with the highest priority value)
     */
    private Rule resolve(List<Rule> ruleList) {
        return ruleList.stream().findFirst().orElse(null);
    }
}
