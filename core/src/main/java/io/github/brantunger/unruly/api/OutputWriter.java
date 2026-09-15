package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

/**
 * Sets a property on the output object. The engine uses it for the properties an action returns with
 * {@link io.github.brantunger.unruly.api.language.ActionResult#set(java.util.Map)}, as a language whose expressions
 * have no side effects does. Give an engine one with {@link RulesEngineBuilder#outputWriter(OutputWriter)}; the default
 * is {@link #beansAndMaps()}.
 *
 * <p>
 * The engine calls it on the thread running the rule, once for each property, in the order the action returned them.
 * Anything it throws fails the rule with a {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}
 * naming the rule and the property, which listeners receive in {@code onError}. It must be thread-safe, because runs
 * on many threads share it.
 * </p>
 *
 * @param <O> The type of the output objects it writes to
 */
@FunctionalInterface
public interface OutputWriter<O> {

    /**
     * Sets one property on the output object.
     *
     * @param output   The output object
     * @param property The property's name, not empty
     * @param value    The value, possibly {@code null}
     * @throws Exception if the property can't be set
     */
    void set(O output, String property, @Nullable Object value) throws Exception;

    /**
     * Returns the default writer. It sets a property with {@code put} on a {@link java.util.Map} output, and otherwise
     * with the output's public setter whose parameter accepts the value, such as {@code setInterestRate} for
     * {@code interestRate}. Values aren't converted, so a setter taking a {@code double} doesn't accept an
     * {@link Integer}. An output without such a setter fails with {@link IllegalArgumentException}.
     *
     * @param <O> The type of the output objects
     * @return The writer
     */
    @SuppressWarnings("unchecked")
    static <O> OutputWriter<O> beansAndMaps() {
        return (OutputWriter<O>) (OutputWriter<?>) BeansAndMapsWriter.INSTANCE;
    }
}
