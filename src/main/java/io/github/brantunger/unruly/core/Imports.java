package io.github.brantunger.unruly.core;

import org.mvel2.ParserConfiguration;

import java.util.Set;

/**
 * The imports a rule list is compiled with: whole packages ({@code java.util}) and single classes
 * ({@code java.time.LocalDate}), and the class loader their classes are looked up with.
 *
 * @param packages    Package names, imported with all their classes
 * @param classes     Classes imported one by one
 * @param classLoader The class loader that finds the classes in {@code packages}
 */
record Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader) {

    // For a thread that has no context class loader. PMD asks for the context class loader instead, which is exactly
    // what is missing in that case.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static final ClassLoader LIBRARY_CLASS_LOADER = Imports.class.getClassLoader();

    /** No imports. */
    static final Imports NONE = new Imports(Set.of(), Set.of(), LIBRARY_CLASS_LOADER);

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

    /**
     * Registers these imports and their class loader on a configuration.
     *
     * @param configuration The configuration to add the imports to
     */
    void applyTo(ParserConfiguration configuration) {
        configuration.setClassLoader(classLoader);
        packages.forEach(configuration::addPackageImport);
        classes.forEach(configuration::addImport);
    }
}
