package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether MVEL can compile a rule list against the facts the engine was declared with, and works out the
 * types to compile it with.
 *
 * <p>
 * MVEL's strong typing rejects a name it has no type for, which is exactly what catches a misspelled property or an
 * unknown fact when the rules load. That only works when the engine knows the whole picture, so all of this must
 * hold:
 * </p>
 *
 * <ul>
 *     <li>the engine was built with {@code requireDeclaredFacts()}, so a name nobody declared really is a mistake
 *     rather than a fact the run supplies anyway;</li>
 *     <li>at least one fact is declared;</li>
 *     <li>no declared type is dynamic. MVEL rejects property access on a {@link Map} ({@code order.id}) and on
 *     {@link Object} in strict mode, so one such fact would turn correct rules into compile errors;</li>
 *     <li>the output type is neither, because an action writes to {@code output}. The default output type is
 *     {@link Object}, so an engine that was never told one can't be type-checked.</li>
 * </ul>
 *
 * <p>
 * When one of them doesn't hold, MVEL compiles as it always has and the reason is logged at DEBUG. Turning strong
 * typing on where it doesn't belong would reject rules that work, which is worse than checking nothing.
 * </p>
 */
final class DeclaredTypes {

    private static final Logger log = LoggerFactory.getLogger("io.github.brantunger.unruly.engine");

    private DeclaredTypes() {
    }

    /**
     * Returns the types to compile a rule list's expressions against: every declared fact, plus the output object an
     * action writes to. An empty map means compiling as MVEL does by default.
     *
     * @param context What the engine is compiling this rule list with
     * @return The type of each name an expression may refer to, or an empty map
     */
    static Map<String, Class<?>> inputsFor(CompileContext context) {
        Map<String, Class<?>> declared = context.declaredFacts();
        if (!context.allFactsDeclared()) {
            logSkipped("the engine wasn't built with requireDeclaredFacts()");
            return Map.of();
        }
        if (declared.isEmpty()) {
            logSkipped("no facts were declared");
            return Map.of();
        }
        for (Map.Entry<String, Class<?>> fact : declared.entrySet()) {
            if (isDynamic(fact.getValue())) {
                logSkipped("fact '" + fact.getKey() + "' is declared as " + fact.getValue().getName()
                        + ", whose members MVEL can't check");
                return Map.of();
            }
        }
        if (isDynamic(context.outputType())) {
            logSkipped("the output type is " + context.outputType().getName()
                    + ", and an action writes to the output; build the engine with outputType(...)");
            return Map.of();
        }
        Map<String, Class<?>> inputs = new LinkedHashMap<>(declared);
        // Only an action binds the output, but a condition that referred to it would be a mistake either way: it
        // reads facts, and the engine gives it no output object.
        inputs.put(ActionContext.OUTPUT_NAME, context.outputType());
        return inputs;
    }

    /**
     * Returns whether a type says nothing about what its members are, so MVEL can't check them.
     *
     * @param type The declared type
     * @return {@code true} for {@link Object} and for any {@link Map}
     */
    private static boolean isDynamic(Class<?> type) {
        return type == Object.class || Map.class.isAssignableFrom(type);
    }

    private static void logSkipped(String reason) {
        log.debug("MVEL is compiling without strong typing, so a misspelled property fails a run rather than load(),"
                + " because {}.", reason);
    }
}
