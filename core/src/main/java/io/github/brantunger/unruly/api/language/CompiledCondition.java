package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

/**
 * A compiled condition, shared by every run of its rule list.
 *
 * <p>
 * Several runs can evaluate one compiled condition at the same time, each with its own {@link Session}, which only one
 * run uses at a time. Keep what changes while the condition runs in the session.
 * </p>
 *
 * <p>
 * The engine evaluates a condition with {@link #evaluateWithDetail}, which a language overrides to explain the result
 * as well as return it.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface is a {@code default} method, so an
 * existing language keeps compiling and working.
 * </p>
 */
@FunctionalInterface
public interface CompiledCondition {

    /**
     * Evaluates the condition. The engine doesn't call this method itself: it calls {@link #evaluateWithDetail}, whose
     * default calls this one, so for a language that doesn't override {@code evaluateWithDetail}, an exception this
     * method throws and a result that isn't a {@link Boolean} fail the rule as {@link #evaluateWithDetail} describes.
     *
     * @param context The facts of the run
     * @param session The run's session for this condition's language, created by the compiler that compiled it
     * @return The result, which must be a {@link Boolean}
     * @throws Exception if the condition fails
     */
    @Nullable Object evaluate(EvaluationContext context, Session session) throws Exception;

    /**
     * Evaluates the condition and explains its result. The engine calls this method, never {@link #evaluate}
     * directly. When the value is a {@link Boolean}, it records the detail, if there is one, on the rule's
     * {@link io.github.brantunger.unruly.api.RuleEvaluation#detail() evaluation} in the run's result, unless the run
     * stops first; any other value fails the run, and the detail is dropped. By default, returns
     * {@link ConditionResult#of(Object)} of what {@link #evaluate} returns, with no detail, so a language that
     * implements only {@code evaluate} works as it did.
     *
     * <p>
     * A language that can explain a condition overrides this method and returns the value and the detail from one
     * evaluation. Its {@code evaluate} must return the same value. The detail must not be the session or hold it: the
     * engine keeps the detail after the run, when the session is used by another run or closed.
     * </p>
     *
     * <p>
     * A decorator that wraps another language's condition must override this method too, and call the wrapped
     * condition's {@code evaluateWithDetail}. A wrapper written as a lambda implements only {@code evaluate}, so the
     * wrapped condition's detail is dropped.
     * </p>
     *
     * @param context The facts of the run
     * @param session The run's session for this condition's language, created by the compiler that compiled it
     * @return The result, never {@code null}. A value that isn't a {@link Boolean}, including {@code null}, fails the
     *         rule, as does a {@code null} result.
     * @throws Exception if the condition fails. It fails the rule with a
     *                   {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}, except a fatal
     *                   {@link Error}, which {@code run()} rethrows unchanged.
     */
    default ConditionResult evaluateWithDetail(EvaluationContext context, Session session) throws Exception {
        return ConditionResult.of(evaluate(context, session));
    }
}
