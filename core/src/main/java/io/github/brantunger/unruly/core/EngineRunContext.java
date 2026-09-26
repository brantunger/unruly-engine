package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RunContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final Set<String> runTags;
    private final Instant start;

    /**
     * Creates the context of one run.
     *
     * @param runId           The run's identifier within its engine
     * @param parent          The run this one started from, or {@code null}
     * @param matchPolicy     {@code "firstMatch"}, {@code "allMatches"} or {@code "uniqueMatch"}
     * @param ruleSetChecksum The checksum of the rules the run uses
     * @param facts           The run's fact values, already read-only
     * @param tags            The run's tags, sorted and read-only, or none if the run uses every rule
     * @param startedAt       When the run started, by the engine's clock
     * @throws NullPointerException if {@code matchPolicy}, {@code ruleSetChecksum}, {@code facts}, {@code tags} or
     *                              {@code startedAt} is {@code null}
     */
    public EngineRunContext(long runId, RunContext parent, String matchPolicy, String ruleSetChecksum,
                            Map<String, Object> facts, Set<String> tags, Instant startedAt) {
        this.id = runId;
        this.enclosingRun = parent;
        this.policy = Objects.requireNonNull(matchPolicy, "matchPolicy must not be null");
        this.checksum = Objects.requireNonNull(ruleSetChecksum, "ruleSetChecksum must not be null");
        this.factValues = Objects.requireNonNull(facts, "facts must not be null");
        this.runTags = Objects.requireNonNull(tags, "tags must not be null");
        this.start = Objects.requireNonNull(startedAt, "startedAt must not be null");
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

    @Override
    public Set<String> tags() {
        return runTags;
    }

    @Override
    public Instant startedAt() {
        return start;
    }

    /**
     * Describes the run without its facts, such as {@code RunContext(runId=3, parent=2, matchPolicy=allMatches,
     * ruleSetChecksum=9f2c..., tags=[eu, retail], startedAt=2027-06-01T00:00:00Z)}. The tags are shortened
     * to 200 characters, then escaped, as the engine's error messages show names.
     *
     * @return The description
     */
    @Override
    public String toString() {
        return "RunContext(runId=" + id + ", parent=" + (enclosingRun == null ? "none" : enclosingRun.runId())
                + ", matchPolicy=" + policy + ", ruleSetChecksum=" + checksum + ", tags="
                + runTags.stream().map(Failures::quote).toList() + ", startedAt=" + start + ")";
    }
}
