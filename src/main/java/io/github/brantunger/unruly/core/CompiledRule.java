package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;

/**
 * A CompiledRule wraps a {@link Rule} together with its compiled condition and action
 * expressions for efficient repeated evaluation.
 * This is an internal implementation detail and is not part of the public API.
 *
 * @param rule              A copy of the {@link Rule}, taken when it was compiled
 * @param displayName       The rule's name for messages, or {@code (unnamed)} if it has none
 * @param compiledCondition The compiled condition expression
 * @param compiledAction    The compiled action expression
 */
record CompiledRule(Rule rule, String displayName, CompiledCondition compiledCondition, CompiledAction compiledAction) {
}
