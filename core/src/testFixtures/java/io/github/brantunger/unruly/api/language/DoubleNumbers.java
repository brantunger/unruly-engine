package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.core.Widening;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A language whose numbers are all doubles, as JavaScript's and JsonLogic's are, for tests: it wraps a language that
 * returns its results as properties, so that every {@link Integer} they hold is a {@link Double}, and converts a whole
 * double back for a setter taking an {@code int}, a {@code long}, a {@code short}, a {@code byte} or one of their
 * wrappers, as {@link ExpressionCompiler#convertOutput} lets a language do (#1127). {@link #convert} is the conversion
 * on its own, with the exactness rules a faithful adapter follows: a double is converted only if it's finite and whole,
 * and within the target's range, which for a {@code long} is where every whole double is exact, 2<sup>53</sup> either
 * side of zero; a fraction, {@code NaN}, an infinity or a number out of range is refused with an
 * {@link IllegalArgumentException} that names the value and the target.
 */
public final class DoubleNumbers {

    /** The largest whole double that is exact, and so the largest magnitude converted to a {@code long}. */
    public static final long EXACT = 1L << 53;

    private DoubleNumbers() {
    }

    /**
     * Wraps a language that returns its results as properties, so every {@link Integer} it returns is a {@link Double},
     * and its compiler converts a whole double for a setter as {@link #convert} does.
     *
     * @param language The language to wrap
     * @return The wrapped language
     */
    public static ExpressionLanguage doubleNumbers(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        return (actionContext, session) -> {
                            Map<String, Object> properties = new LinkedHashMap<>();
                            action.execute(actionContext, session).properties().forEach((key, value) -> properties
                                    .put(key, value instanceof Integer number ? number.doubleValue() : value));
                            return ActionResult.set(properties);
                        };
                    }

                    @Override
                    public @Nullable Object convertOutput(Object value, Class<?> target) {
                        return convert(value, target);
                    }
                };
            }
        };
    }

    /**
     * Converts a whole {@link Double} to the wrapper of a target that is an {@code int}, a {@code long}, a
     * {@code short}, a {@code byte} or one of their wrappers, and returns any other value, or a double for any other
     * target, unchanged.
     *
     * @param value  The value an action returned
     * @param target The type to convert it to
     * @return The converted value, or {@code value}
     * @throws IllegalArgumentException if the value is a double that isn't finite and whole, or is out of the target's
     *                                  range
     */
    public static Object convert(Object value, Class<?> target) {
        if (!(value instanceof Double number)) {
            return value;
        }
        Class<?> wrapper = Widening.wrap(target);
        if (wrapper == Integer.class) {
            return (int) whole(number, Integer.MIN_VALUE, Integer.MAX_VALUE, target);
        }
        if (wrapper == Long.class) {
            return whole(number, -EXACT, EXACT, target);
        }
        if (wrapper == Short.class) {
            return (short) whole(number, Short.MIN_VALUE, Short.MAX_VALUE, target);
        }
        if (wrapper == Byte.class) {
            return (byte) whole(number, Byte.MIN_VALUE, Byte.MAX_VALUE, target);
        }
        return value;
    }

    private static long whole(double number, long min, long max, Class<?> target) {
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.rint(number)) {
            throw new IllegalArgumentException(number + " can't be converted to " + target.getTypeName()
                    + ": not a whole number");
        }
        if (number < min || number > max) {
            throw new IllegalArgumentException(number + " can't be converted to " + target.getTypeName()
                    + ": out of range");
        }
        return (long) number;
    }
}
