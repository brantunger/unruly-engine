package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

/**
 * A FactReference refers to objects that are used by the rules engine in conditional statements. The name field of the
 * fact is used in the conditional expression of the rule and is substituted with the object. In the following example
 * if a fact named <strong>"variableA"</strong> is in the conditional expression of the rule and the object type is of
 * type {@link String} then "variableA" becomes a {@link String}, and methods from the {@link String} class can be called
 * on it. The following code would then become valid when evaluated by the rules engine:
 * <pre>
 *      variableA.equals("Imma String")
 * </pre>
 *
 * <p>
 * A fact's name and value don't change: a {@link FactStore} keeps a fact under its name, and to change a value it stores
 * a new fact. {@link Fact} is the built-in implementation.
 * </p>
 *
 * @param <T> The object/value type of the fact. A fact's value can be {@code null}.
 */
public interface FactReference<T extends @Nullable Object> {

    /**
     * Gets the name of the fact.
     *
     * @return the name
     */
    String getName();

    /**
     * Gets the value of the fact.
     *
     * @return the value object reference
     */
    T getValue();
}
