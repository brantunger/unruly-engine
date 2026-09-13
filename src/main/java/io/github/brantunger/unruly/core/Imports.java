package io.github.brantunger.unruly.core;

import org.mvel2.ParserConfiguration;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The imports a rule list is compiled with: whole packages ({@code java.util}) and single classes
 * ({@code java.time.LocalDate}), the class loader their classes are looked up with, and the names every compilation of
 * the rule list has found not to be classes.
 *
 * @param packages    Package names, imported with all their classes
 * @param classes     Classes imported one by one
 * @param classLoader The class loader that finds the classes in {@code packages}
 * @param notClasses  Names found not to be a class in any of {@code packages}, shared by the configurations created
 *                    with {@link #newConfiguration()}
 */
record Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader, Set<String> notClasses) {

    // For a thread that has no context class loader. PMD asks for the context class loader instead, which is exactly
    // what is missing in that case.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static final ClassLoader LIBRARY_CLASS_LOADER = Imports.class.getClassLoader();

    /** No imports. */
    static final Imports NONE = new Imports(Set.of(), Set.of(), LIBRARY_CLASS_LOADER);

    /**
     * Creates the imports for one rule list, with nothing known yet about which names aren't classes.
     *
     * @param packages    Package names, imported with all their classes
     * @param classes     Classes imported one by one
     * @param classLoader The class loader that finds the classes in {@code packages}
     */
    Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader) {
        this(packages, classes, classLoader, ConcurrentHashMap.newKeySet());
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

    /**
     * Creates a configuration for compiling one expression, with these imports and class loader registered.
     *
     * @return A new configuration, used by one compilation only
     */
    ParserConfiguration newConfiguration() {
        SharedLookupConfiguration configuration = new SharedLookupConfiguration(notClasses);
        configuration.setClassLoader(classLoader);
        if (!packages.isEmpty()) {
            // addPackageImport would first try to load each package name as a class, for every expression compiled.
            // addImport already decided that these strings aren't classes.
            configuration.setPackageImports(new LinkedHashSet<>(packages));
        }
        classes.forEach(configuration::addImport);
        configuration.startSharing();
        return configuration;
    }

    /**
     * A configuration that shares the answer "this name isn't a class" with every other configuration created from
     * the same imports. MVEL keeps that answer only on the configuration that looked the name up, by trying to load it
     * from every imported package, and each condition and action is compiled with a configuration of its own, so one
     * rule can't change how another compiles. Without sharing, a name was looked up again for every expression that
     * used it, and again for every compiled copy made for concurrent runs.
     */
    private static final class SharedLookupConfiguration extends ParserConfiguration {

        private static final long serialVersionUID = 1L;

        private final transient Set<String> notClasses;
        // How many packages are imported once the rule list's imports are registered, or -1 until then. An inline
        // `import pkg.*;` in the expression adds another, where a name that isn't a class elsewhere may be one.
        private int sharedPackageCount = -1;

        SharedLookupConfiguration(Set<String> notClasses) {
            this.notClasses = notClasses;
        }

        void startSharing() {
            sharedPackageCount = packageCount();
        }

        @Override
        public boolean hasImport(String name) {
            boolean sharing = packageCount() == sharedPackageCount;
            // A class imported inline by this expression is in its own imports, whatever other expressions found.
            if (sharing && !imports.containsKey(name) && notClasses.contains(name)) {
                return false;
            }
            boolean found = super.hasImport(name);
            if (!found && sharing) {
                notClasses.add(name);
            }
            return found;
        }

        private int packageCount() {
            Set<String> packageImports = getPackageImports();
            return packageImports != null ? packageImports.size() : 0;
        }
    }
}
