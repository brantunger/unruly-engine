package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A store of facts, each kept under its name. Rules refer to a fact by that name. {@link FactMap} is the built-in
 * implementation.
 *
 * <p>
 * The engine reads the facts through {@link #asMap()} once, when a run starts, and each {@link FactReference}'s value
 * once too, and never changes the store. A fact added, replaced or removed while that run is going isn't seen by it; a
 * change inside the object a fact's value was at the start is. So one store can serve one run after another.
 * </p>
 *
 * @param <T> The object/value type of the fact. A fact's value, and a {@code FactReference} in the store, can be
 *            {@code null}.
 */
public interface FactStore<T extends @Nullable Object> {

    /**
     * Gets the value of the {@link FactReference} object associated with the specified name.
     *
     * @param name the name of the {@link FactReference} object
     * @return the value of type {@code T} associated with the name, or {@code null} if not found
     */
    @Nullable T getValue(String name);

    /**
     * Sets the value of the named fact.
     *
     * @param name the name
     * @param obj  the value
     * @throws IllegalArgumentException if the store rejects {@code name}, as {@link FactMap} does for {@code null}
     */
    void setValue(String name, T obj);

    /**
     * Stores a {@link FactReference} under its name.
     *
     * @param ref the {@link FactReference} to store
     * @return the previous {@link FactReference} stored under the fact's name, or {@code null} if there was
     *         none, as with {@link java.util.Map#put(Object, Object)}
     * @throws IllegalArgumentException if the store rejects the fact's name, as {@link FactMap} does for
     *         {@code null}
     */
    @Nullable FactReference<T> put(FactReference<T> ref);

    /**
     * Returns a read-only view of the facts, keyed by name. The view follows later changes to the store. A run copies
     * its entries once, when it starts, binding each fact's value to its key.
     *
     * @return an unmodifiable map of each name to its {@link FactReference}
     */
    Map<String, @Nullable FactReference<T>> asMap();
}
