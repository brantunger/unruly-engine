package io.github.brantunger.unruly.core;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A read-only view of the unwrapped facts, handed to condition expressions, actions and listeners. A language that
 * writes assignments straight back into the map it evaluates against, as MVEL does, would otherwise change a fact for
 * every later rule in the run: a condition such as {@code approved = true} (a typo for {@code ==}) would set it.
 * Rejecting the write names the variable, which a plain {@link Collections#unmodifiableMap(Map)} would not.
 *
 * <p>
 * A language that can see an assignment in a condition rejects it when {@code load()} compiles the condition, as MVEL
 * does by scanning the condition's text. This view is the run-time backstop for any write that check doesn't
 * recognize, whatever the language. It only covers the variables themselves: a write to a fact's property goes
 * through the fact object.
 * </p>
 */
final class ReadOnlyFacts extends AbstractMap<String, Object> {

    private final Map<String, Object> facts;
    // The message for a rejected write, with %s for the name written to.
    private final String writeError;

    private ReadOnlyFacts(Map<String, Object> facts, String writeError) {
        this.facts = facts;
        this.writeError = writeError;
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
        return new ReadOnlyFacts(facts, "Cannot assign or declare '%s' in a condition: conditions can't change "
                + "facts or create variables. Move assignments and declarations into the action.");
    }

    /**
     * Creates the view passed to {@code beforeEvaluate} and {@code afterEvaluate}, whose writes come from listener
     * code rather than a condition.
     *
     * @param facts The unwrapped facts
     * @return A view that rejects writes with a message about listeners
     */
    static Map<String, Object> forListeners(Map<String, Object> facts) {
        return new ReadOnlyFacts(facts, "The facts passed to a RuleListener are read-only; '%s' can't be changed.");
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
        return new ReadOnlyFacts(facts, "The facts passed to an action are read-only; '%s' can't be changed. Put the "
                + "result in the output object instead.");
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

    /** Rejects a write, naming the variable. */
    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException(writeError.formatted(key));
    }
}
