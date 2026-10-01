package io.github.brantunger.unruly.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The values a language keeps for one run, which
 * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScoped} and
 * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScopedClosing} give it. The run's evaluation
 * context and each of its action contexts share one scope, and a nested run has a scope of its own.
 *
 * <p>
 * {@link #get} isn't synchronized: a run evaluates one expression at a time. It makes its map the first time a value is
 * asked for, so a run whose languages keep nothing allocates no map. Only the run's contexts refer to it, so it goes
 * when the run returns. The values kept with {@code runScoped} aren't closed; those kept with {@code runScopedClosing}
 * are handed back by {@link #end()}, for the run to close, and none can be made after that. {@link #getClosing} and
 * {@link #end()} are synchronized with each other, so a value asked for on another thread while the run ends, such as
 * through a context a language kept, is either handed back to be closed or refused, never left open.
 * </p>
 */
final class RunScope {

    private Map<Object, Object> values;
    // The keys whose init is running, so an init that asks for its own key fails rather than recursing.
    private Set<Object> making;
    // The keys kept with runScopedClosing, and their values in the order they were made, which end() hands back; made
    // when the first such value is.
    private Set<Object> closingKeys;
    private List<AutoCloseable> closing;
    private boolean ended;

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
     * @throws IllegalStateException if the init of {@code key} is running, or {@code key} is kept with
     *                               {@link #getClosing}
     */
    <T> T get(Object key, Supplier<? extends T> init) {
        return get(key, init, false);
    }

    /**
     * Returns the value kept under {@code key}, as {@link #get} does, and keeps it to be handed back by
     * {@link #end()} when the value is made.
     *
     * @param key  The key
     * @param init Makes the value
     * @param <T>  The value's type
     * @return The value
     * @throws NullPointerException  if {@code key}, {@code init} or what {@code init} returns is {@code null}
     * @throws IllegalStateException if the init of {@code key} is running, {@code key} is kept with {@link #get}, or
     *                               {@link #end()} has been called
     */
    // With end(): the init runs holding the lock, so a value is kept, or refused, wholly before or after the run ends.
    synchronized <T extends AutoCloseable> T getClosing(Object key, Supplier<? extends T> init) {
        return get(key, init, true);
    }

    private <T> T get(Object key, Supplier<? extends T> init, boolean closes) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(init, "init must not be null");
        String method = closes ? "runScopedClosing" : "runScoped";
        if (closes && ended) {
            // The key's class, not its toString(), which a key needn't have and could leak what it holds.
            throw new IllegalStateException(method + " was called for a key (" + key.getClass().getName()
                    + ") after the run ended, when its value would never be closed");
        }
        if (values == null) {
            values = new HashMap<>();
            making = new HashSet<>();
        }
        Object value = values.get(key);
        if (value != null && closes != (closingKeys != null && closingKeys.contains(key))) {
            throw new IllegalStateException(method + " was called for a key (" + key.getClass().getName()
                    + ") that " + (closes ? "runScoped" : "runScopedClosing") + " keeps a value under");
        }
        if (value == null) {
            // Not computeIfAbsent: a supplier that asks for another key would change the map while it computes.
            if (!making.add(key)) {
                throw new IllegalStateException(method + " was called for a key (" + key.getClass().getName()
                        + ") while that key's init is running");
            }
            try {
                value = Objects.requireNonNull(init.get(), "init must not return null");
            } finally {
                making.remove(key);
            }
            values.put(key, value);
            if (closes) {
                if (closing == null) {
                    closingKeys = new HashSet<>();
                    closing = new ArrayList<>();
                }
                closingKeys.add(key);
                closing.add((AutoCloseable) value);
            }
        }
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return typed;
    }

    /**
     * Ends the run's scope: hands back the values kept with {@link #getClosing}, for the caller to close, and makes
     * {@link #getClosing} fail from now on. {@link #get} still works. It allocates nothing, so it can end a run that
     * has run out of memory, and nothing can be added to the list it returns.
     *
     * @return The values to close, in the order they were made: the caller closes them last first, so a value made
     *         from another is closed before it. Empty if none were kept, or this has been called before.
     */
    // A scope keeps no list of values to close once it has handed them back.
    @SuppressWarnings("PMD.NullAssignment")
    synchronized List<AutoCloseable> end() {
        ended = true;
        List<AutoCloseable> toClose = closing;
        // Its keys are kept, so asking for one with runScoped still fails as it did.
        closing = null;
        return toClose == null ? List.of() : toClose;
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
