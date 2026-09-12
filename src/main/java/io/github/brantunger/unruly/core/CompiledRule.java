package io.github.brantunger.unruly.core;

import java.io.Serializable;

import io.github.brantunger.unruly.api.Rule;

/**
 * A CompiledRule wraps a {@link Rule} together with its pre-compiled
 * MVEL condition and action expressions for efficient repeated evaluation.
 * This is an internal implementation detail and is not part of the public API.
 *
 * @param rule              A copy of the {@link Rule}, taken when it was compiled
 * @param displayName       The rule's name for messages, or {@code (unnamed)} if it has none
 * @param compiledCondition The pre-compiled MVEL condition expression
 * @param compiledAction    The pre-compiled MVEL action expression
 */
record CompiledRule(Rule rule, String displayName, Serializable compiledCondition, Serializable compiledAction) {
}
