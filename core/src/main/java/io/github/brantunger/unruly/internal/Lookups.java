package io.github.brantunger.unruly.internal;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * The errors a class or method lookup throws that mean the name wasn't found, rather than that something is broken:
 * the JVM's "wrong name" error for a class file found in another case, and the error a native image built with strict
 * reachability metadata throws for a lookup it has no metadata for, where any other build, and the JVM, would report
 * "not found". {@code api.language.Throwables} publishes both checks for expression languages, which look names up as
 * the engine does rather than keeping a copy that could drift. <b>Internal:</b> this class may change in any release,
 * and isn't part of the API: its package isn't exported, and the {@code api} and {@code core} packages share it.
 */
public final class Lookups {

    // What a native image built with strict reachability metadata throws for a name it has no metadata for.
    private static final String MISSING_REGISTRATION = "org.graalvm.nativeimage.MissingReflectionRegistrationError";

    // What OpenJ9's java.vm.name holds, which writes the name asked for last in its "wrong name" error.
    private static final String OPENJ9 = "OpenJ9";

    private Lookups() {
    }

    /**
     * Tells whether a linkage error only means that a class file was found for the name looked up in a different case,
     * so there is no class by that name: the JVM's "wrong name" {@link NoClassDefFoundError}. Its message names two
     * classes in internal form, the one asked for and the one in the class file found, and only the side this JVM
     * writes the one asked for on is read: first on HotSpot, last on OpenJ9 (told by {@code java.vm.name}). An error
     * about a class the one looked up depends on, such as its superclass, doesn't name it there, so it isn't taken for
     * it, even where the superclass's name differs from it only in case, so the file found is its own and the message
     * names it on the other side (#1261). The error may come from a context class loader of the application's own, so
     * its message is read as any exception's the engine didn't create is (see {@link Causes#messageOf}): one that
     * can't be read isn't taken for it either. HotSpot cuts the message at 1023 bytes of UTF-8, so for a name over
     * about 1,000 ASCII characters, or 500 two-byte ones, {@code " (wrong name: "} is cut off and the error isn't
     * recognised; written last, as on OpenJ9, the name would be cut off itself. The class then can't be loaded rather
     * than being missing: an error, never a wrong answer (#1001). {@link ImportSize#MAX_IMPORT_LENGTH} keeps an ASCII
     * import under the cut, but not one of two-byte characters.
     *
     * @param error The error a class lookup threw
     * @param name  The binary name the lookup asked for
     * @return {@code true} for the JVM's "wrong name" {@code NoClassDefFoundError} for {@code name}
     */
    public static boolean isWrongName(Throwable error, String name) {
        return error instanceof NoClassDefFoundError
                && isWrongName(error, name, namesLookupLast(System.getProperty("java.vm.name")));
    }

    /**
     * Tells whether a {@link NoClassDefFoundError} is the JVM's "wrong name" error for a name, reading the side of its
     * message that the given order puts the name asked for on.
     *
     * @param error      The error a class lookup threw
     * @param name       The binary name the lookup asked for
     * @param lookupLast Whether the JVM writes the name asked for last, as OpenJ9 does, rather than first, as HotSpot
     *                   does
     * @return {@code true} for the JVM's "wrong name" error for {@code name}
     */
    static boolean isWrongName(Throwable error, String name, boolean lookupLast) {
        String message = Causes.messageOf(error);
        String internal = name.replace('.', '/');
        if (message == null) {
            return false;
        }
        if (lookupLast) {
            String last = " (wrong name: " + internal + ")";
            return hasAt(message, message.length() - last.length(), last);
        }
        return hasAt(message, 0, internal + " (wrong name: ");
    }

    /**
     * Tells whether a JVM writes the name asked for last in its "wrong name" error: OpenJ9, whose {@code java.vm.name}
     * is {@code Eclipse OpenJ9 VM} whoever builds it (IBM Semeru reports IBM as its vendor). {@link #isWrongName}
     * reads the property on every call and never into a field, so this class keeps no static initializer, which a
     * first call deep in a stack could leave broken for the life of the JVM (#1260). Compared a char at a time, as
     * {@code hasAt} explains, not with {@link String#contains}.
     *
     * @param vmName The JVM's {@code java.vm.name}, or {@code null} if it has none
     * @return {@code true} on OpenJ9
     */
    static boolean namesLookupLast(@Nullable String vmName) {
        if (vmName == null) {
            return false;
        }
        for (int i = 0; i <= vmName.length() - OPENJ9.length(); i++) {
            if (hasAt(vmName, i, OPENJ9)) {
                return true;
            }
        }
        return false;
    }

    // Whether text has part at from. Compared a char at a time, not with String.startsWith, endsWith or
    // regionMatches: on JDK 21 they compare eight bytes at a time, and a mismatch within one of those whole words calls
    // Long.numberOfTrailingZeros, which initializes java.lang.Long, so a first call through Throwables deep in a stack
    // could break it for the JVM (#1260).
    private static boolean hasAt(String text, int from, String part) {
        if (from < 0 || from > text.length() - part.length()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            if (text.charAt(from + i) != part.charAt(i)) {
                return false;
            }
        }
        return true;
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
