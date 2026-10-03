package io.github.brantunger.unruly.core;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * The error a native image built with strict reachability metadata throws for a lookup it has no metadata for, and a
 * method lookup that reads it as "not found", as any other build, and the JVM, would report it.
 * <b>Internal:</b> this class may change in any release. It's public only so that {@code api.BeansAndMapsWriter}, in
 * another package, shares it.
 */
public final class MissingRegistration {

    // What a native image built with strict reachability metadata throws for a name it has no metadata for.
    private static final String MISSING_REGISTRATION = "org.graalvm.nativeimage.MissingReflectionRegistrationError";

    private MissingRegistration() {
    }

    /**
     * Tells whether an error is the one a native image built with strict reachability metadata throws for a lookup it
     * has no metadata for, existing or not, where any other build, and the JVM, throw
     * {@link ClassNotFoundException} or {@link NoSuchMethodException}, or list what is there: GraalVM's
     * {@code MissingReflectionRegistrationError}, an {@link Error} in GraalVM for JDK 21 and a {@link LinkageError} in
     * GraalVM for JDK 25. It is told by its class's name, so this library needs no dependency on GraalVM's SDK, and no
     * other error, a {@link StackOverflowError} least of all, is taken for it.
     *
     * @param error The error a lookup threw
     * @return {@code true} for GraalVM's {@code MissingReflectionRegistrationError}
     */
    // mvel.ExactNameClassLoader keeps a copy of this: the mvel package may not use this one. Fix both together.
    public static boolean isMissingRegistration(Throwable error) {
        return MISSING_REGISTRATION.equals(error.getClass().getName());
    }

    /**
     * Returns a type's public method with a name and parameters, as {@link Class#getMethod} finds it, or {@code null}
     * where it finds none. In a native image built with strict reachability metadata, a lookup of a type or method the
     * image has no metadata for throws GraalVM's error instead (see {@link #isMissingRegistration}), whether or not
     * the type declares the method, and that counts as none too. Any other error is thrown on unchanged.
     *
     * @param type       The type to look the method up on
     * @param name       The method's name
     * @param parameters The method's parameter types
     * @return The method, or {@code null}
     */
    public static @Nullable Method publicMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Error e) {
            if (isMissingRegistration(e)) {
                return null;
            }
            throw e;
        }
    }
}
