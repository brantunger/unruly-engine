package io.github.brantunger.unruly.api.exception;

/**
 * Which part of a rule an expression is: its condition or its action. It is the kind of an
 * {@link io.github.brantunger.unruly.api.language.Expression Expression} an expression language compiles, and what
 * {@link RuleCompilationException#getExpressionKind()} and {@link RuleExecutionException#getExpressionKind()} return
 * to say which expression of a rule failed.
 *
 * @see io.github.brantunger.unruly.api.language.Expression
 */
public enum ExpressionKind {

    /** The rule's condition, which decides whether the rule fires. */
    CONDITION,

    /** The rule's action, which runs when the rule fires. */
    ACTION
}
