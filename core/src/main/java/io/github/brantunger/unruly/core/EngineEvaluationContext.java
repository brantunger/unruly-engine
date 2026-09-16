package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.EvaluationContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * What one condition is evaluated against. <b>Internal:</b> public only because {@link EvaluationContext} is sealed
 * to it.
 *
 * @param facts    The run's facts, read-only
 * @param deadline When the run must stop, or {@code null} if it has none
 */
public record EngineEvaluationContext(Map<String, Object> facts, Instant deadline) implements EvaluationContext {

    /**
     * Wraps the facts in a read-only view, whose writes fail with a message about conditions.
     *
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    public EngineEvaluationContext {
        facts = ReadOnlyFacts.forConditions(Objects.requireNonNull(facts, "facts"));
    }

    @Override
    public boolean isCancelled() {
        return Cancellation.isCancelled(deadline);
    }
}
