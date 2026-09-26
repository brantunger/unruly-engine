package io.github.brantunger.unruly.core;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A read-only view of the unwrapped facts, handed to condition expressions, actions and listeners. A language that
 * writes assignments straight back into the map it evaluates against, as MVEL does, would otherwise change a fact for
 * every later rule in the run: a condition such as {@code approved = true} (a typo for {@code ==}) would set it.
 * Rejecting the write names the variable, which a plain {@link Collections#unmodifiableMap(Map)} would not.
 *
 * <p>
 * Every write is rejected, through the map or its {@link #keySet()}, {@link #values()} and {@link #entrySet()}, even
 * one that would change nothing, such as removing a fact that isn't there, as {@link Collections#unmodifiableMap(Map)}
 * rejects it. A write that names a fact, such as {@code put}, {@code remove} or an entry's {@code setValue}, names it
 * in the message; one that doesn't, such as {@code clear} or {@code putAll}, says the facts are read-only.
 * </p>
 *
 * <p>
 * A language that can see an assignment in a condition rejects it when {@code load()} compiles the condition, as MVEL
 * does by scanning the condition's text. This view is the run-time backstop for any write that check doesn't
 * recognize, whatever the language. It only covers the variables themselves: a write to a fact's property goes
 * through the fact object.
 * </p>
 */
final class ReadOnlyFacts extends AbstractMap<String, Object> {

    // What the messages for a condition advise.
    private static final String CONDITION_ADVICE = "conditions can't change facts or create variables. Move "
            + "assignments and declarations into the action.";

    private final Map<String, Object> facts;
    // The message for a rejected write, with %s for the name written to.
    private final String writeError;
    // The message for a rejected removal, with %s for the name removed.
    private final String removeError;
    // The message for a rejected write that names no single fact, such as clear().
    private final String readOnlyError;
    // The views, each created when it's first asked for.
    private Set<Entry<String, Object>> entries;
    private Set<String> keys;
    private Collection<Object> valueView;

    private ReadOnlyFacts(Map<String, Object> facts, String writeError, String removeError, String readOnlyError) {
        this.facts = facts;
        this.writeError = writeError;
        this.removeError = removeError;
        this.readOnlyError = readOnlyError;
    }

    /**
     * Creates the view a condition is evaluated against. By the time a condition runs, its language has rejected the
     * assignments it can see at {@code load()}, so a write that reaches this view is usually one the language
     * couldn't see, or a declaration such as {@code int y;}, which a language such as MVEL also stores through the
     * map. The message covers both.
     *
     * @param facts The unwrapped facts
     * @return A view that rejects writes with a message about conditions
     */
    static Map<String, Object> forConditions(Map<String, Object> facts) {
        return new ReadOnlyFacts(facts, "Cannot assign or declare '%s' in a condition: " + CONDITION_ADVICE,
                "Cannot remove '%s' in a condition: " + CONDITION_ADVICE, "Conditions can't change facts or create "
                        + "variables. Move assignments and declarations into the action.");
    }

    /**
     * Creates the view passed to {@code beforeEvaluate} and {@code afterEvaluate}, whose writes come from listener
     * code rather than a condition.
     *
     * @param facts The unwrapped facts
     * @return A view that rejects writes with a message about listeners
     */
    static Map<String, Object> forListeners(Map<String, Object> facts) {
        String writeError = "The facts passed to a RuleListener are read-only; '%s' can't be changed.";
        return new ReadOnlyFacts(facts, writeError, writeError, "The facts passed to a RuleListener are read-only.");
    }

    /**
     * Creates the view an action runs against. A language such as MVEL keeps an action's assignments local to the
     * action, so a write that reaches this view comes from a language that doesn't, whose action should change the
     * output object instead.
     *
     * @param facts The unwrapped facts
     * @return A view that rejects writes with a message about actions
     */
    static Map<String, Object> forActions(Map<String, Object> facts) {
        String writeError = "The facts passed to an action are read-only; '%s' can't be changed. Put the result in "
                + "the output object instead.";
        return new ReadOnlyFacts(facts, writeError, writeError, "The facts passed to an action are read-only. Put "
                + "the result in the output object instead.");
    }

    @Override
    public Object get(Object key) {
        return facts.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return facts.containsKey(key);
    }

    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        return facts.getOrDefault(key, defaultValue);
    }

    @Override
    public int size() {
        return facts.size();
    }

    // Reads the facts without creating an entry for each, as the default, which iterates entrySet(), would.
    @Override
    public void forEach(BiConsumer<? super String, ? super Object> action) {
        facts.forEach(action);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        if (entries == null) {
            entries = new ReadOnlySet<>(facts.entrySet(), ReadOnlyEntry::new) {
                @Override
                public boolean remove(Object o) {
                    throw o instanceof Entry<?, ?> entry ? rejected(removeError, entry.getKey()) : rejected();
                }
            };
        }
        return entries;
    }

    @Override
    public Set<String> keySet() {
        if (keys == null) {
            keys = new ReadOnlySet<>(facts.keySet(), Entry::getKey) {
                @Override
                public boolean remove(Object key) {
                    throw rejected(removeError, key);
                }
            };
        }
        return keys;
    }

    @Override
    public Collection<Object> values() {
        if (valueView == null) {
            valueView = new ReadOnlyCollection<>(facts.values(), Entry::getValue);
        }
        return valueView;
    }

    /**
     * Rejects a write, naming the variable, shortened to 200 characters, then escaped, as the engine's messages show
     * names.
     */
    @Override
    public Object put(String key, Object value) {
        throw rejected(writeError, key);
    }

    @Override
    public void putAll(Map<? extends String, ?> map) {
        throw rejected();
    }

    @Override
    public Object remove(Object key) {
        throw rejected(removeError, key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        throw rejected(removeError, key);
    }

    @Override
    public void clear() {
        throw rejected();
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super Object, ?> function) {
        throw rejected();
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        throw rejected(writeError, key);
    }

    @Override
    public Object replace(String key, Object value) {
        throw rejected(writeError, key);
    }

    @Override
    public boolean replace(String key, Object oldValue, Object newValue) {
        throw rejected(writeError, key);
    }

    @Override
    public Object computeIfAbsent(String key, Function<? super String, ?> mappingFunction) {
        throw rejected(writeError, key);
    }

    @Override
    public Object computeIfPresent(String key, BiFunction<? super String, ? super Object, ?> remappingFunction) {
        throw rejected(writeError, key);
    }

    @Override
    public Object compute(String key, BiFunction<? super String, ? super Object, ?> remappingFunction) {
        throw rejected(writeError, key);
    }

    @Override
    public Object merge(String key, Object value, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        throw rejected(writeError, key);
    }

    private static UnsupportedOperationException rejected(String error, Object key) {
        return new UnsupportedOperationException(error.formatted(Failures.quote(String.valueOf(key))));
    }

    private UnsupportedOperationException rejected() {
        return new UnsupportedOperationException(readOnlyError);
    }

    /**
     * A read-only view of the facts' keys, values or entries. It reads through {@code reads}, but iterates the facts'
     * entries, so that a removal through its iterator names the fact.
     *
     * @param <E> The type of the elements
     */
    private class ReadOnlyCollection<E> extends AbstractCollection<E> {

        private final Collection<E> reads;
        private final Function<Entry<String, Object>, E> element;

        ReadOnlyCollection(Collection<E> reads, Function<Entry<String, Object>, E> element) {
            this.reads = reads;
            this.element = element;
        }

        @Override
        public Iterator<E> iterator() {
            Iterator<Entry<String, Object>> entries = facts.entrySet().iterator();
            return new Iterator<>() {
                // The entry next() returned last, or null before the first.
                private Entry<String, Object> current;

                @Override
                public boolean hasNext() {
                    return entries.hasNext();
                }

                @Override
                public E next() {
                    current = entries.next();
                    return element.apply(current);
                }

                @Override
                public void remove() {
                    throw current == null ? rejected() : rejected(removeError, current.getKey());
                }
            };
        }

        @Override
        public int size() {
            return reads.size();
        }

        @Override
        public boolean contains(Object o) {
            return reads.contains(o);
        }

        @Override
        public boolean add(E e) {
            throw rejected();
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            throw rejected();
        }

        @Override
        public boolean remove(Object o) {
            throw rejected();
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            throw rejected();
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            throw rejected();
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            throw rejected();
        }

        @Override
        public void clear() {
            throw rejected();
        }
    }

    /**
     * A read-only view of the facts' keys or entries, equal to any set with the same elements.
     *
     * @param <E> The type of the elements
     */
    private class ReadOnlySet<E> extends ReadOnlyCollection<E> implements Set<E> {

        private final Set<E> reads;

        ReadOnlySet(Set<E> reads, Function<Entry<String, Object>, E> element) {
            super(reads, element);
            this.reads = reads;
        }

        @Override
        public boolean equals(Object o) {
            return reads.equals(o);
        }

        @Override
        public int hashCode() {
            return reads.hashCode();
        }
    }

    /** One fact, read through to the facts' entry, whose {@code setValue} is rejected with the message for a write. */
    private final class ReadOnlyEntry implements Entry<String, Object> {

        private final Entry<String, Object> entry;

        ReadOnlyEntry(Entry<String, Object> entry) {
            this.entry = entry;
        }

        @Override
        public String getKey() {
            return entry.getKey();
        }

        @Override
        public Object getValue() {
            return entry.getValue();
        }

        @Override
        public Object setValue(Object value) {
            throw rejected(writeError, getKey());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry<?, ?> other && Objects.equals(getKey(), other.getKey())
                    && Objects.equals(getValue(), other.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }

        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }
    }
}
