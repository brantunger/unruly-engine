package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One condition or action of a rule, as an {@link ExpressionCompiler} compiles it: which rule it belongs to, whether
 * it's the condition or the action, and its text. A language can name the rule in its own messages, and tell a
 * condition from an action without being told separately.
 *
 * @param ruleName The rule's name, or {@code null} if the rule has none
 * @param kind     Whether the expression is the rule's condition or its action
 * @param text     The expression's text, never blank when the engine compiles it
 */
public record Expression(@Nullable String ruleName, ExpressionKind kind, String text) {

    /**
     * Creates an expression.
     *
     * @throws NullPointerException if {@code kind} or {@code text} is {@code null}
     */
    public Expression {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }
}
