package io.github.brantunger.unruly.api.language;

/**
 * What an action runs against: the run's facts and the output object.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a language. Don't implement it: in 2.0 it may be restricted to
 * the engine's own implementation. A method added to it in a 1.x release is a {@code default} method.
 * </p>
 */
public interface ActionContext extends EvaluationContext {

    /** The name an action uses for the output object. No fact can have this name. */
    String OUTPUT_NAME = "output";

    /**
     * Returns the output object, which the action changes in place. An action can't replace it.
     *
     * @return The output object, never {@code null}
     */
    Object output();
}
