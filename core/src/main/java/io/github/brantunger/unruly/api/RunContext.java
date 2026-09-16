package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * One run of an engine, given to {@link RuleListener}'s run callbacks. It identifies the run, so a listener can
 * correlate what it sees without a {@code ThreadLocal} of its own: a tracing span, a timer or a counter belongs to the
 * {@link #runId()}, and {@link #parent()} tells a run started from inside an action apart from the run around it.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a listener. It's sealed, so a listener can't implement it;
 * test a listener through an engine, as the listener guide shows. Because only the engine implements it, a later
 * release can add methods to it without breaking listeners.
 * </p>
 *
 * <p>
 * <b>Identity:</b> a context equals only itself, so it can key a map from {@code beforeRun} to {@code afterRun} or
 * {@code onRunError}, however the facts change during the run and whatever another engine's runs look like. Its
 * {@code toString()} names the run, its parent, the match policy and the checksum, never the facts.
 * </p>
 */
public sealed interface RunContext permits io.github.brantunger.unruly.core.EngineRunContext {

    /**
     * Returns the run's identifier, which counts up from one within an engine. Two engines use the same numbers, so
     * use the context itself, or the pair of engine and id, to tell runs apart across engines.
     *
     * @return The identifier, the first run of an engine being 1
     */
    long runId();

    /**
     * Returns the run this one started from, for a run an action or a listener started on the same engine and thread.
     *
     * @return The enclosing run, or {@code null} if this run wasn't started from another run of the same engine
     */
    @Nullable RunContext parent();

    /**
     * Returns which rules the engine fires: {@code "firstMatch"} for the action of the highest-priority matching rule,
     * or {@code "allMatches"} for every match in priority order. It's a {@link String} rather than an enum, so a later
     * release can add a policy without breaking code that switches over the values it knows.
     *
     * @return The policy's name
     */
    String matchPolicy();

    /**
     * Returns the checksum of the rules this run uses, as {@link RuleSetInfo#checksum()} describes it.
     *
     * @return The checksum
     */
    String ruleSetChecksum();

    /**
     * Returns the fact values the run was given, the same read-only view the rule callbacks receive. The engine checks
     * the fact <i>names</i> after {@link RuleListener#beforeRun(RunContext)}, so a name no language can refer to is
     * still in this map when {@code beforeRun} runs, and fails the run afterwards.
     *
     * @return The fact values by name. Writing to it throws {@link UnsupportedOperationException}.
     */
    Map<String, @Nullable Object> facts();
}
