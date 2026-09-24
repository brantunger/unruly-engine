package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Widening#wrap(Class)} gives what {@link MethodType#wrap()} gives, from a table, so the engine's check of every
 * declared fact of every run allocates nothing for it.
 */
@DisplayName("Widening.wrap gives a type's wrapper as MethodType does, without allocating")
class WideningTest {

    private static final List<Class<?>> TYPES = List.of(byte.class, short.class, char.class, int.class, long.class,
            float.class, double.class, boolean.class, void.class, Integer.class, String.class, int[].class,
            Object.class);

    @Test
    @DisplayName("each primitive type, void included, gives its wrapper, and any other type itself, as MethodType does")
    void matchesMethodType() {
        for (Class<?> type : TYPES) {
            assertSame(MethodType.methodType(type).wrap().returnType(), Widening.wrap(type), type.getName());
        }
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
