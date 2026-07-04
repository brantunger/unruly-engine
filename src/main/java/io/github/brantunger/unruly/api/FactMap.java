package io.github.brantunger.unruly.api;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * FactMap is an implementation of {@link FactStore}. It's a Key/Value store where the key is a {@link String}
 * representing the fact's name, and the value is a {@link FactReference} itself.
 *
 * @param <T> The object/value type of the facts
 */
public class FactMap<T> implements FactStore<T> {

    private final Map<String, FactReference<T>> facts;

    /**
     * Construct a new empty FactMap.
     */
    public FactMap() {
        facts = new HashMap<>();
    }

    /**
     * Construct a new FactMap from a map of facts.
     * A defensive copy of the input map is created.
     *
     * @param facts The fact map to construct the facts from
     */
    public FactMap(Map<String, FactReference<T>> facts) {
        this.facts = new HashMap<>(facts);
    }

    /**
     * Construct a new FactMap from an array of existing FactReference objects
     *
     * @param facts The facts to construct a new FactMap with
     */
    @SafeVarargs
    public FactMap(FactReference<T>... facts) {
        this();

        for (FactReference<T> fact : facts) {
            if (fact.getName() == null) {
                throw new IllegalArgumentException("fact name must not be null");
            }
            this.facts.put(fact.getName(), fact);
        }
    }


    @Override
    public T getValue(String name) {
        FactReference<T> ref = facts.get(name);
        return ref != null ? ref.getValue() : null;
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
        if (fact.getName() == null) {
            throw new IllegalArgumentException("fact name must not be null");
        }
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
        return facts.get(key);
    }

    @Override
    public FactReference<T> put(String key, FactReference<T> fact) {
        return facts.put(key, fact);
    }

    @Override
    public FactReference<T> remove(Object key) {
        return facts.remove(key);
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
