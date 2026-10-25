package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;

/**
 * Which rules a {@link CoreRulesEngine} fires once a run has evaluated their conditions: one of three, each engine
 * built with one. The engine tells them apart by identity, in
 * {@link CoreRulesEngine#fire(RuleExecutor.Matches, CopyPool, CopyPool.Copy, RunFacts)}.
 */
final class MatchPolicy {

    /**
     * The first-match policy: conditions are evaluated in priority order until one is true, and only that rule's
     * action fires. The rules below the match are never evaluated, and the run's result reports them as not
     * evaluated, so the output object is shaped by one rule: the matching {@link Rule} with the highest priority. A
     * broken lower-priority condition therefore can't fail a run that is already decided.
     *
     * <p>
     * <b>Ties:</b> if several matching rules share the highest priority, the one that appears first in the list
     * passed to {@link CoreRulesEngine#load(java.util.List)} is fired.
     * </p>
     */
    static final MatchPolicy FIRST_MATCH = new MatchPolicy("firstMatch", true);

    /**
     * The all-matches policy: every condition is evaluated, in priority order, except those of rules the run skips,
     * and then the action of every {@link Rule} whose condition was true fires, in the same order, highest priority
     * first. The actions share one output object, so a lower-priority action can overwrite a field set by a
     * higher-priority one.
     *
     * <p>
     * <b>Match, then fire:</b> every condition is evaluated before any action runs, and actions never cause
     * conditions to be re-checked. A rule whose condition matched still fires even if a higher-priority action
     * changed the facts it depended on.
     * </p>
     *
     * <p>
     * <b>Not atomic:</b> if an action throws, the actions that already ran keep their effects on the output object
     * and on any fact objects they changed, and {@link RulesEngine#run(FactStore)} throws a
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} for the failing rule only.
     * </p>
     */
    static final MatchPolicy ALL_MATCHES = new MatchPolicy("allMatches", false);

    /**
     * The unique-match policy: fires the one rule whose condition is true, and fails the run when more than one is:
     * DMN's unique match policy, for a decision table whose rows must not overlap. Every condition is evaluated, in
     * priority order, except those of rules the run skips, before anything fires, so the failure names every rule that
     * matched. More than one match fires nothing, and fails the run before the output object is created.
     */
    static final MatchPolicy UNIQUE_MATCH = new MatchPolicy("uniqueMatch", false);

    /** The policy's name, which {@link io.github.brantunger.unruly.api.RunContext#matchPolicy()} reports. */
    final String label;

    /** Whether a run stops evaluating conditions at the first match. */
    final boolean untilFirst;

    private MatchPolicy(String label, boolean untilFirst) {
        this.label = label;
        this.untilFirst = untilFirst;
    }
}
