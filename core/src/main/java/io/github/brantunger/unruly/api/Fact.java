package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The built-in, immutable {@link FactReference}. A rule sees the fact's value under the fact's name, so a fact named
 * {@code applicant} is {@code applicant} in a condition or action. The value object isn't copied: a rule or a listener
 * gets the same object the fact holds.
 *
 * <p>
 * A fact's name and value can't change. To give a name a new value, store a new fact, as
 * {@link FactStore#setValue(String, Object)} does. The value object itself isn't copied, so changes to it are seen
 * everywhere it's held.
 * </p>
 *
 * @param <T> The object/value type of the fact. A fact's value can be {@code null}.
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/facts.md">Facts</a>
 */
public final class Fact<T extends @Nullable Object> implements FactReference<T> {
    private final String name;
    private final T value;

    /**
     * Instantiate a Fact from the name and value.
     *
     * @param name  The name of the fact
     * @param value The value of the fact
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Fact(String name, T value) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.value = value;
    }

    /**
     * Instantiate a Fact from another Fact object. The source may hold a narrower type, so a
     * {@code Fact<String>} can be copied into a {@code Fact<Object>}.
     *
     * @param fact The existing Fact object to get the value and name from
     * @throws NullPointerException if {@code fact} or its name is {@code null}
     */
    public Fact(FactReference<? extends T> fact) {
        this(Objects.requireNonNull(fact, "fact must not be null").getName(), fact.getValue());
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public T getValue() {
        return this.value;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Fact<?> fact)) {
            return false;
        }
        return name.equals(fact.name) && Objects.equals(value, fact.value);
    }

    /**
     * Based on the name and value. A fact whose value is changed in place, such as a list that gains an element, gets a
     * different hash code, so don't change a value while its fact is in a hashed collection such as a
     * {@link java.util.HashSet}.
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
