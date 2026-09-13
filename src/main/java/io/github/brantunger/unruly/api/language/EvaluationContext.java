package io.github.brantunger.unruly.api.language;

import java.util.Map;

/**
 * What a condition is evaluated against.
 */
public interface EvaluationContext {

    /**
     * Returns the values of the run's facts by name.
     *
     * @return A read-only map; writing to it throws {@link UnsupportedOperationException}
     */
    Map<String, Object> facts();
}
