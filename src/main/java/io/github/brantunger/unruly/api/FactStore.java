package io.github.brantunger.unruly.api;

import java.util.Map;

/**
 * FactStore is an extension of a {@link Map}. It's a Key/Value store where the key is a {@link String} representing
 * the fact's name, and the value is a {@link FactReference} itself.
 *
 * @param <T> The object/value type of the fact.
 */
public interface FactStore<T> extends Map<String, FactReference<T>> {

    /**
     * Gets the value of the {@link FactReference} object associated with the specified name.
     *
     * @param name the name of the {@link FactReference} object
     * @return the {@link FactReference} object associated with the name
     */
    T getValue(String name);

    /**
     * Sets the value of {@link FactReference} object.
     *
     * @param name the name
     * @param obj  the value
     */
    void setValue(String name, T obj);

    /**
     * Puts a {@link FactReference} object into the Map.
     *
     * @param ref the {@link FactReference} object to be put into the Map
     * @return the {@link FactReference} object put into the Map
     */
    FactReference<T> put(FactReference<T> ref);
}
