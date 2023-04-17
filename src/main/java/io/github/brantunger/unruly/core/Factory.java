package io.github.brantunger.unruly.core;

public interface Factory<O> {

    /**
     * A factory method to create a new object of type {@link O}
     *
     * @return The object to instantiate
     */
    O create();
}
