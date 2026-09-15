package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * FactStore is an extension of a {@link Map}. It's a Key/Value store where the key is a {@link String} representing
 * the fact's name, and the value is a {@link FactReference} itself.
 *
 * <p>
 * <b>2.0:</b> a {@code FactStore} is expected to stop being a {@link Map}. Methods inherited from an interface can't be
 * deprecated, so prefer {@link #getValue(String)}, {@link #setValue(String, Object)} and
 * {@link #put(FactReference)} over the {@code Map} methods now, to keep code working then.
 * </p>
 *
 * @param <T> The object/value type of the fact. A fact's value, and a {@code FactReference} in the map, can be
 *            {@code null}.
 */
public interface FactStore<T extends @Nullable Object> extends Map<String, @Nullable FactReference<T>> {

    /**
     * Gets the value of the {@link FactReference} object associated with the specified name.
     *
     * @param name the name of the {@link FactReference} object
     * @return the value of type {@code T} associated with the name, or {@code null} if not found
     */
    @Nullable T getValue(String name);

    /**
     * Sets the value of {@link FactReference} object.
     *
     * @param name the name
     * @param obj  the value
     * @throws IllegalArgumentException if the store rejects {@code name}, as {@link FactMap} does for {@code null}
     */
    void setValue(String name, T obj);

    /**
     * Puts a {@link FactReference} object into the Map.
     *
     * @param ref the {@link FactReference} object to be put into the Map
     * @return the previous {@link FactReference} stored under the fact's name, or {@code null} if there was
     *         none, as with {@link java.util.Map#put(Object, Object)}
     * @throws IllegalArgumentException if the store rejects the fact's name, as {@link FactMap} does for
     *         {@code null}
     */
    @Nullable FactReference<T> put(FactReference<T> ref);
}
