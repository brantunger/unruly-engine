package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The Flight Recorder event for one condition evaluated or one action run: which rule, in which language, and what
 * came of it. Disabled by default, because a run of a thousand rules is up to two thousand events; a recording
 * enables it to investigate. No stack trace, which would multiply its size for nothing: every event comes from
 * {@code run()}.
 * The event's name and fields are the documented contract; the class isn't API.
 *
 * <p>
 * The event is created only while a recording has it enabled, so a run without one allocates nothing for it. A
 * rule a first-match run didn't evaluate has no event.
 * </p>
 */
@Name(RuleEvent.NAME)
@Label("Rule Evaluated")
@Category("Unruly")
@Description("One condition evaluated or one action run")
@Enabled(false)
@StackTrace(false)
final class RuleEvent extends Event {

    /** The event's name in a recording and in a {@code .jfc} settings file. */
    static final String NAME = "io.github.brantunger.unruly.Rule";

    /** The condition was true. */
    static final String MATCHED = "MATCHED";
    /** The condition was false. */
    static final String NOT_MATCHED = "NOT_MATCHED";
    /** The action ran to completion. */
    static final String FIRED = "FIRED";
    /** The condition or action failed the rule, or a listener's fatal error ended the run in it. */
    static final String FAILED = "FAILED";
    /** The run was interrupted, or passed its deadline, while the condition or action ran. */
    static final String STOPPED = "STOPPED";

    /** Asked whether the event is enabled, so a disabled event isn't allocated for every rule. */
    private static final RuleEvent PROBE = new RuleEvent();

    @Label("Engine")
    long engineId;

    @Label("Run")
    long runId;

    @Label("Rule")
    String ruleName;

    @Label("Language")
    String language;

    @Label("Phase")
    @Description("CONDITION or ACTION")
    String phase;

    @Label("Result")
    @Description("MATCHED or NOT_MATCHED for a condition, FIRED for an action, FAILED or STOPPED for either")
    String result;

    /**
     * Loads the class, which registers the event with Flight Recorder. Called by {@link FlightRecorderEvents}, which
     * finds out whether that works where the engine runs.
     */
    static void load() {
        // Nothing else to do: calling a static method runs the class's initializer.
    }

    /**
     * Starts the event for a condition or action, if a recording has it enabled.
     *
     * @return The started event, or {@code null} when no recording wants it
     */
    static RuleEvent startIfEnabled() {
        if (!PROBE.isEnabled()) {
            return null;
        }
        RuleEvent event = new RuleEvent();
        event.begin();
        return event;
    }

    /**
     * Ends the event with what came of the rule and commits it.
     *
     * @param engine The engine's number
     * @param run    The run's number
     * @param rule   The rule
     * @param kind   Whether its condition was evaluated or its action ran
     * @param what   {@link #MATCHED}, {@link #NOT_MATCHED}, {@link #FIRED}, {@link #FAILED} or {@link #STOPPED}
     */
    void commit(long engine, long run, CompiledRule rule, ExpressionKind kind, String what) {
        engineId = engine;
        runId = run;
        ruleName = rule.rule().getRuleName();
        language = rule.language();
        phase = kind.name();
        result = what;
        commit();
    }
}
