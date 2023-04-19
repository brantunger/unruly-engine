package io.github.brantunger.unruly.api;

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
 * @param <T> The object/value type of the fact.
 */
public class Fact<T> implements FactReference<T> {
    private String name;
    private T value;

    public Fact(String name, T value) {
        this.name = name;
        this.value = value;
    }

    public Fact(T obj) {
        this.name = obj.toString();
        this.value = obj;
    }

    public Fact(FactReference<T> fact) {
        this.name = fact.getName();
        this.value = fact.getValue();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public FactReference<T> setName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public T getValue() {
        return this.value;
    }

    @Override
    public FactReference<T> setValue(T obj) {
        this.value = obj;
        return this;
    }
}
