package io.github.brantunger.unruly.core;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A read-only view of the unwrapped facts, handed to condition expressions and listeners.
 * MVEL writes assignments straight back into the map it evaluates against, so without
 * this a condition such as {@code approved = true} (a typo for {@code ==}) would change
 * the fact for every later rule in the run. Rejecting the write names the variable,
 * which a plain {@link Collections#unmodifiableMap(Map)} would not.
 */
final class ReadOnlyFacts extends AbstractMap<String, Object> {

    private final Map<String, Object> facts;

    ReadOnlyFacts(Map<String, Object> facts) {
        this.facts = facts;
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
    public Set<Entry<String, Object>> entrySet() {
        return Collections.unmodifiableMap(facts).entrySet();
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("Cannot assign '" + key
                + "': facts are read-only in conditions. Use == to compare.");
    }
}
