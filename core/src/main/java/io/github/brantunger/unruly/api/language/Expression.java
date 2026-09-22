package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.ExpressionKind;

import java.util.Objects;

/**
 * One condition or action of a rule, as an {@link ExpressionCompiler} compiles it: which rule it belongs to, whether
 * it's the condition or the action, and its text. A language can name the rule in its own messages, and tell a
 * condition from an action without being told separately.
 *
 * @param ruleName The rule's name
 * @param kind     Whether the expression is the rule's condition or its action
 * @param text     The expression's text, never blank when the engine compiles it
 */
public record Expression(String ruleName, ExpressionKind kind, String text) {

    /**
     * Creates an expression.
     *
     * @throws NullPointerException if {@code ruleName}, {@code kind} or {@code text} is {@code null}
     */
    public Expression {
        Objects.requireNonNull(ruleName, "ruleName must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }
}
