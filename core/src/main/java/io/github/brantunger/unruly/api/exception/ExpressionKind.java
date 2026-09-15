package io.github.brantunger.unruly.api.exception;

/**
 * Which part of a rule an expression is.
 */
public enum ExpressionKind {

    /** The rule's condition, which decides whether the rule fires. */
    CONDITION,

    /** The rule's action, which runs when the rule fires. */
    ACTION
}
