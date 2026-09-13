package io.github.brantunger.unruly.api.language;

/**
 * What an action runs against: the run's facts and the output object.
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
