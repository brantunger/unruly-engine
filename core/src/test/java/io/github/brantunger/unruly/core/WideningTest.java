package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.internal.Widening;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Widening#wrap(Class)} gives what {@link MethodType#wrap()} gives, without allocating, so the engine's check of
 * every declared fact of every run allocates nothing for it; and each of {@link Widening}'s answers, for each primitive
 * type, each wrapper and some other types, is the one its tables gave before it decided them with comparisons (#1322).
 */
@DisplayName("Widening gives a type's wrapper as MethodType does, without allocating, and widens as Java does")
class WideningTest {

    private static final List<Class<?>> TYPES = List.of(byte.class, short.class, char.class, int.class, long.class,
            float.class, double.class, boolean.class, void.class, Byte.class, Short.class, Character.class,
            Integer.class, Long.class, Float.class, Double.class, Boolean.class, Void.class, String.class, int[].class,
            Object.class, Number.class, BigDecimal.class);

    // The primitive types, in Widening.order()'s order, then the wrappers in the same order.
    private static final List<Class<?>> PRIMITIVES = List.of(byte.class, short.class, char.class, int.class,
            long.class, float.class, double.class, boolean.class);
    private static final List<Class<?>> WRAPPERS = List.of(Byte.class, Short.class, Character.class, Integer.class,
            Long.class, Float.class, Double.class, Boolean.class);

    // A value of each wrapper, then of types that aren't wrappers, which widen to nothing.
    private static final List<Object> VALUES = List.of((byte) -7, (short) -300, 'A', Integer.MAX_VALUE, Long.MAX_VALUE,
            1.1f, 2.5d, true, BigDecimal.ONE, "s", new Object());

    // How many lookups lookUpAll makes: order, isWrapper and widens.
    private static final int LOOKUPS = 3;

    // For order and isWrapper: a primitive type, a wrapper and a type that is neither.
    private static final List<Class<?>> LOOKED_UP = List.of(int.class, Integer.class, String.class);

    // For widens: a pair that widens, then two that don't, a narrowing and a value that isn't a boxed primitive.
    private static final List<Object> WIDENED_VALUES = List.of(5, 5L, "s");
    private static final List<Class<?>> WIDENED_TO = List.of(long.class, int.class, int.class);

    @Test
    @DisplayName("each primitive type, void included, gives its wrapper, and any other type itself, as MethodType does")
    void matchesMethodType() {
        for (Class<?> type : TYPES) {
            assertSame(MethodType.methodType(type).wrap().returnType(), Widening.wrap(type), type.getName());
        }
    }

    @Test
    @DisplayName("each primitive type but void has its place in the order, and the wrappers alone are wrappers")
    void orderAndWrappers() {
        for (Class<?> type : TYPES) {
            assertEquals(PRIMITIVES.indexOf(type), Widening.order(type), type.getName());
            assertEquals(WRAPPERS.contains(type), Widening.isWrapper(type), type.getName());
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, -1, -1), List.of(Widening.order(byte.class),
                Widening.order(short.class), Widening.order(char.class), Widening.order(int.class),
                Widening.order(long.class), Widening.order(float.class), Widening.order(double.class),
                Widening.order(boolean.class), Widening.order(void.class), Widening.order(Integer.class)));
        assertFalse(Widening.isWrapper(void.class));
        assertFalse(Widening.isWrapper(Void.class));
        assertFalse(Widening.isWrapper(int.class));
    }

    @Test
    @DisplayName("a number, a character and a boolean are primitive-like, and nothing else is")
    void primitiveLike() {
        List<Boolean> expected = List.of(true, true, true, true, true, true, true, true, true, false, false);
        for (int i = 0; i < VALUES.size(); i++) {
            assertEquals(expected.get(i), Widening.isPrimitiveLike(VALUES.get(i)), String.valueOf(VALUES.get(i)));
        }
        assertFalse(Widening.isPrimitiveLike(null));
    }

    @Test
    @DisplayName("each wrapper widens to the primitive types Java widens its own to, as Java's cast does, and to no"
            + " other type")
    void widensAsJavaDoes() {
        // What each value becomes for each type it widens to, in TYPES' order, as Java's cast gives it, lossy
        // conversions included. A value not here widens to nothing.
        Map<Object, List<Object>> widened = Map.of(
                VALUES.get(0), List.of((short) -7, -7, -7L, -7f, -7d),
                VALUES.get(1), List.of(-300, -300L, -300f, -300d),
                VALUES.get(2), List.of(65, 65L, 65f, 65d),
                VALUES.get(3), List.of((long) Integer.MAX_VALUE, (float) Integer.MAX_VALUE,
                        (double) Integer.MAX_VALUE),
                VALUES.get(4), List.of((float) Long.MAX_VALUE, (double) Long.MAX_VALUE),
                VALUES.get(5), List.of((double) 1.1f));
        for (Object value : VALUES) {
            List<Object> actual = new ArrayList<>();
            for (Class<?> target : TYPES) {
                Object result = Widening.widen(value, target);
                if (Widening.widens(value, target)) {
                    assertSame(Widening.wrap(target), result.getClass(), value + " to " + target);
                    actual.add(result);
                } else {
                    assertSame(value, result, value + " to " + target);
                }
            }
            assertEquals(widened.getOrDefault(value, List.of()), actual, String.valueOf(value));
        }
    }

    @Test
    @DisplayName("a null type, or a null value to widen to a primitive type, is rejected, as the tables rejected it")
    void nulls() {
        assertThrows(NullPointerException.class, () -> Widening.wrap(null));
        assertThrows(NullPointerException.class, () -> Widening.order(null));
        assertThrows(NullPointerException.class, () -> Widening.isWrapper(null));
        assertThrows(NullPointerException.class, () -> Widening.widens(null, int.class));
        assertThrows(NullPointerException.class, () -> Widening.widens(null, void.class));
        assertThrows(NullPointerException.class, () -> Widening.widen(null, long.class));
        assertThrows(NullPointerException.class, () -> Widening.widens(1, null));
        assertFalse(Widening.widens(null, Integer.class));
        assertNull(Widening.widen(null, String.class));
    }

    @Test
    @DisplayName("looking a wrapper up allocates nothing, where MethodType.wrap() allocates on every call")
    void allocatesNothing() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int calls = 100_000;
        // Warmed up first, so class loading and the first compilations aren't counted.
        wrapAll(calls);

        long before = threads.getCurrentThreadAllocatedBytes();
        int found = wrapAll(calls);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertEquals(calls, found);
        // MethodType.wrap() allocates tens of bytes a call, megabytes for these calls. The bound leaves room for what
        // the measurement itself allocates, and is under one byte a call.
        assertTrue(allocated < calls, allocated + " bytes for " + calls + " calls");
    }

    @Test
    @DisplayName("the other lookups a run makes, order, isWrapper and widens, allocate nothing either")
    void otherLookupsAllocateNothing() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int calls = 100_000;
        for (int lookup = 0; lookup < LOOKUPS; lookup++) {
            // Warmed up first, as wrap is above, so class loading and the first compilations aren't counted.
            lookUpAll(lookup, calls);

            long before = threads.getCurrentThreadAllocatedBytes();
            int found = lookUpAll(lookup, calls);
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;

            assertEquals(calls, found, "lookup " + lookup);
            // As for wrap: the bound leaves room for what the measurement itself allocates, under one byte a call.
            assertTrue(allocated < calls, "lookup " + lookup + ": " + allocated + " bytes for " + calls + " calls");
        }
    }

    // Makes one lookup, by its number, the given number of times, and counts the answers it expects, so none of the
    // calls can be left out as unused.
    private static int lookUpAll(int lookup, int calls) {
        int found = 0;
        for (int i = 0; i < calls; i++) {
            int at = i % LOOKED_UP.size();
            boolean expected;
            if (lookup == 0) {
                expected = Widening.order(LOOKED_UP.get(at)) == (at == 0 ? 3 : -1);
            } else if (lookup == 1) {
                expected = Widening.isWrapper(LOOKED_UP.get(at)) == (at == 1);
            } else {
                expected = Widening.widens(WIDENED_VALUES.get(at), WIDENED_TO.get(at)) == (at == 0);
            }
            if (expected) {
                found++;
            }
        }
        return found;
    }

    private static int wrapAll(int calls) {
        int found = 0;
        for (int i = 0; i < calls; i++) {
            if (Widening.wrap(TYPES.get(i % TYPES.size())) != null) {
                found++;
            }
        }
        return found;
    }
}
