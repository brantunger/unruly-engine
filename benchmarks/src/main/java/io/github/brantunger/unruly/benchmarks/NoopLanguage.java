package io.github.brantunger.unruly.benchmarks;

import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;

import java.util.Map;

/**
 * The cheapest language that still does a rule's work: a condition compares one fact with a number, and an action
 * returns one property. Running the same rule list through this and through MVEL separates what the engine costs
 * from what an expression language costs, which is the comparison the engine's own overhead has to be judged by.
 *
 * <p>
 * A condition's text is the threshold the fact {@code score} must reach, and an action's text is the name of the
 * output property to set. It keeps no state between runs, so its compiler returns {@link Session#none()}.
 * </p>
 */
public final class NoopLanguage implements ExpressionLanguage {

    /** Creates the language. Benchmarks give one to the builder, as an application would. */
    public NoopLanguage() {
        // Nothing to set up: a compiler holds everything, and it holds nothing.
    }

    /** The name rules use to choose this language. */
    public static final String LANGUAGE_NAME = "noop";

    /** The fact a condition compares. */
    public static final String SCORE_FACT = "score";

    @Override
    public String name() {
        return LANGUAGE_NAME;
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        return new Compiler();
    }

    private static final class Compiler implements ExpressionCompiler {

        @Override
        public CompiledCondition compileCondition(Expression expression) {
            int threshold = Integer.parseInt(expression.text());
            return (evaluation, session) -> scoreOf(evaluation.facts()) >= threshold;
        }

        @Override
        public CompiledAction compileAction(Expression expression) {
            String property = expression.text();
            return (action, session) -> ActionResult.set(Map.of(property, Boolean.TRUE));
        }

        @Override
        public Session newSession() {
            return Session.none();
        }

        private static int scoreOf(Map<String, ?> facts) {
            Object score = facts.get(SCORE_FACT);
            return score instanceof Number number ? number.intValue() : 0;
        }
    }
}
