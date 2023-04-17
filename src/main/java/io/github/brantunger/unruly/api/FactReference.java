package io.github.brantunger.unruly.api;

public interface FactReference<T> {

    /**
     * Gets the name of the fact.
     *
     * @return the name
     */
    String getName();

    /**
     * Sets the name of the fact.
     *
     * @param name the name
     */
    FactReference<T> setName(String name);

    /**
     * Gets the value of the fact.
     *
     * @return the value object reference
     */
    T getValue();

    /**
     * Sets the value of the fact.
     *
     * @param obj the value object reference
     */
    FactReference<T> setValue(T obj);
}
