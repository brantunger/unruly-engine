package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

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

    private final Supplier<O> outputFactory;

    /**
     * Construct a StatefulRulesEngine.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with
     */
    public StatefulRulesEngine(Supplier<O> outputFactory) {
        this.outputFactory = outputFactory;
    }

    /**
     * Run all the rules through a <b>STATEFUL</b> rules engine and fire all the actions of the rules when the condition
     * field of the {@link Rule} condition returns true. In the stateful rules engine the rules are sorted by priority.
     * The highest priority wins. The output object saves state in between each rule, so rules with lower priority may
     * override the fields in the output object.
     *
     * @param facts The input fact store to run rules against
     * @return The accumulated output object resulting from firing the actions of all matching rules
     */
    @Override
    public O run(FactStore<Object> facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        List<CompiledRule> rules = getCompiledRules();
        if (null == rules || rules.isEmpty()) {
            return null;
        }

        Map<String, Object> entryMap = this.unwrapFacts(facts);

        // Match the facts and data against the set of rules with the highest priority first.
        List<CompiledRule> matchedRuleList = this.match(rules, entryMap);
        if (matchedRuleList.isEmpty()) {
            return null;
        }

        O outputObject = outputFactory.get();

        // Run the action of every rule on given data, saving state each time
        for (CompiledRule rule : matchedRuleList) {
            outputObject = this.executeRule(rule, outputObject, entryMap);
        }

        return outputObject;
    }
}
