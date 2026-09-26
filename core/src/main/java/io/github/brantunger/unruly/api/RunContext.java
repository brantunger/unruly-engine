package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * One run of an engine, given to {@link RuleListener}'s run callbacks. It identifies the run, so a listener can
 * correlate what it sees without a {@code ThreadLocal} of its own: a tracing span, a timer or a counter belongs to the
 * {@link #runId()}, and {@link #parent()} tells a run started from inside an action apart from the run around it.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a listener. It's sealed, so a listener can't implement it;
 * test a listener by running an engine, as <a href=
 * "https://github.com/brantunger/unruly-engine/blob/main/docs/listeners-and-logging.md#-callbacks">Callbacks</a> in
 * the listener guide says. Because only the engine implements it, a later release can add methods to it without
 * breaking listeners.
 * </p>
 *
 * <p>
 * <b>Identity:</b> a context equals only itself, so it can key a map from {@code beforeRun} to {@code afterRun} or
 * {@code onRunError}, however the facts change during the run and whatever another engine's runs look like. Its
 * {@code toString()} names the run, its parent, the match policy, the checksum, the tags and when the run started,
 * never the facts; the tags are shortened to 200 characters, then escaped, as the engine's error messages show names.
 * </p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/listeners-and-logging.md">Listeners &amp;
 *      logging</a>
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
     * {@code "allMatches"} for every match in priority order, or {@code "uniqueMatch"} for the one match, failing the
     * run when there are more. It's a {@link String} rather than an enum, so a later release can add a policy without
     * breaking code that switches over the values it knows.
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
     * still in this map when {@code beforeRun} runs, and fails the run afterwards. A value the engine widened to a
     * fact's declared primitive type, as {@link RulesEngineBuilder#fact(String, Class)} describes, is here as it was
     * widened, such as a {@link Long} for an {@link Integer} the run supplied, already when {@code beforeRun} runs.
     *
     * @return The fact values by name. Writing to it throws {@link UnsupportedOperationException}.
     */
    Map<String, @Nullable Object> facts();

    /**
     * Returns the tags the run was given with {@link RunOptions#withTags(java.util.Collection)}: the run uses only the
     * rules that carry at least one of them, and reports the others as
     * {@link RuleEvaluation.Outcome#SKIPPED SKIPPED}. With {@link #startedAt()} and the {@link #ruleSetChecksum()}, it
     * tells which rules the run uses, so an audit record can say why one was skipped.
     *
     * @return The run's {@link RunOptions#tags()}: in {@link String} order, unmodifiable, and empty if the run uses
     *         every rule
     */
    Set<String> tags();

    /**
     * Returns when the run started, by the engine's {@link RulesEngineBuilder#clock(java.time.Clock) clock}: the one
     * instant the run read from it, which judged every rule's {@link Rule#getValidFrom() validFrom} and
     * {@link Rule#getValidTo() validTo}. A fixed clock gives every run the same instant. It isn't comparable to
     * {@link RuleSetInfo#loadedAt()} or the run's deadline, which are measured with the system clock.
     *
     * @return The instant the run started at
     */
    Instant startedAt();
}
