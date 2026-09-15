package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.core.StatefulRulesEngine;
import io.github.brantunger.unruly.core.StatelessRulesEngine;

import java.util.function.Supplier;

/**
 * A builder to construct instances of {@link RulesEngine}. This provides a clean API for consumers
 * without requiring them to directly import core engine implementations.
 */
public final class RulesEngineBuilder {

    // The engine constructors are deprecated so that users call these methods instead.
    private static final String REMOVAL = "removal";

    private RulesEngineBuilder() {
        // Hide utility class constructor
    }

    /**
     * Creates a new STATELESS rules engine. A stateless engine evaluates all rules but only
     * fires the action of the single highest-priority rule that matched.
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param <O>           The type of the output object
     * @return A new stateless {@link RulesEngine}
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    @SuppressWarnings(REMOVAL)
    public static <O> RulesEngine<O> stateless(Supplier<O> outputFactory) {
        return new StatelessRulesEngine<>(outputFactory);
    }

    /**
     * Creates a new STATEFUL rules engine. A stateful engine evaluates all rules and fires
     * the actions of all matching rules in priority order, accumulating changes in the output object.
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param <O>           The type of the output object
     * @return A new stateful {@link RulesEngine}
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    @SuppressWarnings(REMOVAL)
    public static <O> RulesEngine<O> stateful(Supplier<O> outputFactory) {
        return new StatefulRulesEngine<>(outputFactory);
    }

    /**
     * Creates a new STATELESS rules engine, like {@link #stateless(Supplier)}, that keeps at most {@code maxCopies}
     * compiled copies of its rules.
     *
     * <p>
     * Each run uses a compiled copy of the rules that no other run is using. An engine without a limit makes a new
     * copy whenever all of them are in use, and keeps as many as the most runs it has had in progress at once. This
     * engine keeps at most {@code maxCopies}: a run that starts while all of them are in use waits until one is free,
     * so at most {@code maxCopies} runs are in progress at once. A run started from inside another run on the same
     * thread, such as from an action or a listener, doesn't wait: if no copy is free, it gets an extra copy that
     * isn't kept.
     * </p>
     *
     * <p>
     * Use a limit when runs can come from many more threads than you want copies, for example from virtual threads.
     * If a thread is interrupted while its run waits for a copy, the run throws a
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} and the thread's interrupt status stays
     * set.
     * </p>
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param maxCopies     The most compiled copies of the rules to keep, and so the most runs in progress at once;
     *                      at least 1
     * @param <O>           The type of the output object
     * @return A new stateless {@link RulesEngine}
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    @SuppressWarnings(REMOVAL)
    public static <O> RulesEngine<O> stateless(Supplier<O> outputFactory, int maxCopies) {
        return new StatelessRulesEngine<>(outputFactory, maxCopies);
    }

    /**
     * Creates a new STATEFUL rules engine, like {@link #stateful(Supplier)}, that keeps at most {@code maxCopies}
     * compiled copies of its rules. A run that starts while all of them are in use waits until one is free, as
     * {@link #stateless(Supplier, int)} describes.
     *
     * @param outputFactory Creates the output object. It is called once per run that matches a rule and must
     *                      return a new, non-null object each time.
     * @param maxCopies     The most compiled copies of the rules to keep, and so the most runs in progress at once;
     *                      at least 1
     * @param <O>           The type of the output object
     * @return A new stateful {@link RulesEngine}
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    @SuppressWarnings(REMOVAL)
    public static <O> RulesEngine<O> stateful(Supplier<O> outputFactory, int maxCopies) {
        return new StatefulRulesEngine<>(outputFactory, maxCopies);
    }
}
