package io.github.brantunger.unruly.model;

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
