package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.EvaluationContext;

import java.util.Map;
import java.util.Objects;

/**
 * What one condition is evaluated against. <b>Internal:</b> public only because {@link EvaluationContext} is sealed
 * to it.
 *
 * @param facts The run's facts, read-only
 */
public record EngineEvaluationContext(Map<String, Object> facts) implements EvaluationContext {

    /**
     * Wraps the facts in a read-only view, whose writes fail with a message about conditions.
     *
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    public EngineEvaluationContext {
        facts = ReadOnlyFacts.forConditions(Objects.requireNonNull(facts, "facts"));
    }
}
