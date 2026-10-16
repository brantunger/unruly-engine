package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

/**
 * Converts a value an action returned with
 * {@link io.github.brantunger.unruly.api.language.ActionResult#set(java.util.Map)} to the type of a setter, as the
 * rule's expression language understands its own values. The engine builds one for each rule from its language's
 * {@link io.github.brantunger.unruly.api.language.ExpressionCompiler#convertOutput convertOutput}, and gives it to the
 * engine's {@link OutputWriter} with {@link OutputWriter#set(Object, String, Object, OutputConverter)}, which calls it
 * with the type of a setter when the value as it is fits none.
 *
 * <p>
 * <b>Implemented by</b> the engine. A writer of an application's own calls it, and a test may implement it to give such
 * a writer a conversion of its own.
 * </p>
 */
@FunctionalInterface
public interface OutputConverter {

    /**
     * Converts a value to one the target type accepts, as the language understands the value, or returns it unchanged
     * where the language has no opinion. The engine's converter returns {@code null} unchanged, without asking the
     * language.
     *
     * @param value  The value an action returned, or {@code null}
     * @param target The type to convert it to: a setter's parameter type, which can be primitive, such as
     *               {@code int.class}
     * @return The converted value, or {@code value} where the language has no conversion for the target; {@code null}
     *         is a value too
     * @throws IllegalArgumentException if the language refuses the conversion, as it does a value that would lose
     *                                  precision or overflow the target; a language's message is expected to name the
     *                                  value and the target (see
     *                                  {@link io.github.brantunger.unruly.api.language.ExpressionCompiler#convertOutput
     *                                  convertOutput})
     */
    @Nullable Object convert(@Nullable Object value, Class<?> target);
}
