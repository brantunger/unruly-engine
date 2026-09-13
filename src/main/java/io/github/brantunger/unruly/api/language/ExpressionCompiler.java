package io.github.brantunger.unruly.api.language;

/**
 * Compiles the conditions and actions of one rule list, and checks the names of the facts they run against.
 *
 * <p>
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)} calls the compile methods on one
 * thread. {@link #checkFactName(String)} is called by every {@code run()} of the rule list, possibly on many threads at
 * once, so it must be thread-safe.
 * </p>
 */
public interface ExpressionCompiler {

    /**
     * Compiles a condition. A condition must evaluate to a {@link Boolean}, and can't change facts or declare
     * variables; a compiler that can see such a write rejects the condition here.
     *
     * @param source The condition, never {@code null} or blank
     * @return The compiled condition
     * @throws io.github.brantunger.unruly.api.exception.InvalidExpressionException if the condition breaks a rule the
     *         engine enforces, such as assigning to a fact. Any other exception, such as a syntax error, is reported
     *         as the cause of a {@link io.github.brantunger.unruly.api.exception.RuleCompilationException}.
     */
    CompiledCondition compileCondition(String source);

    /**
     * Compiles an action, which changes the output object it sees as {@value ActionContext#OUTPUT_NAME}.
     *
     * @param source The action, never {@code null} or blank
     * @return The compiled action
     * @throws io.github.brantunger.unruly.api.exception.InvalidExpressionException if the action breaks a rule the
     *         engine enforces. Any other exception, such as a syntax error, is reported as the cause of a
     *         {@link io.github.brantunger.unruly.api.exception.RuleCompilationException}.
     */
    CompiledAction compileAction(String source);

    /**
     * Rejects the name of a fact that rules written in this language couldn't refer to, such as a keyword of the
     * language. The engine has already rejected {@value ActionContext#OUTPUT_NAME} and {@code null}. By default,
     * every other name is accepted.
     *
     * @param name The fact's name
     * @throws IllegalArgumentException if rules can't refer to a fact with this name; {@code run()} throws it as is
     */
    default void checkFactName(String name) {
        // Every name is accepted.
    }
}
