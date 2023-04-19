package io.github.brantunger.unruly.core;

/**
 * A Factory that instantiates an object of the output object type.
 *
 * @param <O> The object type to instantiate
 */
public interface Factory<O> {

    /**
     * A factory method to create a new object of type {@link O}
     *
     * @return The object to instantiate
     */
    O create();
}
