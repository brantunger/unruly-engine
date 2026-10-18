package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputConverter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.internal.Text;

/**
 * A CompiledRule wraps a {@link Rule} together with its compiled condition and action
 * expressions for efficient repeated evaluation. Every run shares it, each with its own sessions.
 * This is an internal implementation detail and is not part of the public API. A plain class, not a record:
 * nothing compares or prints one, and a record's own methods would link through {@code ObjectMethods}, which can fail
 * for good when first called deep in the stack (#996).
 */
// Each accessor is named after the field it returns, as a record's are.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CompiledRule {
    private final Rule rule;
    private final String displayName;
    private final String language;
    private final CompiledCondition compiledCondition;
    private final CompiledAction compiledAction;
    private final OutputConverter converter;

    /**
     * @param rule              The {@link Rule}, as it was passed to {@code load}
     * @param displayName       The rule's name for messages, escaped as {@link Text#quote} does
     * @param language          The name of the expression language that compiled the rule, whose session it runs with
     * @param compiledCondition The compiled condition expression
     * @param compiledAction    The compiled action expression
     * @param converter         Converts the values the action returns for the output's setters, as the language does
     */
    CompiledRule(Rule rule, String displayName, String language, CompiledCondition compiledCondition,
                 CompiledAction compiledAction, OutputConverter converter) {
        this.rule = rule;
        this.displayName = displayName;
        this.language = language;
        this.compiledCondition = compiledCondition;
        this.compiledAction = compiledAction;
        this.converter = converter;
    }

    /** The {@link Rule}, as it was passed to {@code load}. */
    Rule rule() {
        return rule;
    }

    /** The rule's name for messages, escaped as {@link Text#quote} does. */
    String displayName() {
        return displayName;
    }

    /** The name of the expression language that compiled the rule, whose session it runs with. */
    String language() {
        return language;
    }

    /** The compiled condition expression. */
    CompiledCondition compiledCondition() {
        return compiledCondition;
    }

    /** The compiled action expression. */
    CompiledAction compiledAction() {
        return compiledAction;
    }

    /** Converts the values the action returns for the output's setters, as the language does. */
    OutputConverter converter() {
        return converter;
    }
}
