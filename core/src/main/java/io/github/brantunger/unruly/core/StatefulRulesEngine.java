package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The all-matches engine: every condition is evaluated, in priority order, and then the action of every {@link Rule}
 * whose condition was true fires, in the same order, highest priority first. The actions share one output object, so
 * a lower-priority action can overwrite a field set by a higher-priority one.
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
 * @param <O> The output object type to instantiate when the rule's action expression is fired
 */
final class StatefulRulesEngine<O> extends AbstractRulesEngine<O> {

    /**
     * Construct a StatefulRulesEngine.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once
     *                      per run that matches a rule and must return a new, non-null object each time.
     * @param configuration The builder's settings
     * @throws IllegalStateException    if the languages or the default language can't be resolved
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    StatefulRulesEngine(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        super(outputFactory, configuration);
    }

    /**
     * Once every condition has been evaluated in priority order, fires the action of every matched {@link Rule} in
     * that order, highest priority first. A rule the run skips isn't evaluated. The actions share one output object,
     * so a lower-priority action can overwrite a field set by a higher-priority one.
     *
     * @param matches {@inheritDoc}
     * @param ruleSet {@inheritDoc}
     * @param copy    {@inheritDoc}
     * @param facts   {@inheritDoc}
     * @return The accumulated output object resulting from firing the actions of all matching rules
     * @throws RuleExecutionException {@inheritDoc}
     */
    @Override
    RunResult<O> fire(Matches matches, RuleSet ruleSet, RuleSet.Copy copy, RunFacts facts) {
        O outputObject = createOutput();

        // Run the action of every rule on given data, saving state each time
        List<Rule> fired = new ArrayList<>();
        for (CompiledRule rule : matches.matched()) {
            outputObject = this.executeRule(rule, copy, outputObject, facts);
            fired.add(rule.rule());
        }

        return RunResult.of(outputObject, fired, matches.evaluations(), ruleSet.checksum());
    }

    @Override
    String matchPolicy() {
        return "allMatches";
    }
}
