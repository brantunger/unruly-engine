package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What an action did: changed the output object itself ({@link #done()}), or produced properties for the engine to
 * set on it ({@link #set(Map)}). A language whose expressions compute values without side effects returns properties.
 *
 * <p>
 * The engine sets the properties in the order of the map, after the action returns, with the engine's
 * {@link io.github.brantunger.unruly.api.OutputWriter}. By default that is {@code put} on a {@link Map} output, and on
 * any other output the public setter whose parameter accepts the value, such as {@code setInterestRate} for
 * {@code interestRate}, without converting it. A property the writer can't set fails the rule with a
 * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}, which listeners receive in
 * {@code onError}. In a run of an engine that fires every match, each firing rule's properties are set in turn, so a
 * later rule can overwrite an earlier one's.
 * </p>
 */
public final class ActionResult {

    private static final ActionResult NO_PROPERTIES = new ActionResult(Map.of());

    private final Map<String, @Nullable Object> values;

    private ActionResult(Map<String, @Nullable Object> values) {
        this.values = values;
    }

    /**
     * Returns the result of an action that changed the output object itself, or changed nothing.
     *
     * @return The result, with no properties
     */
    public static ActionResult done() {
        return NO_PROPERTIES;
    }

    /**
     * Returns the result of an action that produced properties for the engine to set on the output object.
     *
     * @param properties The values by property name, in the order to set them; copied. Values can be {@code null}.
     * @return The result
     * @throws NullPointerException     if {@code properties} or one of its names is {@code null}
     * @throws IllegalArgumentException if a name is empty
     */
    public static ActionResult set(Map<String, ? extends @Nullable Object> properties) {
        Map<String, @Nullable Object> copy = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            Objects.requireNonNull(name, "property name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("property name must not be empty");
            }
            copy.put(name, value);
        });
        return new ActionResult(Collections.unmodifiableMap(copy));
    }

    /**
     * Returns the properties to set on the output object.
     *
     * @return The values by property name, in the order they're set; empty for {@link #done()}; unmodifiable
     */
    public Map<String, @Nullable Object> properties() {
        return values;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ActionResult result && values.equals(result.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.isEmpty() ? "ActionResult.done()" : "ActionResult.set(" + values + ")";
    }
}
