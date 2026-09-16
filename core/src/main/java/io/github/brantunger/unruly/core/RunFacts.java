package io.github.brantunger.unruly.core;

import java.time.Instant;
import java.util.Map;

/**
 * What every rule of one run needs: the run's fact values, the read-only views built over them, and when the run
 * must stop. <b>Internal:</b> this record may change in any release.
 *
 * <p>
 * The views and the evaluation context depend only on the run, not on the rule being evaluated, so the engine builds
 * them once here rather than once per condition. At a thousand rules that was two objects per condition, and the
 * engine's own allocation per run was several times what a run of the same rules costs now.
 * </p>
 *
 * @param values       The fact values the run was given, unwrapped
 * @param forListeners The view listeners are given, whose writes fail with a message about listeners
 * @param evaluation   The context every condition of the run is evaluated against
 * @param deadline     When the run must stop, or {@code null} if it has none
 */
record RunFacts(Map<String, Object> values, Map<String, Object> forListeners, EngineEvaluationContext evaluation,
                Instant deadline) {

    /**
     * Builds the views one run needs.
     *
     * @param values       The fact values the run was given, unwrapped
     * @param forListeners The view listeners are given, which the run already built to report itself with
     * @param deadline     When the run must stop, or {@code null} if it has none
     * @return The run's facts
     */
    static RunFacts of(Map<String, Object> values, Map<String, Object> forListeners, Instant deadline) {
        return new RunFacts(values, forListeners, new EngineEvaluationContext(values, deadline), deadline);
    }
}
