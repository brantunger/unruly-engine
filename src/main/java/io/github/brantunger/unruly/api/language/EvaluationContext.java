package io.github.brantunger.unruly.api.language;

import java.util.Map;

/**
 * What a condition is evaluated against.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a language. Don't implement it: in 2.0 it may be restricted to
 * the engine's own implementation. A method added to it in a 1.x release is a {@code default} method.
 * </p>
 */
public interface EvaluationContext {

    /**
     * Returns the values of the run's facts by name.
     *
     * @return A read-only map; writing to it throws {@link UnsupportedOperationException}
     */
    Map<String, Object> facts();
}
