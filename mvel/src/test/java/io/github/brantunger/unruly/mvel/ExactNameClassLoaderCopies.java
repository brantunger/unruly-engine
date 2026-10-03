package io.github.brantunger.unruly.mvel;

/**
 * Gives a test in another package, such as {@code core.WrongNameCopiesTest}, which also reaches
 * {@code core.ImportResolver}, the copy of the engine's "wrong name" check {@link ExactNameClassLoader} keeps.
 */
public final class ExactNameClassLoaderCopies {

    private ExactNameClassLoaderCopies() {
    }

    /**
     * Tells whether an error is the JVM's "wrong name" error for a name, as {@link ExactNameClassLoader#isWrongName}
     * does.
     *
     * @param error The error a class lookup threw
     * @param name  The binary name the lookup asked for
     * @return {@code true} for the JVM's "wrong name" {@code NoClassDefFoundError} for {@code name}
     */
    public static boolean isWrongName(NoClassDefFoundError error, String name) {
        return ExactNameClassLoader.isWrongName(error, name);
    }
}
