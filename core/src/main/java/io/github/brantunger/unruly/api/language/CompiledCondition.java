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
 * <b>Implemented by</b> expression languages. A method added to this interface is a {@code default} method, so an
 * existing language keeps compiling and working.
 * </p>
 */
public interface CompiledCondition {

    /**
     * Evaluates the condition. An exception it throws fails the rule with a
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}, except a fatal {@link Error}, which
     * {@code run()} rethrows unchanged.
     *
     * @param context The facts of the run
     * @param session The run's session for this condition's language, created by the compiler that compiled it
     * @return The result. Anything but a {@link Boolean}, including {@code null}, fails the rule.
     * @throws Exception if the condition fails
     */
    @Nullable Object evaluate(EvaluationContext context, Session session) throws Exception;
}
