package io.github.brantunger.unruly.mvel;

import java.lang.reflect.InvocationTargetException;

/**
 * Finds what the Java code an MVEL expression called threw, under the exceptions MVEL wrapped it in.
 *
 * <p>
 * Until MVEL's JIT compiles an accessor for an expression, MVEL calls a method, getter, setter or constructor through
 * reflection, which wraps what the code threw in an {@link InvocationTargetException}, and MVEL wraps that in its own
 * exceptions: a {@code PropertyAccessException} or a {@code CompileException} the first times the expression runs, then
 * a {@link RuntimeException} such as {@code "cannot invoke method"}, once or more. The JIT's accessor calls the code
 * directly, so nothing wraps what it throws, and with {@code -Dmvel2.disable.jit=true} the reflective accessor stays.
 * Unwrapping gives a rule's failure the same cause at any of those stages: the exception the rule's own code threw.
 * </p>
 *
 * <p>
 * Only exceptions MVEL made are looked through: one of MVEL's own classes, or a plain {@link RuntimeException} thrown
 * from MVEL's code. The first exception below them is unwrapped only if it's an {@link InvocationTargetException} the
 * JDK's reflection made, so an exception chain the rule's code built itself, even one holding an
 * {@code InvocationTargetException}, reaches the engine as that code threw it, and MVEL's own errors, such as a
 * property it can't resolve, keep their shape. Anything this can't read leaves the exception as MVEL threw it: an
 * exception without a stack trace, one whose {@code getCause()} or {@code getStackTrace()} throws, or a chain of more
 * than {@value #MAX_DEPTH} of MVEL's exceptions, as one that loops back on itself is.
 * </p>
 *
 * <p>
 * Two failures stay under MVEL's exceptions. What a {@link java.util.Map}'s own {@code get} or an object's
 * {@code toString()} threw, the first times MVEL runs the expression: MVEL calls those directly, so no
 * {@code InvocationTargetException} marks what the code threw. And what an argument threw once MVEL's JIT compiled the
 * argument but not the call it's passed to, as happens when a run fails while the JIT compiles that call: MVEL's
 * reflective accessor then wraps what the argument threw directly, as it wraps its own errors in an argument, such as
 * a null in a property path.
 * </p>
 */
final class CalledCodeFailures {

    private static final String MVEL_PACKAGE = "org.mvel2.";
    private static final String REFLECTION_PACKAGE = "jdk.internal.reflect.";
    // MVEL wraps what the code threw in a few of its exceptions; a chain that loops back on itself never ends.
    private static final int MAX_DEPTH = 32;

    private CalledCodeFailures() {
    }

    /**
     * Returns the exception to throw in place of one MVEL threw: what the code the expression called threw, if MVEL
     * wrapped it, or else the exception itself. An {@link Error}, or a {@link Throwable} that is neither an error nor
     * an exception, is thrown instead of returned, unchecked, as it is once MVEL's JIT calls the code directly.
     *
     * @param <T>    The type the compiler takes a {@code Throwable} that isn't an {@link Exception} to be; it's thrown
     *               as it is, whatever its type
     * @param thrown What MVEL threw
     * @return The exception to throw
     * @throws T The error or other throwable the code threw
     */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> Exception unwrapped(RuntimeException thrown) throws T {
        Throwable failure = thrownByCalledCode(thrown);
        if (failure instanceof Exception exception) {
            return exception;
        }
        throw (T) failure;
    }

    /**
     * Returns what the code an expression called threw, if MVEL wrapped it, or else {@code thrown}.
     *
     * @param thrown What MVEL threw
     * @return What the called code threw, or {@code thrown}
     */
    static Throwable thrownByCalledCode(RuntimeException thrown) {
        try {
            Throwable below = thrown;
            for (int depth = 0; depth < MAX_DEPTH && below != null && madeByMvel(below); depth++) {
                below = below.getCause();
            }
            // A chain that loops back on itself ends the walk on an exception MVEL made, which isn't an
            // InvocationTargetException, and neither is thrown: that's a RuntimeException.
            if (below instanceof InvocationTargetException call && call.getCause() != null
                    && thrownFrom(call, REFLECTION_PACKAGE)) {
                return call.getCause();
            }
            return thrown;
        } catch (Throwable unreadable) {
            // An Error too: reading the chain is only to find a better cause, never a reason to fail differently.
            return thrown;
        }
    }

    private static boolean madeByMvel(Throwable t) {
        return t.getClass().getName().startsWith(MVEL_PACKAGE)
                || t.getClass() == RuntimeException.class && thrownFrom(t, MVEL_PACKAGE);
    }

    /** Whether {@code t} was created in a class of the package, going by the top frame of its stack trace. */
    private static boolean thrownFrom(Throwable t, String packagePrefix) {
        StackTraceElement[] trace = t.getStackTrace();
        return trace.length > 0 && trace[0].getClassName().startsWith(packagePrefix);
    }
}
