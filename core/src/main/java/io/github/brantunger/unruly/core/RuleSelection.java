package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Which rules one run uses: the enabled ones that are within their validity window when the run starts and, if the
 * run was given tags, carry at least one of them. The run skips every other rule.
 *
 * @param startedAt When the run started, by the engine's clock
 * @param tags      The tags the run was given, or none to use rules whatever their tags
 */
record RuleSelection(Instant startedAt, Set<String> tags) {

    /**
     * Returns whether the run skips {@code rule}.
     *
     * @param rule The rule
     * @return {@code true} if the rule is disabled, outside its validity window, or carries none of the run's tags
     */
    boolean skips(Rule rule) {
        return !rule.isEnabled() || !withinWindow(rule) || !carriesATag(rule);
    }

    /** Whether the run was given no tags, or the rule carries at least one of them. */
    private boolean carriesATag(Rule rule) {
        return tags.isEmpty() || !Collections.disjoint(tags, rule.getTags());
    }

    /** Whether the run started at or after the rule's start and before its end. */
    private boolean withinWindow(Rule rule) {
        Instant from = rule.getValidFrom();
        Instant to = rule.getValidTo();
        return (from == null || !startedAt.isBefore(from)) && (to == null || startedAt.isBefore(to));
    }
}
