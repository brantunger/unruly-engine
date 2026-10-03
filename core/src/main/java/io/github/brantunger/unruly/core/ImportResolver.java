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

    /**
     * The most characters an import string may have. A longer one is rejected before it is looked up as a class, as
     * a parallel-capable class loader, such as the JDK's application class loader, keeps an object for each name it is
     * asked for, for as long as it lives.
     */
    static final int MAX_IMPORT_LENGTH = 1_000;

    /**
     * The most dot-separated parts an import string may have. A name that isn't a class is looked up again with each
     * dot from the right replaced by {@code $}, and a language such as MVEL looks up each name a rule uses in each
     * imported package the same way, so the work grows with the number of parts.
     */
    static final int MAX_IMPORT_PARTS = 64;

    private ImportResolver() {
    }

    /**
     * Works out what an import string names. A class the context class loader can load is imported on its own, so
     * {@code imports("java.time.LocalDate")} works; anything else must be a syntactically valid package name.
     * A nested class can be written as Java imports it ({@code java.util.Map.Entry}) or by its binary name
     * ({@code java.util.Map$Entry}), as in an inline MVEL {@code import}. In a native image built with strict
     * reachability metadata, a name the image has no metadata for isn't a class, as on the JVM a name no class has
     * isn't one, so a package import such as {@code java.util} still works there.
     *
     * @param name An import string given to the builder
     * @return The class, or {@code null} if {@code name} is a package name
     * @throws IllegalArgumentException if {@code name} has more than {@value #MAX_IMPORT_LENGTH} characters or more
     *                                  than {@value #MAX_IMPORT_PARTS} dot-separated parts, checked before it is
     *                                  looked up; if it is neither a loadable class nor a valid package name, or names
     *                                  a class that exists but can't be loaded, for example because a class it depends
     *                                  on is missing. The linkage error's text is cut to at most 1,000 characters,
     *                                  with a note of how many were left out, then escaped, as the application's own
     *                                  class loader may have written it, and a root cause it hides is named
     */
    static Class<?> resolve(String name) {
        checkSize(name);
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
                        + Failures.describeWithClass(e), e);
            }
            return packageImport(name, e);
        }
    }

    /**
     * Rejects an import string too long, or with too many parts, to look up, before any class loader sees it.
     * {@link EngineCompileContext} checks each package it is given with it too, so a context made outside an engine
     * rejects what {@code build()} does, and {@code mvel.Imports} keeps a copy for an import in a rule's own text,
     * which {@code ImportLimitCopiesTest} checks against this one.
     *
     * @param name An import string
     * @throws IllegalArgumentException if {@code name} has more than {@value #MAX_IMPORT_LENGTH} characters or more
     *                                  than {@value #MAX_IMPORT_PARTS} dot-separated parts
     */
    static void checkSize(String name) {
        checkLength(name);
        long parts = name.chars().filter(c -> c == '.').count() + 1;
        if (parts > MAX_IMPORT_PARTS) {
            throw new IllegalArgumentException("Can't import '" + Failures.quote(name) + "': it has " + parts
                    + " dot-separated parts, and an import may have at most " + MAX_IMPORT_PARTS);
        }
    }

    /**
     * Rejects an import string too long to pass on: the part of {@link #checkSize(String)} that applies to a
     * language's own imports too, from {@code RulesEngineBuilder.languageImports}, which no class loader sees, so their
     * parts aren't limited.
     *
     * @param name An import string
     * @throws IllegalArgumentException if {@code name} has more than {@value #MAX_IMPORT_LENGTH} characters
     */
    static void checkLength(String name) {
        if (name.length() > MAX_IMPORT_LENGTH) {
            throw new IllegalArgumentException("Can't import '" + Failures.quote(name) + "': it has " + name.length()
                    + " characters, and an import may have at most " + MAX_IMPORT_LENGTH);
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
            return load(name, loader);
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
            return load(name, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Loads a class, without initializing it, reporting a name a native image has no metadata for as a class that
     * isn't there (see {@link MissingRegistration#isMissingRegistration}), before any caller can take its error for a
     * class that exists but can't be loaded.
     *
     * @throws ClassNotFoundException if the class loader has no class by that name, or, in a native image built with
     *                                strict reachability metadata, no metadata for it, with the image's error as the
     *                                cause
     */
    private static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException {
        try {
            return loader.loadClass(name);
        } catch (Error e) {
            if (MissingRegistration.isMissingRegistration(e)) {
                throw new ClassNotFoundException(name, e);
            }
            throw e;
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

    // Read by code point, as Java reads a name, so a letter outside the Basic Multilingual Plane, a surrogate pair,
    // is a letter and a lone surrogate isn't. mvel.FactNames reads a fact's name by char on purpose: MVEL can't read
    // such a letter in a rule's text, so no rule could refer to a fact named with one.
    private static boolean isIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        int c = name.codePointAt(0);
        if (!Character.isJavaIdentifierStart(c)) {
            return false;
        }
        for (int i = Character.charCount(c); i < name.length(); i += Character.charCount(c)) {
            c = name.codePointAt(i);
            if (!Character.isJavaIdentifierPart(c)) {
                return false;
            }
        }
        return true;
    }
}
