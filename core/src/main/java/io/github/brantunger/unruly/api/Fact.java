package io.github.brantunger.unruly.api;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A Fact is an implementation of {@link FactReference}. Facts are objects that are used by the rules engine in
 * conditional statements. The name field of the fact is used in the conditional expression of the rule and is
 * substituted with the object. In the following example if a fact named <strong>"variableA"</strong> is in the
 * conditional expression of the rule and the object type is of type {@link String} then "variableA" becomes a
 * {@link String}, and methods from the {@link String} class can be called on it. The following code would then become
 * valid when evaluated by the rules engine:
 * <pre>
 *      variableA.equals("Imma String")
 * </pre>
 *
 * @param <T> The object/value type of the fact. A fact's value can be {@code null}.
 */
public class Fact<T extends @Nullable Object> implements FactReference<T> {
    private @Nullable String name;
    private T value;

    /**
     * Instantiate a Fact from the name and value.
     *
     * @param name  The name of the fact
     * @param value The value of the fact
     */
    public Fact(@Nullable String name, T value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Instantiate a Fact named after its value: the name is {@code obj.toString()}, such as {@code "John Smith"}
     * or {@code "java.lang.Object@4501b7af"}. Rules can only refer to a fact whose name is a Java identifier, so
     * prefer {@link #Fact(String, Object)} unless the value's string form is one. The name doesn't follow later
     * changes to the value.
     *
     * @param obj The object to obtain the value from
     * @throws NullPointerException if {@code obj} is {@code null}
     */
    public Fact(@NonNull T obj) {
        Objects.requireNonNull(obj, "Fact value must not be null");
        this.name = obj.toString();
        this.value = obj;
    }

    /**
     * Instantiate a Fact from another Fact object. The source may hold a narrower type, so a
     * {@code Fact<String>} can be copied into a {@code Fact<Object>}.
     *
     * @param fact The existing Fact object to get the value and name from
     * @throws NullPointerException if {@code fact} is {@code null}
     */
    public Fact(FactReference<? extends T> fact) {
        Objects.requireNonNull(fact, "fact must not be null");
        this.name = fact.getName();
        this.value = fact.getValue();
    }

    @Override
    public @Nullable String getName() {
        return this.name;
    }

    /**
     * Sets the name of the fact.
     *
     * @param name the name
     * @return This fact
     * @deprecated Create a new fact with {@code new Fact<>(name, value)}, or set a value by name with
     *             {@link FactStore#setValue(String, Object)}. Facts are expected to become immutable in 2.0.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    @SuppressWarnings("removal") // Overrides the FactReference method, which is deprecated too.
    @Override
    public FactReference<T> setName(@Nullable String name) {
        this.name = name;
        return this;
    }

    @Override
    public T getValue() {
        return this.value;
    }

    /**
     * Sets the value of the fact.
     *
     * @param obj the value
     * @return This fact
     * @deprecated Create a new fact with {@code new Fact<>(name, value)}, or set a value by name with
     *             {@link FactStore#setValue(String, Object)}. Facts are expected to become immutable in 2.0.
     */
    @Deprecated(since = "1.8.0", forRemoval = true)
    @SuppressWarnings("removal") // Overrides the FactReference method, which is deprecated too.
    @Override
    public FactReference<T> setValue(T obj) {
        this.value = obj;
        return this;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Fact<?> fact)) {
            return false;
        }
        return Objects.equals(name, fact.name) && Objects.equals(value, fact.value);
    }

    /**
     * Based on the name and value, both of which can change. Don't change a Fact while it is in a hashed collection
     * such as a {@link java.util.HashSet}, or the collection can no longer find it.
     *
     * @return The hash code of the name and value
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Override
    public String toString() {
        return "Fact{name='" + name + "', value=" + value + "}";
    }
}
