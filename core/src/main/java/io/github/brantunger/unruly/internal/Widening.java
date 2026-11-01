package io.github.brantunger.unruly.internal;

import java.util.Objects;

/**
 * Widens a boxed primitive to another primitive type as Java does (JLS 5.1.2), lossy conversions included, and never
 * narrows or otherwise converts one. <b>Internal:</b> this class may change in any release. It's public only so that
 * the default output writer, {@code OutputWriter.beansAndMaps()} in another package, and the engine's widening and
 * check of a declared fact share it. Its lookups decide with comparisons, which allocate nothing, as the engine asks
 * for one for every declared fact of every run; only {@link #widen} allocates, to box its result. It has no static
 * initializer of its own, so its first use deep in a stack, as when a language's test makes a compile context with a
 * declared fact and no engine built, can't break it (#1322).
 */
public final class Widening {

    /**
     * Why a number, a character or a boolean wasn't accepted where a primitive is, for a failure's message.
     */
    public static final String ONLY_WIDENED = "a value is only widened as Java widens a primitive, never narrowed or"
            + " converted";

    private Widening() {
    }

    // A primitive type, as the component type of an array of it. A primitive type's literal, such as short.class,
    // reads its wrapper's TYPE field, which initializes the wrapper, and on JDK 21 nothing at startup initializes Short
    // or Long, so a first use deep in a stack could break them (#1260, #1322). An array class's literal initializes
    // nothing, and its component type is a field the JVM set when it made the class, so this allocates nothing.
    private static Class<?> componentOf(Class<?> arrayType) {
        return arrayType.getComponentType();
    }

    /**
     * Returns the type itself, or its wrapper if it's primitive, {@code void} included, as {@code MethodType.wrap()}
     * gives it.
     *
     * @param type Any type
     * @return The type a boxed value of it is an instance of
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == componentOf(byte[].class)) {
            return Byte.class;
        }
        if (type == componentOf(short[].class)) {
            return Short.class;
        }
        if (type == componentOf(char[].class)) {
            return Character.class;
        }
        if (type == componentOf(int[].class)) {
            return Integer.class;
        }
        if (type == componentOf(long[].class)) {
            return Long.class;
        }
        if (type == componentOf(float[].class)) {
            return Float.class;
        }
        if (type == componentOf(double[].class)) {
            return Double.class;
        }
        return type == componentOf(boolean[].class) ? Boolean.class : Void.class;
    }

    /**
     * Returns where a primitive type comes in the order from the narrowest to the widest of those Java widens
     * between, then {@code boolean}: {@code byte}, {@code short}, {@code char}, {@code int}, {@code long},
     * {@code float}, {@code double}, {@code boolean}.
     *
     * @param type Any type
     * @return Its place, from 0, or -1 if it isn't primitive, or is {@code void}
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static int order(Class<?> type) {
        if (!type.isPrimitive()) {
            return -1;
        }
        if (type == componentOf(byte[].class)) {
            return 0;
        }
        if (type == componentOf(short[].class)) {
            return 1;
        }
        if (type == componentOf(char[].class)) {
            return 2;
        }
        if (type == componentOf(int[].class)) {
            return 3;
        }
        if (type == componentOf(long[].class)) {
            return 4;
        }
        if (type == componentOf(float[].class)) {
            return 5;
        }
        if (type == componentOf(double[].class)) {
            return 6;
        }
        return type == componentOf(boolean[].class) ? 7 : -1;
    }

    /**
     * Returns whether a type is the wrapper of a primitive type other than {@code void}.
     *
     * @param type Any type
     * @return Whether it's {@link Byte}, {@link Short}, {@link Character}, {@link Integer}, {@link Long},
     *         {@link Float}, {@link Double} or {@link Boolean}
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static boolean isWrapper(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        return type == Byte.class || type == Short.class || type == Character.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class || type == Boolean.class;
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
     * Returns whether Java widens the primitive a value boxes to a primitive type other than its own (JLS 5.1.2),
     * lossy conversions included. A {@code byte} or a {@code short} never becomes a {@code char}, and a {@code char}
     * never becomes a {@code short}. A {@code boolean} becomes nothing else, and nothing becomes a {@code byte}.
     *
     * @param value     Any value, not {@code null}
     * @param primitive The type to widen to
     * @return Whether {@code primitive} is primitive and {@code value} is a boxed primitive that Java widens to it
     * @throws NullPointerException if {@code value} is {@code null} and {@code primitive} is primitive
     */
    public static boolean widens(Object value, Class<?> primitive) {
        if (!primitive.isPrimitive()) {
            return false;
        }
        Class<?> from = value.getClass();
        // Every wrapper but Long's, Float's, Double's and Boolean's widens to a long, a float and a double.
        boolean toLongOrWider = primitive == componentOf(long[].class) || primitive == componentOf(float[].class)
                || primitive == componentOf(double[].class);
        if (from == Byte.class) {
            return primitive == componentOf(short[].class) || primitive == componentOf(int[].class) || toLongOrWider;
        }
        if (from == Short.class || from == Character.class) {
            return primitive == componentOf(int[].class) || toLongOrWider;
        }
        if (from == Integer.class) {
            return toLongOrWider;
        }
        if (from == Long.class) {
            return primitive == componentOf(float[].class) || primitive == componentOf(double[].class);
        }
        return from == Float.class && primitive == componentOf(double[].class);
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
        // A char isn't a Number, but it widens to an int, and from there to the rest. Each conversion is Java's cast
        // to the type. Nothing widens to a byte, a char or a boolean, so a type none of these is is a double.
        Number number = value instanceof Character character ? Integer.valueOf(character) : (Number) value;
        if (primitive == componentOf(short[].class)) {
            return number.shortValue();
        }
        if (primitive == componentOf(int[].class)) {
            return number.intValue();
        }
        if (primitive == componentOf(long[].class)) {
            return number.longValue();
        }
        if (primitive == componentOf(float[].class)) {
            return number.floatValue();
        }
        return number.doubleValue();
    }
}
