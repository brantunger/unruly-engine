package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads MVEL's {@code strongTyping} option, and works out the types to compile a rule list against when it's on.
 *
 * <p>
 * MVEL's strong typing rejects a name it has no type for, which is exactly what catches a misspelled property or an
 * unknown fact when the rules load. It's an option, because it also rejects rules that work without it: a
 * {@code foreach} variable without a type, a map entry read as a property ({@code m.a}), and every {@code def}
 * function. When it's on, it only works if the engine knows the whole picture, so all of this must hold, or loading
 * fails saying which doesn't:
 * </p>
 *
 * <ul>
 *     <li>the engine was built with {@code requireDeclaredFacts()}, so a name nobody declared really is a mistake
 *     rather than a fact the run supplies anyway;</li>
 *     <li>at least one fact is declared;</li>
 *     <li>no declared type is dynamic: {@link Object}, a {@link Map}, a {@link Collection}, or an array of one of
 *     them. MVEL's strict mode rejects {@code order.id} on a map and {@code items[0].qty} on a list, whose elements it
 *     has no type for;</li>
 *     <li>the output type isn't dynamic either, because an action writes to {@code output}. The default output type is
 *     {@link Object}, so an engine that was never told one can't be type-checked.</li>
 * </ul>
 */
final class DeclaredTypes {

    /** The option that turns strong typing on. */
    static final String STRONG_TYPING = "strongTyping";

    private DeclaredTypes() {
    }

    /**
     * Returns the types to compile a rule list's expressions against: every declared fact, plus the output object an
     * action writes to. An empty map means compiling as MVEL does by default.
     *
     * @param context What the engine is compiling this rule list with
     * @return The type of each name an expression may refer to, or an empty map when {@code strongTyping} is off
     * @throws IllegalArgumentException if an option isn't one MVEL has, {@code strongTyping} isn't {@code true} or
     *                                  {@code false}, or it's {@code true} but strong typing can't apply
     */
    static Map<String, Class<?>> inputsFor(CompileContext context) {
        if (!strongTyping(context.options())) {
            return Map.of();
        }
        Map<String, Class<?>> declared = context.declaredFacts();
        if (!context.allFactsDeclared()) {
            throw cantApply("the engine wasn't built with requireDeclaredFacts()");
        }
        if (declared.isEmpty()) {
            throw cantApply("no facts were declared");
        }
        for (Map.Entry<String, Class<?>> fact : declared.entrySet()) {
            if (isDynamic(fact.getValue())) {
                throw cantApply("fact '" + FactNames.quote(fact.getKey()) + "' is declared as "
                        + fact.getValue().getName() + ", whose members MVEL can't check");
            }
        }
        if (isDynamic(context.outputType())) {
            throw cantApply("the output type is " + context.outputType().getName()
                    + ", and an action writes to the output; build the engine with outputType(...)");
        }
        Map<String, Class<?>> inputs = new LinkedHashMap<>(declared);
        // Only an action binds the output, but a condition that referred to it would be a mistake either way: it
        // reads facts, and the engine gives it no output object.
        inputs.put(ActionContext.OUTPUT_NAME, context.outputType());
        return inputs;
    }

    /**
     * Reads the options MVEL was given.
     *
     * @param options The options by name
     * @return Whether {@code strongTyping} is on
     * @throws IllegalArgumentException if an option isn't one MVEL has, or {@code strongTyping} is neither
     *                                  {@code true} nor {@code false}
     */
    private static boolean strongTyping(Map<String, String> options) {
        for (String key : options.keySet()) {
            if (!STRONG_TYPING.equals(key)) {
                throw new IllegalArgumentException("MVEL has no option '" + FactNames.quote(key)
                        + "'; its only option is " + STRONG_TYPING);
            }
        }
        String value = options.getOrDefault(STRONG_TYPING, "false");
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("MVEL's " + STRONG_TYPING + " option must be true or false, "
                    + "but was '" + FactNames.quote(value) + "'");
        };
    }

    private static IllegalArgumentException cantApply(String reason) {
        return new IllegalArgumentException("MVEL's " + STRONG_TYPING + " option is on, but strong typing can't apply,"
                + " because " + reason);
    }

    /**
     * Returns whether a type says nothing about what its members are, so MVEL can't check them.
     *
     * @param type The declared type
     * @return {@code true} for {@link Object}, any {@link Map} or {@link Collection}, and an array of any of them
     */
    private static boolean isDynamic(Class<?> type) {
        if (type.isArray()) {
            return isDynamic(type.getComponentType());
        }
        return type == Object.class || Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
    }
}
