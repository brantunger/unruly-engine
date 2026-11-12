package io.github.brantunger.unruly.core;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An unmodifiable copy of a map that behaves as the map {@link Map#copyOf(Map)} makes does. It rejects a
 * {@code null} key or value it's asked for: {@link #get(Object)}, {@link #containsKey(Object)},
 * {@link #getOrDefault(Object, Object)} and {@link #containsValue(Object)} throw a {@link NullPointerException} for
 * one. And every method that could change it throws an {@link UnsupportedOperationException}, whether or not the
 * call would have changed anything. The declared facts and a language's options are kept in one, so a caller that
 * looked a {@code null} name up in them, or tried to change them, fails as it always did; an engine's declared
 * facts are a {@link DeclaredFacts}, which is one. It has no static initializer.
 *
 * @param <V> The type of the map's values
 */
class NullRejectingMap<V> extends AbstractMap<String, V> {

    // An unmodifiable copy, in the order of the map it was made from.
    private final Map<String, V> entries;

    /**
     * Copies a map.
     *
     * @param entries The entries to keep, none with a {@code null} key or value; copied
     */
    NullRejectingMap(Map<String, ? extends V> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
        return entries.entrySet();
    }

    // Looked up, where AbstractMap would search every entry. getOrDefault asks get, so it rejects null as well.
    @Override
    public V get(Object key) {
        return entries.get(Objects.requireNonNull(key, "key must not be null"));
    }

    @Override
    public boolean containsKey(Object key) {
        return entries.containsKey(Objects.requireNonNull(key, "key must not be null"));
    }

    @Override
    public boolean containsValue(Object value) {
        return entries.containsValue(Objects.requireNonNull(value, "value must not be null"));
    }

    @Override
    public int size() {
        return entries.size();
    }

    // AbstractMap's put always throws. The rest are written out, because AbstractMap's and Map's own return quietly
    // when there is nothing to change, such as removing a key the map doesn't have.
    @Override
    public V remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public V putIfAbsent(String key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V replace(String key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean replace(String key, V oldValue, V newValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super V, ? extends V> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V computeIfAbsent(String key, Function<? super String, ? extends V> mappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V computeIfPresent(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V compute(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V merge(String key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        throw new UnsupportedOperationException();
    }
}
