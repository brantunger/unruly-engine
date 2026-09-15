package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ActionContext;

import java.util.Map;
import java.util.Objects;

/**
 * What one action runs against. <b>Internal:</b> public only because {@link ActionContext} is sealed to it.
 *
 * @param facts  The run's facts, read-only
 * @param output The output object the action changes
 */
public record EngineActionContext(Map<String, Object> facts, Object output) implements ActionContext {

    /**
     * Wraps the facts in a read-only view, whose writes fail with a message about actions.
     *
     * @throws NullPointerException if {@code facts} or {@code output} is {@code null}
     */
    public EngineActionContext {
        facts = ReadOnlyFacts.forActions(Objects.requireNonNull(facts, "facts"));
        Objects.requireNonNull(output, "output");
    }
}
