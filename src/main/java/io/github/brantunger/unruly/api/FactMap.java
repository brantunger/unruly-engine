package io.github.brantunger.unruly.api;

import java.util.*;

/**
 * FactMap is an implementation of {@link FactStore}. It's a Key/Value store where the key is a {@link String}
 * representing the fact's name, and the value is a {@link FactReference} itself.
 *
 * @param <T>
 */
public class FactMap<T> implements FactStore<T> {

    private final Map<String, FactReference<T>> facts;

    public FactMap(Map<String, FactReference<T>> facts) {
        this.facts = facts;
    }

    @SafeVarargs
    public FactMap(FactReference<T>... facts) {
        this();

        for (FactReference<T> fact : facts) {
            this.put(fact);
        }
    }

    public FactMap() {
        facts = new HashMap<>();
    }

    @Override
    public T getValue(String name) {
        return Optional.ofNullable(facts.get(name)).map(FactReference::getValue).orElse(null);
    }

    @Override
    public void setValue(String name, T obj) {
        FactReference<T> fact = facts.get(name);
        if (fact == null) {
            fact = new Fact<>(name, obj);
            facts.put(name, fact);
            return;
        }
        fact.setValue(obj);
    }

    @Override
    public FactReference<T> put(FactReference<T> fact) {
        return put(fact.getName(), fact);
    }

    @Override
    public int size() {
        return facts.size();
    }

    @Override
    public boolean isEmpty() {
        return facts.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return facts.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return facts.containsValue(value);
    }

    @Override
    public FactReference<T> get(Object key) {
        FactReference<T> obj = facts.get(key);
        if (obj == null) {
            return null;
        }
        if (obj instanceof Fact) {
            return obj;
        }
        return new Fact<>(obj);
    }

    @Override
    public FactReference<T> put(String key, FactReference<T> fact) {
        Optional<FactReference<T>> prev = Optional.ofNullable(facts.put(key, fact));
        return prev.map(obj -> obj instanceof Fact ? (Fact<T>) obj : new Fact<>(obj)).orElse(null);
    }

    @Override
    public FactReference<T> remove(Object key) {
        FactReference<T> obj = facts.remove(key);
        if (obj == null) {
            return null;
        }
        if (obj instanceof Fact) {
            return obj;
        }
        return new Fact<>(obj);
    }

    @Override
    public void putAll(Map<? extends String, ? extends FactReference<T>> map) {
        facts.putAll(map);
    }

    @Override
    public void clear() {
        facts.clear();
    }

    @Override
    public Set<String> keySet() {
        return facts.keySet();
    }

    @Override
    public Collection<FactReference<T>> values() {
        return facts.values();
    }

    @Override
    public Set<Entry<String, FactReference<T>>> entrySet() {
        return facts.entrySet();
    }
}
