package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;

import java.util.Objects;

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

    // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods, which
    // can fail for good when first called deep in the stack (#996). equals compares the components last first, as
    // ObjectMethods does.
    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof CompiledRule that
                && Objects.equals(compiledAction, that.compiledAction)
                && Objects.equals(compiledCondition, that.compiledCondition) && Objects.equals(language, that.language)
                && Objects.equals(displayName, that.displayName) && Objects.equals(rule, that.rule);
    }

    @Override
    public final int hashCode() {
        int hash = Objects.hashCode(rule);
        hash = hash * 31 + Objects.hashCode(displayName);
        hash = hash * 31 + Objects.hashCode(language);
        hash = hash * 31 + Objects.hashCode(compiledCondition);
        return hash * 31 + Objects.hashCode(compiledAction);
    }

    @Override
    public final String toString() {
        return "CompiledRule[rule=" + rule + ", displayName=" + displayName + ", language=" + language
                + ", compiledCondition=" + compiledCondition + ", compiledAction=" + compiledAction + "]";
    }
}
