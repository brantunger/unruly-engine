package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ActionContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * What one action runs against. <b>Internal:</b> public only because {@link ActionContext} is sealed to it.
 *
 * @param facts    The run's facts, read-only
 * @param output   The output object the action changes
 * @param deadline When the run must stop, or {@code null} if it has none
 */
public record EngineActionContext(Map<String, Object> facts, Object output, Instant deadline)
        implements ActionContext {

    /**
     * Wraps the facts in a read-only view, whose writes fail with a message about actions.
     *
     * @throws NullPointerException if {@code facts} or {@code output} is {@code null}
     */
    public EngineActionContext {
        facts = ReadOnlyFacts.forActions(Objects.requireNonNull(facts, "facts must not be null"));
        Objects.requireNonNull(output, "output must not be null");
    }

    @Override
    public boolean isCancelled() {
        return Cancellation.isCancelled(deadline);
    }

    /**
     * Describes the context without its facts or its output object, so logging it can't leak their values.
     *
     * @return The description, such as {@code ActionContext(output=java.util.HashMap, deadline=none)}
     */
    @Override
    public String toString() {
        return "ActionContext(output=" + output.getClass().getName() + ", deadline="
                + (deadline == null ? "none" : deadline) + ")";
    }
}
