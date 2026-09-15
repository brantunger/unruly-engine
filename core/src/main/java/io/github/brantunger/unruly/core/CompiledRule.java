package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;

/**
 * A CompiledRule wraps a {@link Rule} together with its compiled condition and action
 * expressions for efficient repeated evaluation. Every run shares it, each with its own sessions.
 * This is an internal implementation detail and is not part of the public API.
 *
 * @param rule              The {@link Rule}, as it was passed to {@code load}
 * @param displayName       The rule's name for messages, escaped as {@link Failures#quote} does
 * @param language          The name of the expression language that compiled the rule, whose session it runs with
 * @param compiledCondition The compiled condition expression
 * @param compiledAction    The compiled action expression
 */
record CompiledRule(Rule rule, String displayName, String language, CompiledCondition compiledCondition,
                    CompiledAction compiledAction) {
}
