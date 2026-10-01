package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;

import java.util.function.Supplier;

/**
 * The first-match engine: conditions are evaluated in priority order until one is true, and only that rule's action
 * fires. The rules below the match are never evaluated, and the run's result reports them as not evaluated, so the
 * output object is shaped by one rule: the matching {@link Rule} with the highest priority.
 *
 * <p>
 * <b>Ties:</b> if several matching rules share the highest priority, the one that appears first in the list
 * passed to {@link #load(java.util.List)} is fired.
 * </p>
 *
 * @param <O> The output object type to instantiate when the rule's action expression is fired
 */
final class StatelessRulesEngine<O> extends AbstractRulesEngine<O> {

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
        super(outputFactory, configuration);
    }

    /**
     * Stops evaluating at the first rule whose condition is true, and only that rule's action fires. Conditions are
     * evaluated in priority order, so the rules below the match are never evaluated: they are neither matched nor
     * unmatched, and the result reports them as not evaluated. The output object is therefore shaped by only one
     * rule, the matching rule with the highest priority value.
     *
     * @return {@code true}
     */
    @Override
    boolean untilFirst() {
        // Evaluate in priority order and stop at the first match: the rules below it aren't evaluated, so a
        // broken lower-priority condition can't fail a run that is already decided.
        return true;
    }

    @Override
    String matchPolicy() {
        return "firstMatch";
    }
}
