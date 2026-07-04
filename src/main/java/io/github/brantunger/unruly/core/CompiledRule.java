package io.github.brantunger.unruly.core;

import java.io.Serializable;

import io.github.brantunger.unruly.api.Rule;

/**
 * A CompiledRule wraps a {@link Rule} together with its pre-compiled
 * MVEL condition and action expressions for efficient repeated evaluation.
 * This is an internal implementation detail and is not part of the public API.
 *
 * @param rule              The original {@link Rule} object
 * @param compiledCondition The pre-compiled MVEL condition expression
 * @param compiledAction    The pre-compiled MVEL action expression
 */
record CompiledRule(Rule rule, Serializable compiledCondition, Serializable compiledAction) {
}
