package io.github.brantunger.unruly.core;

/**
 * Works out what the import strings an engine is built with name, and which class loader imported classes are looked up
 * with.
 */
final class ImportResolver {

    // For a thread that has no context class loader. PMD asks for the context class loader instead, which is exactly
    // what is missing in that case.
    @SuppressWarnings("PMD.UseProperClassLoader")
    static final ClassLoader LIBRARY_CLASS_LOADER = ImportResolver.class.getClassLoader();

    private ImportResolver() {
    }

    /**
     * Works out what an import string names. A class the context class loader can load is imported on its own, so
     * {@code imports("java.time.LocalDate")} works; anything else must be a syntactically valid package name.
     * A nested class can be written as Java imports it ({@code java.util.Map.Entry}) or by its binary name
     * ({@code java.util.Map$Entry}), as in an inline MVEL {@code import}.
     *
     * @param name An import string given to the builder
     * @return The class, or {@code null} if {@code name} is a package name
     * @throws IllegalArgumentException if {@code name} is neither a loadable class nor a valid package name, or names a
     *                                  class that exists but can't be loaded, for example because a class it depends
     *                                  on is missing
     */
    static Class<?> resolve(String name) {
        try {
            return loadImport(name, contextClassLoader());
        } catch (ClassNotFoundException e) {
            return packageImport(name, e);
        } catch (LinkageError e) {
            // A class file found for a name that differs in case, in a class directory on a case-insensitive file
            // system, isn't this class. Any other linkage error means the class exists, and rules couldn't use it.
            if (!isWrongName(e)) {
                throw new IllegalArgumentException("Can't import '" + Failures.quote(name)
                        + "': the class exists but can't be loaded: "
                        + Failures.textOf(e), e);
            }
            return packageImport(name, e);
        }
    }

    /**
     * Accepts a name that isn't a class as a package import.
     *
     * @return {@code null}, for a package name
     * @throws IllegalArgumentException if {@code name} isn't a valid package name
     */
    private static Class<?> packageImport(String name, Throwable notAClass) {
        if (!isPackageName(name)) {
            throw new IllegalArgumentException("'" + Failures.quote(name)
                    + "' is neither a class nor a valid package name", notAClass);
        }
        return null;
    }

    // The JVM's "wrong name" NoClassDefFoundError, as in mvel.ExactNameClassLoader. The error may come from a context
    // class loader of the application's own, so its message is read as any exception's the engine didn't create is.
    private static boolean isWrongName(LinkageError error) {
        String message = Failures.messageOf(error);
        return error instanceof NoClassDefFoundError && message != null && message.contains("(wrong name: ");
    }

    /**
     * Loads an imported class as MVEL looks one up: by its name, then with each dot from the right replaced by
     * {@code $} in turn, so {@code java.util.Map.Entry} finds {@code java.util.Map$Entry}. The class isn't
     * initialized.
     *
     * @throws ClassNotFoundException the first lookup's exception, if no form of the name is a class
     */
    private static Class<?> loadImport(String name, ClassLoader loader) throws ClassNotFoundException {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException notFound) {
            String binaryName = name;
            for (int dot = name.lastIndexOf('.'); dot > 0; dot = binaryName.lastIndexOf('.')) {
                binaryName = binaryName.substring(0, dot) + '$' + binaryName.substring(dot + 1);
                Class<?> nested = loadOrNull(binaryName, loader);
                if (nested != null) {
                    return nested;
                }
            }
            throw notFound;
        }
    }

    private static Class<?> loadOrNull(String name, ClassLoader loader) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Returns the class loader to look up imported classes with: the calling thread's context class loader, or this
     * library's own loader when the thread has none. Left to itself, MVEL takes the context class loader of whichever
     * thread first needs one, so a lookup made from another thread could see different classes.
     *
     * @return The class loader for imports set up on this thread
     */
    static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : LIBRARY_CLASS_LOADER;
    }

    private static boolean isPackageName(String name) {
        for (String part : name.split("\\.", -1)) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    // mvel.FactNames keeps a copy of this: the mvel package may not use this one. Fix both together.
    private static boolean isIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
