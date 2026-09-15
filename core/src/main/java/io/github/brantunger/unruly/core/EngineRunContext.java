package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RunContext;

import java.util.Map;
import java.util.Objects;

/**
 * One run of an engine. <b>Internal:</b> public only because {@link RunContext} is sealed to it.
 *
 * @param runId            The run's identifier within its engine
 * @param parent           The run this one started from, or {@code null}
 * @param matchPolicy      {@code "firstMatch"} or {@code "allMatches"}
 * @param ruleSetChecksum  The checksum of the rules the run uses
 * @param facts            The run's fact values, already read-only
 */
public record EngineRunContext(long runId, RunContext parent, String matchPolicy, String ruleSetChecksum,
                               Map<String, Object> facts) implements RunContext {

    /**
     * Checks what the engine always supplies.
     *
     * @throws NullPointerException if {@code matchPolicy}, {@code ruleSetChecksum} or {@code facts} is {@code null}
     */
    public EngineRunContext {
        Objects.requireNonNull(matchPolicy, "matchPolicy");
        Objects.requireNonNull(ruleSetChecksum, "ruleSetChecksum");
        Objects.requireNonNull(facts, "facts");
    }
}
