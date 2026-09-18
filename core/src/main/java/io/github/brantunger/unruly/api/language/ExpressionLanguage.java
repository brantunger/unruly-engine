package io.github.brantunger.unruly.api.language;

/**
 * A language that rule conditions and actions are written in, such as MVEL.
 *
 * <p>
 * The engine compiles each rule list with a new {@link ExpressionCompiler}, which keeps whatever the language caches
 * for that list. The engine enforces the rest of the rule contract itself, whatever the language: rule names are
 * unique, conditions and actions aren't blank, rules run in priority order, a condition evaluates to a
 * {@link Boolean}, no fact is named {@value ActionContext#OUTPUT_NAME}, and failures are reported as
 * {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} or
 * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} and to listeners.
 * </p>
 *
 * <p>
 * Implementations must be thread-safe: an engine can compile rule lists on several threads.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface in a later 2.x release is a
 * {@code default} method, so an existing language keeps compiling and working.
 * </p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/custom.md">Writing an expression
 *      language</a>
 */
public interface ExpressionLanguage {

    /**
     * Returns the language's name, such as {@code "mvel"}.
     *
     * @return The name, never {@code null} or blank
     */
    String name();

    /**
     * Creates the compiler for one rule list.
     *
     * @param context The imports and class loader the rule list is compiled with
     * @return A new compiler, used for this rule list only
     */
    ExpressionCompiler newCompiler(CompileContext context);
}
