package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.util.List;
import java.util.function.Supplier;

/**
 * An engine that fires the one rule whose condition is true, and fails the run when more than one is: DMN's unique
 * match policy, for a decision table whose rows must not overlap. Every condition is evaluated, in priority order,
 * before anything fires, so the failure names every rule that matched.
 *
 * @param <O> The output object type to instantiate when the rule's action expression is fired.
 */
final class UniqueMatchRulesEngine<O> extends AbstractRulesEngine<O> {

    // How many rules may match in one run: the policy's whole point.
    private static final int ALLOWED_MATCHES = 1;

    /**
     * Construct a UniqueMatchRulesEngine.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once
     *                      per run that matches exactly one rule and must return a new, non-null object each time.
     * @param configuration The builder's settings
     * @throws IllegalStateException    if the languages or the default language can't be resolved
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    UniqueMatchRulesEngine(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        super(outputFactory, configuration);
    }

    /**
     * Fires the action of the one rule that matched, once every condition has been evaluated, except those of rules
     * the run skips. More than one match fires nothing, and fails the run before the output object is created.
     *
     * @param matches {@inheritDoc}
     * @param ruleSet {@inheritDoc}
     * @param copy    {@inheritDoc}
     * @param facts   {@inheritDoc}
     * @return The object that is the result of the action getting fired against the given {@link Rule}
     * @throws RuleExecutionException if more than one rule matched: it names each of them, in priority order, with
     *                                the list cut at 1,000 characters like text copied from an exception, and belongs
     *                                to no rule; or, when one rule matched, {@inheritDoc}
     */
    @Override
    RunResult<O> fire(Matches matches, RuleSet ruleSet, RuleSet.Copy copy, RunFacts facts) {
        List<CompiledRule> matched = matches.matched();
        if (matched.size() > ALLOWED_MATCHES) {
            // The list of names is cut like text copied from an exception: a table whose rows all match would
            // otherwise put every name in one log line. It's escaped after the cut, so the cut can't split an
            // escape.
            throw failedRun(matched.size() + " rules matched, but a unique-match engine allows one: "
                    + Failures.quoteJoined(matched.stream().map(rule -> rule.rule().getRuleName())));
        }
        return super.fire(matches, ruleSet, copy, facts);
    }

    @Override
    String matchPolicy() {
        return "uniqueMatch";
    }
}
