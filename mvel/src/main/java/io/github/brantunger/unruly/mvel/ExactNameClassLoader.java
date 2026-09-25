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
 *
 * <p>
 * It's registered as parallel-capable. Otherwise the JVM would hold this loader's lock through every class lookup made
 * with it, so the threads compiling the rule list's expressions would look up even different names one at a time,
 * each waiting virtual thread pinned to its carrier. The application's class loader still decides what waits: the
 * JDK's own loaders serialize lookups of the same name, a loader that isn't parallel-capable serializes every lookup,
 * and a virtual thread stays on its carrier while the JVM loads a class.
 * </p>
 */
final class ExactNameClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

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
    // core.ImportResolver keeps a copy of this: the mvel package may not use that one. Fix both together. The error may
    // come from a context class loader of the application's own, so its message is read as core reads it.
    static boolean isWrongName(LinkageError error) {
        String message = ExceptionReads.messageOf(error);
        return error instanceof NoClassDefFoundError && message != null && message.contains("(wrong name: ");
    }
}
