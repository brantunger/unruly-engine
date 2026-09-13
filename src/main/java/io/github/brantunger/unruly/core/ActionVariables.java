package io.github.brantunger.unruly.core;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The variables an action runs against: the facts, read through without copying, plus the output object and the
 * variables the action assigns, which stay local to it. A stateful run executes every matched action, so copying all
 * the facts for each one cost the number of facts times the number of matched rules.
 *
 * <p>
 * MVEL writes an assignment such as {@code output = new HashMap()} into this map and the engine never reads it back,
 * so the replacement would be silently discarded. The write is rejected instead; actions change the output object in
 * place.
 * </p>
 */
final class ActionVariables extends AbstractMap<String, Object> {

    /** The name actions use for the output object. */
    static final String OUTPUT_KEYWORD = "output";

    private final Map<String, Object> facts;
    private final Map<String, Object> locals = new HashMap<>();

    /**
     * Creates the variables for one action.
     *
     * @param facts  The run's facts, which this map never changes
     * @param output The output object, bound to {@value #OUTPUT_KEYWORD}
     */
    ActionVariables(Map<String, Object> facts, Object output) {
        this.facts = facts;
        locals.put(OUTPUT_KEYWORD, output);
    }

    @Override
    public Object get(Object key) {
        return locals.containsKey(key) ? locals.get(key) : facts.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return locals.containsKey(key) || facts.containsKey(key);
    }

    /**
     * Assigns a variable local to this action; a fact of the same name is hidden from the action but unchanged.
     *
     * @throws UnsupportedOperationException if {@code key} is {@value #OUTPUT_KEYWORD}
     */
    @Override
    public Object put(String key, Object value) {
        if (OUTPUT_KEYWORD.equals(key)) {
            throw new UnsupportedOperationException("Cannot assign '" + OUTPUT_KEYWORD
                    + "': an action changes the output object in place (e.g. output.put(...)) but can't replace it.");
        }
        Object previous = get(key);
        locals.put(key, value);
        return previous;
    }

    /** Returns a snapshot of every variable, local ones taking precedence. Only built when MVEL lists variables. */
    @Override
    public Set<Entry<String, Object>> entrySet() {
        Map<String, Object> merged = new HashMap<>(facts);
        merged.putAll(locals);
        return Collections.unmodifiableMap(merged).entrySet();
    }
}
