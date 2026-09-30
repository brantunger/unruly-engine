package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import org.junit.jupiter.api.DisplayName;

/**
 * The contract test for the toy with conditions that join two with {@code and}, which the toy itself can't, so that
 * the checks that start a run inside a condition run against a language that keeps a run's state on the stack.
 */
@DisplayName("the test-only language, with conditions that require both of two, keeps the expression-language"
        + " contract")
class ToyConjunctionsContractTest extends ToyExpressionLanguageContractTest {

    /** How a condition {@code LEFT and RIGHT} evaluates its two sides, each compiled by the wrapped language. */
    @FunctionalInterface
    interface Joining {
        CompiledCondition join(CompiledCondition left, CompiledCondition right);
    }

    /**
     * Wraps a language so that a condition {@code LEFT and RIGHT} is true when both sides are, evaluating the left
     * side first, and the right side only if the left is true.
     */
    static ExpressionLanguage conjunctions(ExpressionLanguage language) {
        return conjunctions(language, (left, right) -> (evaluation, session) ->
                Boolean.TRUE.equals(left.evaluate(evaluation, session))
                        && Boolean.TRUE.equals(right.evaluate(evaluation, session)));
    }

    /**
     * Wraps a language so that a condition {@code LEFT and RIGHT} compiles each side with the language, and evaluates
     * them as {@code joining} joins them. Any other condition is the language's own.
     */
    static ExpressionLanguage conjunctions(ExpressionLanguage language, Joining joining) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        String[] sides = expression.text().split(" and ", -1);
                        if (sides.length != 2) {
                            return compiler.compileCondition(expression);
                        }
                        return joining.join(
                                compiler.compileCondition(
                                        new Expression(expression.ruleName(), ExpressionKind.CONDITION, sides[0])),
                                compiler.compileCondition(
                                        new Expression(expression.ruleName(), ExpressionKind.CONDITION, sides[1])));
                    }
                };
            }
        };
    }

    @Override
    protected ExpressionLanguage language() {
        return conjunctions(new ToyExpressionLanguage());
    }

    @Override
    protected String bothConditions(String condition, String other) {
        return condition + " and " + other;
    }
}
