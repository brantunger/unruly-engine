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
     * The map itself is copied, but the {@link FactReference} objects are shared with it. Changing a
     * value through {@link #setValue} affects only this map. Calling {@code setValue} on a shared
     * {@code FactReference} directly changes it everywhere it is held.
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

    /**
     * Sets the value of the named fact by storing a new {@link Fact}. An existing {@link FactReference} is
     * replaced, never updated in place, so a {@code FactMap} copied from another, or built from the same
     * {@code Fact} objects, is not changed by this call. A reference previously obtained from {@link #get}
     * keeps its old value.
     *
     * @param name The name of the fact
     * @param obj  The new value
     */
    @Override
    public void setValue(String name, T obj) {
        facts.put(name, new Fact<>(name, obj));
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
