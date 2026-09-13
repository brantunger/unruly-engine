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
 *
 * <p>
 * {@code setRuleList()} already rejects conditions whose text contains an assignment (see
 * {@link ConditionAssignments}). This view is the run-time backstop for any write that check doesn't recognize.
 * It only covers the variables themselves: a write to a fact's property goes through the fact object.
 * </p>
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

    /**
     * Rejects a write. By the time a condition runs, the text check in {@code setRuleList()} has already rejected
     * visible assignments, so what reaches here is usually a declaration such as {@code int y;}, which MVEL also
     * stores through this map. The message covers both.
     */
    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("Cannot assign or declare '" + key + "' in a condition: "
                + "conditions can't change facts or create variables. Use == to compare, and move variables and "
                + "functions into the action.");
    }
}
