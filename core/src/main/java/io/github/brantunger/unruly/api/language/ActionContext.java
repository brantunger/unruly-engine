package io.github.brantunger.unruly.api.language;

/**
 * What an action runs against: the run's facts and the output object.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a language. It's sealed, so a language can't implement it; a
 * language's unit tests create one with {@code io.github.brantunger.unruly.test.LanguageTestContexts}, from the
 * {@code unruly-engine-test} artifact. Because only the engine implements it, a later release can add methods to it
 * without breaking languages.
 * </p>
 */
public sealed interface ActionContext extends EvaluationContext
        permits io.github.brantunger.unruly.core.EngineActionContext {

    /** The name an action uses for the output object. No fact can have this name. */
    String OUTPUT_NAME = "output";

    /**
     * Returns the output object. The action changes it in place, or returns {@link ActionResult#set(java.util.Map)}
     * with the properties for the engine to set on it. An action can't replace it.
     *
     * @return The output object, never {@code null}
     */
    Object output();
}
