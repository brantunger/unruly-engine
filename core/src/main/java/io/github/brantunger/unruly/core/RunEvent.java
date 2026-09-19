package io.github.brantunger.unruly.core;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Threshold;

/**
 * The Flight Recorder event for one run: how long it took, from {@code run()} being called to it returning or
 * throwing, and what it did. Enabled by default with a 10 ms threshold, so a recording keeps only the slow runs
 * unless it lowers the threshold. It keeps JFR's stack trace, which shows who called {@code run()}. The event's name
 * and fields are the documented contract; the class isn't API.
 *
 * <p>
 * The event is created only while a recording has it enabled, so a run without one allocates nothing for it.
 * </p>
 */
@Name(RunEvent.NAME)
@Label("Rules Run")
@Category("Unruly")
@Description("One run of a rules engine, from run() being called to it returning or throwing")
@Enabled(true)
@Threshold("10 ms")
final class RunEvent extends Event {

    /** The event's name in a recording and in a {@code .jfc} settings file. */
    static final String NAME = "io.github.brantunger.unruly.Run";

    /** The run returned its result. */
    static final String COMPLETED = "COMPLETED";
    /** The run threw. */
    static final String FAILED = "FAILED";
    /** The run threw because its thread was interrupted or it passed its deadline, also while waiting for a copy. */
    static final String STOPPED = "STOPPED";

    /** Asked whether the event is enabled, so a disabled event isn't allocated for every run. */
    private static final RunEvent PROBE = new RunEvent();

    @Label("Engine")
    @Description("Numbers the engines of this JVM in creation order")
    long engineId;

    @Label("Run")
    @Description("Numbers the engine's runs, as RunContext.runId() does")
    long runId;

    @Label("Parent run")
    @Description("The run this one was started from, on the same thread, or 0 for an outermost run")
    long parentRunId;

    @Label("Match policy")
    String matchPolicy;

    @Label("Rules evaluated")
    @Description("How many conditions were evaluated, also when the run failed or stopped")
    int rulesEvaluated;

    @Label("Rules fired")
    @Description("How many actions ran to completion")
    int rulesFired;

    @Label("Rule set checksum")
    String ruleSetChecksum;

    @Label("Outcome")
    @Description("COMPLETED, FAILED, or STOPPED because the run was interrupted or passed its deadline")
    String outcome;

    /**
     * Loads the class, which registers the event with Flight Recorder. Called by {@link FlightRecorderEvents}, which
     * finds out whether that works where the engine runs.
     */
    static void load() {
        // Nothing else to do: calling a static method runs the class's initializer.
    }

    /**
     * Starts the event for a run, if a recording has it enabled.
     *
     * @return The started event, or {@code null} when no recording wants it
     */
    static RunEvent startIfEnabled() {
        if (!PROBE.isEnabled()) {
            return null;
        }
        RunEvent event = new RunEvent();
        event.begin();
        return event;
    }

    /**
     * Ends the event with what the run did and commits it, subject to the threshold.
     *
     * @param engine   The engine's number
     * @param run      The run's number
     * @param parent   The number of the run this one was started from, or 0
     * @param policy   The engine's match policy
     * @param tally    What the run counted
     * @param checksum The checksum of the rules the run used
     * @param result   {@link #COMPLETED}, {@link #FAILED} or {@link #STOPPED}
     */
    void commit(long engine, long run, long parent, String policy, RunTally tally, String checksum, String result) {
        engineId = engine;
        runId = run;
        parentRunId = parent;
        matchPolicy = policy;
        rulesEvaluated = tally.evaluatedCount();
        rulesFired = tally.firedCount();
        ruleSetChecksum = checksum;
        outcome = result;
        commit();
    }
}
