package io.github.brantunger.unruly.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Widens a boxed primitive to another primitive type as Java does (JLS 5.1.2), lossy conversions included, and never
 * narrows or otherwise converts one. <b>Internal:</b> this class may change in any release. It's public only so that
 * the default output writer, {@code OutputWriter.beansAndMaps()} in another package, and the engine's widening and
 * check of a declared fact share it.
 */
public final class Widening {

    /**
     * Why a number, a character or a boolean wasn't accepted where a primitive is, for a failure's message.
     */
    public static final String ONLY_WIDENED = "a value is only widened as Java widens a primitive, never narrowed or"
            + " converted";

    // The primitive types, from the narrowest to the widest of those Java widens between, then boolean.
    private static final List<Class<?>> PRIMITIVES = List.of(byte.class, short.class, char.class, int.class,
            long.class, float.class, double.class, boolean.class);

    // The wrappers of the primitive types.
    private static final Set<Class<?>> WRAPPERS = Set.of(Byte.class, Short.class, Character.class, Integer.class,
            Long.class, Float.class, Double.class, Boolean.class);

    // For each wrapper, the primitive types other than its own that Java widens its primitive to (JLS 5.1.2), lossy
    // ones included. A byte or a short never becomes a char, and a char never becomes a short. A boolean becomes
    // nothing else, so Boolean has none.
    private static final Map<Class<?>, Set<Class<?>>> WIDENED = Map.of(
            Byte.class, Set.of(short.class, int.class, long.class, float.class, double.class),
            Short.class, Set.of(int.class, long.class, float.class, double.class),
            Character.class, Set.of(int.class, long.class, float.class, double.class),
            Integer.class, Set.of(long.class, float.class, double.class),
            Long.class, Set.of(float.class, double.class),
            Float.class, Set.of(double.class));

    // The wrapper of each primitive type, void included, as MethodType.wrap() gives it. A table, so looking one up
    // allocates nothing: the engine does it for every declared fact of every run.
    private static final Map<Class<?>, Class<?>> WRAPPER_OF = Map.of(
            byte.class, Byte.class, short.class, Short.class, char.class, Character.class, int.class, Integer.class,
            long.class, Long.class, float.class, Float.class, double.class, Double.class, boolean.class, Boolean.class,
            void.class, Void.class);

    // How a number becomes each primitive type something widens to, as Java's cast to that type does. Nothing widens
    // to a byte, a char or a boolean.
    private static final Map<Class<?>, Function<Number, Object>> CONVERSIONS = Map.of(
            short.class, Number::shortValue,
            int.class, Number::intValue,
            long.class, Number::longValue,
            float.class, Number::floatValue,
            double.class, Number::doubleValue);

    private Widening() {
    }

    /**
     * Returns the type itself, or its wrapper if it's primitive.
     *
     * @param type Any type
     * @return The type a boxed value of it is an instance of
     */
    public static Class<?> wrap(Class<?> type) {
        return WRAPPER_OF.getOrDefault(type, type);
    }

    /**
     * Returns where a primitive type comes in the order from the narrowest to the widest of those Java widens
     * between, then {@code boolean}: {@code byte}, {@code short}, {@code char}, {@code int}, {@code long},
     * {@code float}, {@code double}, {@code boolean}.
     *
     * @param type Any type
     * @return Its place, from 0, or -1 if it isn't primitive, or is {@code void}
     */
    public static int order(Class<?> type) {
        return PRIMITIVES.indexOf(type);
    }

    /**
     * Returns whether a type is the wrapper of a primitive type other than {@code void}.
     *
     * @param type Any type
     * @return Whether it's {@link Byte}, {@link Short}, {@link Character}, {@link Integer}, {@link Long},
     *         {@link Float}, {@link Double} or {@link Boolean}
     */
    public static boolean isWrapper(Class<?> type) {
        return WRAPPERS.contains(type);
    }

    /**
     * Returns whether a value is a number, a character or a boolean, the values a failure explains with
     * {@link #ONLY_WIDENED} where a primitive type didn't accept them.
     *
     * @param value Any value, or {@code null}
     * @return Whether it's a {@link Number}, a {@link Character} or a {@link Boolean}
     */
    public static boolean isPrimitiveLike(Object value) {
        return value instanceof Number || value instanceof Character || value instanceof Boolean;
    }

    /**
     * Returns whether Java widens the primitive a value boxes to a primitive type other than its own.
     *
     * @param value     Any value, not {@code null}
     * @param primitive The type to widen to
     * @return Whether {@code primitive} is primitive and {@code value} is a boxed primitive that Java widens to it
     * @throws NullPointerException if {@code value} is {@code null} and {@code primitive} is primitive
     */
    public static boolean widens(Object value, Class<?> primitive) {
        return primitive.isPrimitive() && WIDENED.getOrDefault(value.getClass(), Set.of()).contains(primitive);
    }

    /**
     * Returns a value as the wrapper of a primitive type, widened as Java widens its primitive to that type, or the
     * value itself if Java doesn't widen it to that type, as for a value that is already of that wrapper.
     *
     * @param value     Any value, not {@code null}
     * @param primitive The type to widen to
     * @return The widened value, such as the {@link Long} 5 for the {@link Integer} 5 and {@code long}, or
     *         {@code value}
     * @throws NullPointerException if {@code value} is {@code null} and {@code primitive} is primitive
     */
    public static Object widen(Object value, Class<?> primitive) {
        if (!widens(value, primitive)) {
            return value;
        }
        // A char isn't a Number, but it widens to an int, and from there to the rest.
        Number number = value instanceof Character character ? Integer.valueOf(character) : (Number) value;
        return CONVERSIONS.get(primitive).apply(number);
    }
}
