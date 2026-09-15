package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A StatefulRulesEngine is a concrete implementation that extends the {@link AbstractRulesEngine} class. In the
 * <strong>STATEFUL</strong> implementation the rules engine fires the action of every {@link Rule} whose condition
 * returns true. In the stateful rules engine, the rules are sorted by priority.
 * Matching actions fire in priority order, highest first. They share one output object, so a lower-priority action
 * can overwrite a field set by a higher-priority one.
 *
 * <p>
 * <b>Match, then fire:</b> every condition is evaluated before any action runs, and actions never cause
 * conditions to be re-checked. A rule whose condition matched still fires even if a higher-priority action
 * changed the facts it depended on.
 * </p>
 *
 * <p>
 * <b>Not atomic:</b> if an action throws, the actions that already ran keep their effects on the output object
 * and on any fact objects they changed, and {@link #run(FactStore)} throws a
 * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} for the failing rule only.
 * </p>
 *
 * @param <O> The output object type to instantiate when the rule's action expression is fired.
 */
final class StatefulRulesEngine<O> extends AbstractRulesEngine<O> {

    private final Supplier<O> outputFactory;

    /**
     * Construct a StatefulRulesEngine.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once
     *                      per run that matches a rule and must return a new, non-null object each time.
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    StatefulRulesEngine(Supplier<O> outputFactory) {
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Construct a StatefulRulesEngine that keeps at most {@code maxCopies} compiled copies of its rules.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once
     *                      per run that matches a rule and must return a new, non-null object each time.
     * @param maxCopies     The most compiled copies of the rules to keep, at least 1, as
     *                      {@link io.github.brantunger.unruly.api.RulesEngineBuilder#stateful(Supplier, int)}
     *                      describes
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    StatefulRulesEngine(Supplier<O> outputFactory, int maxCopies) {
        super(maxCopies);
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Run all the rules through a <b>STATEFUL</b> rules engine and fire the action of every {@link Rule} whose
     * condition returns true. In the stateful rules engine the rules are sorted by priority.
     * Matching actions fire in priority order, highest first. They share one output object, so a lower-priority
     * action can overwrite a field set by a higher-priority one.
     *
     * @param facts The input fact store to run rules against
     * @return The accumulated output object resulting from firing the actions of all matching rules, or
     *         {@code null} if the rule list is empty or no rule matched
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException if {@link #setRuleList(List)} has not been called, or the engine is closed
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public O run(FactStore<Object> facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        return withCompiledRules((ruleSet, copy) -> {
            // Validated before the empty-list return, so an invalid fact is reported whatever the rules.
            Map<String, Object> entryMap = this.unwrapFacts(facts, ruleSet.factChecks());
            List<CompiledRule> rules = ruleSet.rules();
            if (rules.isEmpty()) {
                return null;
            }

            // Match the facts and data against the set of rules with the highest priority first.
            List<CompiledRule> matchedRuleList = this.match(rules, copy, entryMap);
            if (matchedRuleList.isEmpty()) {
                return null;
            }

            O outputObject = createOutput(outputFactory);

            // Run the action of every rule on given data, saving state each time
            for (CompiledRule rule : matchedRuleList) {
                outputObject = this.executeRule(rule, copy, outputObject, entryMap);
            }

            return outputObject;
        });
    }
}
