package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A StatelessRulesEngine is a concrete implementation that extends the {@link AbstractRulesEngine} class. In the
 * <strong>STATELESS</strong> implementation, the {@link io.github.brantunger.unruly.api.RulesEngine} fires the action of a single rule. All condition fields within the
 * ruleList are evaluated in the stateless rule engine. However, only a single action is fired. During conflict
 * resolution the {@link Rule} with the highest priority value is found first. The action field of the rule found first
 * will be the only action triggered. The output object is therefore shaped by only one rule: the matching rule with
 * the highest priority value.
 *
 * <p>
 * <b>Ties:</b> if several matching rules share the highest priority, the one that appears first in the list
 * passed to {@link #setRuleList(java.util.List)} is fired.
 * </p>
 *
 * @param <O> The output object type to instantiate when the rule's action expression is fired.
 */
final class StatelessRulesEngine<O> extends AbstractRulesEngine<O> {

    private final Supplier<O> outputFactory;

    /**
     * Construct a StatelessRulesEngine
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once
     *                      per run that matches a rule and must return a new, non-null object each time.
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    StatelessRulesEngine(Supplier<O> outputFactory) {
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Construct a StatelessRulesEngine that keeps at most {@code maxCopies} compiled copies of its rules.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once
     *                      per run that matches a rule and must return a new, non-null object each time.
     * @param maxCopies     The most compiled copies of the rules to keep, at least 1, as
     *                      {@link io.github.brantunger.unruly.api.RulesEngineBuilder#stateless(Supplier, int)}
     *                      describes
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    StatelessRulesEngine(Supplier<O> outputFactory, int maxCopies) {
        super(maxCopies);
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Run all the rules through a <b>STATELESS</b> rules engine and fire the action of a single rule. All condition
     * fields within the ruleList are evaluated in the stateless rule engine. However, only a single action is fired.
     * During conflict resolution the {@link Rule} with the highest priority value is found first. The action field of
     * the rule found first will be the only action triggered. The output object is therefore shaped by only one rule:
     * the matching rule with the highest priority value.
     *
     * @param facts The key/value fact store to run the rule engine against.
     * @return The object that is the result of the action getting fired against the given {@link Rule}, or
     *         {@code null} if the rule list is empty or no rule matched
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called, or the engine is closed
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public O run(FactStore<?> facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        return withCompiledRules((ruleSet, copy) -> {
            // Validated before the empty-list return, so an invalid fact is reported whatever the rules.
            Map<String, Object> entryMap = this.unwrapFacts(facts, ruleSet.factChecks());
            List<CompiledRule> rules = ruleSet.rules();
            if (rules.isEmpty()) {
                return null;
            }

            // Match the facts and data against the set of rules with highest priority first.
            List<CompiledRule> matchedRuleList = this.match(rules, copy, entryMap);

            // Resolve any conflicts and give the selected one rule.
            CompiledRule resolvedRule = this.resolve(matchedRuleList);
            if (null == resolvedRule) {
                return null;
            }

            // Run the action of the selected rule on given data and return the output.
            return this.executeRule(resolvedRule, copy, createOutput(outputFactory), entryMap);
        });
    }

    /**
     * Picks the rule to fire: the first matched rule, which has the highest priority because the list is sorted.
     *
     * @param ruleList The rule list to resolve the conflicts against
     * @return The {@link CompiledRule} object found first (the rule with the highest priority value)
     */
    private CompiledRule resolve(List<CompiledRule> ruleList) {
        return ruleList.stream().findFirst().orElse(null);
    }
}

