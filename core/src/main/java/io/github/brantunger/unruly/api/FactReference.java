package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

/**
 * A named value that rules run against. A rule sees the value under the fact's name, so a fact named
 * {@code applicant} is {@code applicant} in a condition or action. The value object isn't copied: a rule or a listener
 * gets the same object the fact holds.
 *
 * <p>
 * A fact's name and value don't change: a {@link FactStore} keeps a fact under its name, and to change a value it stores
 * a new fact. {@link Fact} is the built-in implementation.
 * </p>
 *
 * @param <T> The object/value type of the fact. A fact's value can be {@code null}.
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/facts.md">Facts</a>
 */
public interface FactReference<T extends @Nullable Object> {

    /**
     * Gets the name of the fact.
     *
     * @return The name
     */
    String getName();

    /**
     * Gets the value of the fact.
     *
     * @return The value, which can be {@code null}
     */
    T getValue();
}
