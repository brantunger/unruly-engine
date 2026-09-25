package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The built-in {@link FactStore}: a map from each fact's name to its {@link FactReference}, backed by a
 * {@link HashMap}.
 *
 * <p>
 * Every way of adding a fact rejects a {@code null} name, and rejects a key that differs from the
 * {@link FactReference#getName() name} of the fact stored under it, with {@link IllegalArgumentException}.
 * That includes {@link Map.Entry#setValue} on an {@link #entrySet()} entry and {@link #replaceAll}.
 * The varargs constructor also rejects two facts with the same name.
 * </p>
 *
 * <p>
 * A {@code FactMap} is a {@link Map} as well as a {@link FactStore}, so a {@code FactMap} variable has every
 * {@code Map} method. {@link #asMap()} returns a read-only view of it.
 * </p>
 *
 * <p>
 * {@code equals}, {@code hashCode} and {@code toString} follow the {@link Map} contract: two maps holding equal
 * facts under the same names are equal, whatever their implementation.
 * </p>
 *
 * <p>
 * <b>Threads:</b> not thread-safe, like the {@link HashMap} it wraps. Build one for each run, and don't change it
 * while a run reads it.
 * </p>
 *
 * @param <T> The object/value type of the facts
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/facts.md">Facts</a>
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/facts.md#-reusing-and-sharing-a-store">
 *      Reusing and sharing a store</a>
 */
public class FactMap<T extends @Nullable Object> implements FactStore<T>, Map<String, @Nullable FactReference<T>> {

    private final Map<String, @Nullable FactReference<T>> facts;

    /**
     * Construct a new empty FactMap.
     */
    public FactMap() {
        facts = new HashMap<>();
    }

    /**
     * Construct a new FactMap from a map of facts.
     * The map itself is copied, and the {@link FactReference} objects are shared with it. Changing a
     * value through {@link #setValue} affects only this map.
     *
     * @param facts The fact map to construct the facts from. Its values may be any {@link FactReference}
     *              implementation, such as a {@code Map<String, Fact<T>>}.
     * @throws IllegalArgumentException if a key is {@code null} or differs from its fact's name
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    public FactMap(Map<String, ? extends @Nullable FactReference<T>> facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        facts.forEach(FactMap::checkEntry);
        this.facts = new HashMap<>(facts);
    }

    /**
     * Construct a new FactMap from an array of existing FactReference objects
     *
     * @param facts The facts to construct a new FactMap with
     * @throws IllegalArgumentException if a fact's name is {@code null}, or two facts share a name
     * @throws NullPointerException if {@code facts} or any element is {@code null}
     */
    @SafeVarargs
    public FactMap(FactReference<T>... facts) {
        this();
        Objects.requireNonNull(facts, "facts must not be null");

        for (FactReference<T> fact : facts) {
            Objects.requireNonNull(fact, "facts must not contain null");
            checkEntry(fact.getName(), fact);
            // Only the last of two same-named facts would survive, silently dropping the first.
            if (this.facts.containsKey(fact.getName())) {
                throw new IllegalArgumentException("duplicate fact name '" + Names.quote(fact.getName()) + "'");
            }
            this.facts.put(fact.getName(), fact);
        }
    }

    /**
     * Rules see a fact by its map key, so a key that differs from the fact's own name would make the fact
     * unreachable under the name the caller gave it.
     */
    private static void checkEntry(@Nullable String key, @Nullable FactReference<?> fact) {
        if (key == null) {
            throw new IllegalArgumentException("fact name must not be null");
        }
        if (fact != null && !key.equals(fact.getName())) {
            throw new IllegalArgumentException("key '" + Names.quote(key) + "' does not match the fact's name '"
                    + Names.quote(fact.getName()) + "'");
        }
    }


    @Override
    public @Nullable T getValue(String name) {
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
     * @throws IllegalArgumentException if {@code name} is {@code null}
     */
    @Override
    public void setValue(String name, T obj) {
        checkEntry(name, null);
        facts.put(name, new Fact<>(name, obj));
    }

    /**
     * {@inheritDoc}
     *
     * @param fact {@inheritDoc}
     * @return {@inheritDoc}
     * @throws IllegalArgumentException if the fact's name is {@code null}
     * @throws NullPointerException if {@code fact} is {@code null}
     */
    @Override
    public @Nullable FactReference<T> put(FactReference<T> fact) {
        Objects.requireNonNull(fact, "fact must not be null");
        return put(fact.getName(), fact);
    }

    @Override
    public Map<String, @Nullable FactReference<T>> asMap() {
        return Collections.unmodifiableMap(facts);
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
    public boolean containsKey(@Nullable Object key) {
        return facts.containsKey(key);
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
        return facts.containsValue(value);
    }

    @Override
    public @Nullable FactReference<T> get(@Nullable Object key) {
        return facts.get(key);
    }

    /**
     * Stores {@code fact} under {@code key}.
     *
     * @param key  The fact's name
     * @param fact The fact. Unless it is {@code null}, its own name must equal {@code key}.
     * @return The fact previously stored under {@code key}, or {@code null} if there was none
     * @throws IllegalArgumentException if {@code key} is {@code null} or differs from the fact's name
     */
    @Override
    public @Nullable FactReference<T> put(String key, @Nullable FactReference<T> fact) {
        checkEntry(key, fact);
        return facts.put(key, fact);
    }

    @Override
    public @Nullable FactReference<T> remove(@Nullable Object key) {
        return facts.remove(key);
    }

    /**
     * Copies every entry of {@code map} into this map. Every entry is checked like {@link #put(String, FactReference)}
     * before any is stored, so an invalid one leaves this map unchanged.
     *
     * @param map The facts to add
     * @throws IllegalArgumentException if a key is {@code null} or differs from its fact's name
     * @throws NullPointerException if {@code map} is {@code null}
     */
    @Override
    public void putAll(Map<? extends String, ? extends @Nullable FactReference<T>> map) {
        Objects.requireNonNull(map, "map must not be null");
        // Checked before copying so an invalid entry leaves this map unchanged.
        map.forEach((key, fact) -> checkEntry(key, fact));
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
    public Collection<@Nullable FactReference<T>> values() {
        return facts.values();
    }

    /**
     * Replaces every fact with the function's result. Every result is checked like {@link #put(String, FactReference)}
     * before any is stored, so an invalid one leaves this map unchanged.
     *
     * @param function Computes the replacement for each name and fact
     * @throws IllegalArgumentException if a result's name differs from its key
     */
    @Override
    public void replaceAll(BiFunction<? super String, ? super @Nullable FactReference<T>,
            ? extends @Nullable FactReference<T>> function) {
        Objects.requireNonNull(function, "function must not be null");
        Map<String, @Nullable FactReference<T>> replaced = new HashMap<>();
        facts.forEach((key, fact) -> {
            FactReference<T> result = function.apply(key, fact);
            checkEntry(key, result);
            replaced.put(key, result);
        });
        facts.putAll(replaced);
    }

    /**
     * Returns a view of the entries backed by this map. Removing through the view works as usual, and an entry's
     * {@link Map.Entry#setValue} is checked like {@link #put(String, FactReference)}, so it can't store a fact
     * under a key that differs from the fact's name.
     *
     * @return A checked view of the entries
     */
    @Override
    public Set<Entry<String, @Nullable FactReference<T>>> entrySet() {
        return new CheckedEntrySet();
    }

    /** The entry set view returned by {@link #entrySet()}. */
    private final class CheckedEntrySet extends AbstractSet<Entry<String, @Nullable FactReference<T>>> {

        @Override
        public Iterator<Entry<String, @Nullable FactReference<T>>> iterator() {
            Iterator<Entry<String, @Nullable FactReference<T>>> entries = facts.entrySet().iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return entries.hasNext();
                }

                @Override
                public Entry<String, @Nullable FactReference<T>> next() {
                    return new CheckedEntry<>(entries.next());
                }

                @Override
                public void remove() {
                    entries.remove();
                }
            };
        }

        @Override
        public int size() {
            return facts.size();
        }
    }

    /** An entry whose {@link #setValue} rejects a fact whose name differs from the entry's key. */
    private static final class CheckedEntry<T extends @Nullable Object>
            implements Entry<String, @Nullable FactReference<T>> {

        private final Entry<String, @Nullable FactReference<T>> entry;

        CheckedEntry(Entry<String, @Nullable FactReference<T>> entry) {
            this.entry = entry;
        }

        @Override
        public String getKey() {
            return entry.getKey();
        }

        @Override
        public @Nullable FactReference<T> getValue() {
            return entry.getValue();
        }

        @Override
        public @Nullable FactReference<T> setValue(@Nullable FactReference<T> fact) {
            checkEntry(entry.getKey(), fact);
            return entry.setValue(fact);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return entry.equals(o);
        }

        @Override
        public int hashCode() {
            return entry.hashCode();
        }

        @Override
        public String toString() {
            return entry.toString();
        }
    }

    /**
     * Compares by entries, as the {@link Map} contract requires: equal to any {@code Map}, of any
     * implementation, holding equal facts under the same names.
     *
     * @param o The object to compare with
     * @return {@code true} if {@code o} is a {@code Map} with the same entries
     */
    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Map<?, ?> && facts.equals(o);
    }

    @Override
    public int hashCode() {
        return facts.hashCode();
    }

    @Override
    public String toString() {
        return facts.toString();
    }
}
