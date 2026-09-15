package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

/**
 * Thrown by an {@link io.github.brantunger.unruly.api.language.ExpressionCompiler} to reject an expression that breaks
 * a rule the engine enforces, such as a condition that assigns to a fact.
 *
 * <p>
 * The message says what is wrong without naming the rule, such as {@code contains an assignment ('=' at position 15)}.
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)} reports it as a
 * {@link RuleCompilationException} whose message names the expression and the rule first:
 * {@code Condition for rule 'prime-rate' contains an assignment ('=' at position 15)}, and has this exception as its
 * cause. Anything else an expression compiler throws, such as a syntax error, is the cause too, but the message then
 * reads {@code Can not compile rule 'prime-rate'. Error: } followed by the exception's message.
 * </p>
 */
public class InvalidExpressionException extends UnrulyException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message What is wrong with the expression, without the rule's name
     */
    public InvalidExpressionException(@Nullable String message) {
        super(message);
    }
}
