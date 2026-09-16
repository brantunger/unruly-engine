package io.github.brantunger.unruly.mvel;

/**
 * The class loader MVEL compiles rules with. It asks the application's class loader for every class, but reports a
 * class file whose name only matches in a different case as a missing class.
 *
 * <p>
 * While compiling {@code applicant.creditScore}, MVEL checks whether {@code applicant} is a class. In a class directory
 * on a case-insensitive file system (the default on Windows and macOS), the lookup for {@code applicant.class} finds
 * {@code Applicant.class}, and the JVM throws {@code NoClassDefFoundError: applicant (wrong name: Applicant)}. MVEL
 * doesn't catch it, so the rule would fail to compile. Reported as a {@link ClassNotFoundException}, it tells MVEL
 * that {@code applicant} isn't a class, so it reads it as the fact. Any other linkage error is passed on unchanged.
 * </p>
 */
final class ExactNameClassLoader extends ClassLoader {

    /**
     * Creates a class loader that asks {@code parent} for every class.
     *
     * @param parent The application's class loader
     */
    ExactNameClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        try {
            return getParent().loadClass(name);
        } catch (NoClassDefFoundError e) {
            if (isWrongName(e)) {
                throw new ClassNotFoundException(name, e);
            }
            throw e;
        }
    }

    /**
     * Tells whether a linkage error only means that a class file was found for a name that differs in case, so there
     * is no class by the name that was looked up.
     *
     * @param error The error a class lookup threw
     * @return {@code true} for the JVM's "wrong name" {@code NoClassDefFoundError}
     */
    // core.ImportResolver keeps a copy of this: the mvel package may not use that one. Fix both together.
    static boolean isWrongName(LinkageError error) {
        String message = error.getMessage();
        return error instanceof NoClassDefFoundError && message != null && message.contains("(wrong name: ");
    }
}
