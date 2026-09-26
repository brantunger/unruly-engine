package io.github.brantunger.unruly.mvel;

import org.mvel2.ParserConfiguration;
import org.mvel2.util.MethodStub;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The imports a rule list is compiled with: whole packages ({@code java.util}) and single classes
 * ({@code java.time.LocalDate}), the class loader their classes are looked up with, and the names every compilation of
 * the rule list has found not to be classes.
 *
 * @param packages    Package names, imported with all their classes
 * @param classes     Classes imported one by one
 * @param classLoader The class loader that finds the classes in {@code packages}, as an {@link ExactNameClassLoader}
 * @param notClasses  Names found not to be a class in any of {@code packages}, shared by the configurations created
 *                    with {@link #newConfiguration()}
 * @param inputs      The type of each name an expression may refer to, for compiling with strong typing, or an empty
 *                    map to compile as MVEL does by default. See {@link DeclaredTypes}.
 */
record Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader, Set<String> notClasses,
               Map<String, Class<?>> inputs) {

    /**
     * Creates the imports for one rule list, with nothing known yet about which names aren't classes. The class loader
     * is wrapped once here, for every expression of the rule list, in an {@link ExactNameClassLoader}.
     *
     * @param packages    Package names, imported with all their classes
     * @param classes     Classes imported one by one
     * @param classLoader The application's class loader that finds the classes in {@code packages}
     */
    Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader) {
        this(packages, classes, classLoader, Map.of());
    }

    /**
     * Creates the imports for one rule list, with the types its expressions are compiled against.
     *
     * @param packages    Package names, imported with all their classes
     * @param classes     Classes imported one by one
     * @param classLoader The application's class loader that finds the classes in {@code packages}
     * @param inputs      The type of each name an expression may refer to, or an empty map for no strong typing
     */
    Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader, Map<String, Class<?>> inputs) {
        this(packages, classes, new ExactNameClassLoader(classLoader), ConcurrentHashMap.newKeySet(),
                Map.copyOf(inputs));
    }

    /**
     * Returns whether expressions are compiled with strong typing, which they are exactly when the engine declared
     * types MVEL can check.
     *
     * @return {@code true} if every name an expression may refer to has a declared type
     */
    boolean stronglyTyped() {
        return !inputs.isEmpty();
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
            // The engine decided when it was built that these strings aren't classes.
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
     * used it, and again for the compiled copy of every session that runs it.
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

        /**
         * Returns the static method imported as {@code name}, as MVEL does, unless the name is an imported class. MVEL
         * asks for one when an expression calls a name it imports like a method, such as {@code ArrayList(y)} with
         * {@code java.util} imported, and casts whatever is imported as that name to a method, which fails for a
         * class with a {@link ClassCastException} that says nothing of the expression.
         *
         * @param name The name called like a method
         * @return The method imported as {@code name}, or {@code null} if none is
         * @throws ClassCalledLikeMethod if {@code name} is an imported class
         */
        @Override
        public MethodStub getStaticImport(String name) {
            if (imports.get(name) instanceof Class) {
                throw new ClassCalledLikeMethod(name);
            }
            return super.getStaticImport(name);
        }

        private int packageCount() {
            Set<String> packageImports = getPackageImports();
            return packageImports != null ? packageImports.size() : 0;
        }
    }

    /**
     * What the engine throws in place of MVEL's own {@link ClassCastException} when an expression calls an imported
     * class like a method, such as {@code ArrayList(y)}, which the compiler reports as a compile error. It is still a
     * {@link ClassCastException}, so MVEL handles it as it did its own.
     */
    static final class ClassCalledLikeMethod extends ClassCastException {

        private static final long serialVersionUID = 1L;

        ClassCalledLikeMethod(String name) {
            super(name + " is an imported class, not a method");
        }
    }
}
