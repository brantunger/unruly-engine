package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RunResult;

import java.time.Duration;
import java.time.Instant;
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
 * passed to {@link #load(java.util.List)} is fired.
 * </p>
 *
 * @param <O> The output object type to instantiate when the rule's action expression is fired.
 */
final class StatelessRulesEngine<O> extends AbstractRulesEngine<O> {

    private final Supplier<O> outputFactory;

    /**
     * Construct a StatelessRulesEngine.
     *
     * @param outputFactory The {@link Supplier} to use to instantiate the output object with. It is called once
     *                      per run that matches a rule and must return a new, non-null object each time.
     * @param configuration The builder's settings
     * @throws IllegalStateException    if the languages or the default language can't be resolved
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    StatelessRulesEngine(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        super(configuration);
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Fires the action of the first rule whose condition is true. Conditions are evaluated in priority order, and the
     * run stops at the first match, so the rules below it are never evaluated: they are neither matched nor unmatched.
     * The output object is therefore shaped by only one rule, the matching rule with the highest priority value.
     *
     * @param facts   The key/value fact store to run the rule engine against.
     * @param timeout How long the run may take, or {@code null} if it has no deadline
     * @return The object that is the result of the action getting fired against the given {@link Rule}, or
     *         {@code null} if the rule list is empty or no rule matched
     */
    @Override
    RunResult<O> runRules(FactStore<?> facts, Duration timeout) {
        return runInScope(facts, timeout, (ruleSet, copy, entryMap, deadline) -> {
            List<CompiledRule> rules = ruleSet.rules();
            if (rules.isEmpty()) {
                return RunResult.of(null, List.of(), ruleSet.checksum());
            }

            // Evaluate in priority order and stop at the first match: the rules below it aren't evaluated, so a
            // broken lower-priority condition can't fail a run that is already decided.
            CompiledRule resolvedRule = this.firstMatch(rules, copy, entryMap, deadline);
            if (null == resolvedRule) {
                return RunResult.of(null, List.of(), ruleSet.checksum());
            }

            // Run the action of the selected rule on given data and return the output.
            O output = this.executeRule(resolvedRule, copy, createOutput(outputFactory), entryMap, deadline);
            return RunResult.of(output, List.of(resolvedRule.rule()), ruleSet.checksum());
        });
    }

    @Override
    String matchPolicy() {
        return "firstMatch";
    }

    /**
     * Returns the first rule whose condition is true, evaluating them in priority order and stopping there.
     *
     * @param ruleList The rules, in evaluation order
     * @param copy     The run's copy of the rules, whose sessions the conditions run with
     * @param entryMap The run's facts
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return The first matching rule, or {@code null} if none matched
     */
    private CompiledRule firstMatch(List<CompiledRule> ruleList, RuleSet.Copy copy, Map<String, Object> entryMap,
                                    Instant deadline) {
        for (CompiledRule rule : ruleList) {
            if (this.matches(rule, copy, entryMap, deadline)) {
                return rule;
            }
        }
        return null;
    }
}

