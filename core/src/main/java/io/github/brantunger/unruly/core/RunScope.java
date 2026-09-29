package io.github.brantunger.unruly.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The values a language keeps for one run, which
 * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScoped} gives it. The run's evaluation context
 * and each of its action contexts share one scope, and a nested run has a scope of its own.
 *
 * <p>
 * It isn't synchronized: a run evaluates one expression at a time. It makes its map the first time a value is asked
 * for, so a run whose languages keep nothing allocates no map. Only the run's contexts refer to it, so it goes when the
 * run returns; its values aren't closed.
 * </p>
 */
final class RunScope {

    private Map<Object, Object> values;
    // The keys whose init is running, so an init that asks for its own key fails rather than recursing.
    private Set<Object> making;

    /**
     * Returns the value kept under {@code key}, making it with {@code init} the first time. A supplier that throws
     * keeps nothing, so the next call for the key calls a supplier again. The supplier can ask for other keys: the
     * value is kept only once it returns. It can't ask for its own key.
     *
     * @param key  The key
     * @param init Makes the value
     * @param <T>  The value's type
     * @return The value
     * @throws NullPointerException  if {@code key}, {@code init} or what {@code init} returns is {@code null}
     * @throws IllegalStateException if the init of {@code key} is running
     */
    <T> T get(Object key, Supplier<? extends T> init) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(init, "init must not be null");
        if (values == null) {
            values = new HashMap<>();
            making = new HashSet<>();
        }
        Object value = values.get(key);
        if (value == null) {
            // Not computeIfAbsent: a supplier that asks for another key would change the map while it computes.
            if (!making.add(key)) {
                // The key's class, not its toString(), which a key needn't have and could leak what it holds.
                throw new IllegalStateException("runScoped was called for a key (" + key.getClass().getName()
                        + ") while that key's init is running");
            }
            try {
                value = Objects.requireNonNull(init.get(), "init must not return null");
            } finally {
                making.remove(key);
            }
            values.put(key, value);
        }
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return typed;
    }

    /**
     * Tells whether a value was ever asked for, and so whether the scope has made its map.
     *
     * @return {@code true} once {@link #get} has been called
     */
    boolean allocated() {
        return values != null;
    }
}
