package io.github.brantunger.unruly.api.language;

/**
 * Which part of a rule an expression is: its condition or its action. It is the kind of an {@link Expression} an
 * expression language compiles, and what
 * {@link io.github.brantunger.unruly.api.RuleCompilationException#getExpressionKind()} and
 * {@link io.github.brantunger.unruly.api.RuleExecutionException#getExpressionKind()} return to say which expression
 * of a rule failed.
 *
 * @see Expression
 */
public enum ExpressionKind {

    /** The rule's condition, which decides whether the rule fires. */
    CONDITION,

    /** The rule's action, which runs when the rule fires. */
    ACTION
}
