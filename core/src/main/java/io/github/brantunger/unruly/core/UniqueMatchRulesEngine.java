package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

    private final Supplier<O> outputFactory;

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
        super(configuration);
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory must not be null");
    }

    /**
     * Evaluates every condition, except those of rules the run skips, then fires the action of the one rule that
     * matched. No match returns no output, like the other engines. More than one match fires nothing, and fails the
     * run before the output object is created.
     *
     * @param facts   The key/value fact store to run the rule engine against.
     * @param timeout How long the run may take, or {@code null} if it has no deadline
     * @param tags    The tags that choose the rules the run uses, or none to use rules whatever their tags
     * @return The object that is the result of the action getting fired against the given {@link Rule}, or
     *         {@code null} if the rule list is empty or no rule matched
     * @throws RuleExecutionException if more than one rule matched: it names each of them, in priority order, with
     *                                the list cut at 1,000 characters like text copied from an exception, and belongs
     *                                to no rule
     */
    @Override
    RunResult<O> runRules(FactStore<?> facts, Duration timeout, Set<String> tags) {
        return runInScope(facts, timeout, tags, (ruleSet, copy, runFacts) -> {
            Matches matches = this.match(ruleSet.rules(), copy, runFacts, false);
            List<CompiledRule> matched = matches.matched();
            if (matched.isEmpty()) {
                return RunResult.of(null, List.of(), matches.evaluations(), ruleSet.checksum());
            }
            if (matched.size() > ALLOWED_MATCHES) {
                // The list of names is cut like text copied from an exception: a table whose rows all match would
                // otherwise put every name in one log line. It's escaped after the cut, so the cut can't split an
                // escape.
                throw failedRun(matched.size() + " rules matched, but a unique-match engine allows one: "
                        + Failures.escape(Failures.truncate(matched.stream()
                                .map(rule -> "'" + Failures.shorten(rule.rule().getRuleName()) + "'")
                                .collect(Collectors.joining(", ")))));
            }

            CompiledRule resolvedRule = matched.get(0);
            O output = this.executeRule(resolvedRule, copy, createOutput(outputFactory), runFacts);
            return RunResult.of(output, List.of(resolvedRule.rule()), matches.evaluations(), ruleSet.checksum());
        });
    }

    @Override
    String matchPolicy() {
        return "uniqueMatch";
    }
}
