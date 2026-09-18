package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RunContext;

import java.util.Map;
import java.util.Objects;

/**
 * One run of an engine. <b>Internal:</b> public only because {@link RunContext} is sealed to it.
 *
 * <p>
 * A class rather than a record, so it compares by identity: a listener keys a map on the context, and a record's
 * {@code equals} would make two engines' runs with equal facts one key, and change the hash when a fact changes during
 * the run. {@link #toString()} leaves the facts out, so logging a context can't leak a fact value.
 * </p>
 */
public final class EngineRunContext implements RunContext {

    private final long id;
    private final RunContext enclosingRun;
    private final String policy;
    private final String checksum;
    private final Map<String, Object> factValues;

    /**
     * Creates the context of one run.
     *
     * @param runId           The run's identifier within its engine
     * @param parent          The run this one started from, or {@code null}
     * @param matchPolicy     {@code "firstMatch"}, {@code "allMatches"} or {@code "uniqueMatch"}
     * @param ruleSetChecksum The checksum of the rules the run uses
     * @param facts           The run's fact values, already read-only
     * @throws NullPointerException if {@code matchPolicy}, {@code ruleSetChecksum} or {@code facts} is {@code null}
     */
    public EngineRunContext(long runId, RunContext parent, String matchPolicy, String ruleSetChecksum,
                            Map<String, Object> facts) {
        this.id = runId;
        this.enclosingRun = parent;
        this.policy = Objects.requireNonNull(matchPolicy, "matchPolicy");
        this.checksum = Objects.requireNonNull(ruleSetChecksum, "ruleSetChecksum");
        this.factValues = Objects.requireNonNull(facts, "facts");
    }

    @Override
    public long runId() {
        return id;
    }

    @Override
    public RunContext parent() {
        return enclosingRun;
    }

    @Override
    public String matchPolicy() {
        return policy;
    }

    @Override
    public String ruleSetChecksum() {
        return checksum;
    }

    @Override
    public Map<String, Object> facts() {
        return factValues;
    }

    /**
     * Describes the run without its facts, such as
     * {@code RunContext(runId=3, parent=2, matchPolicy=allMatches, ruleSetChecksum=9f2c...)}.
     *
     * @return The description
     */
    @Override
    public String toString() {
        return "RunContext(runId=" + id + ", parent=" + (enclosingRun == null ? "none" : enclosingRun.runId())
                + ", matchPolicy=" + policy + ", ruleSetChecksum=" + checksum + ")";
    }
}
