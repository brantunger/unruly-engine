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

    // The most characters and dot-separated parts an import in an expression's own text may have, as the engine's
    // core.ImportResolver allows an engine's imports, which the mvel package may not use.
    private static final int MAX_IMPORT_LENGTH = 1_000;
    private static final int MAX_IMPORT_PARTS = 64;

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
     * Rejects an import too long, or with too many parts, to look up, with the same message as the engine's
     * {@code core.ImportResolver.checkSize}, which the {@code mvel} package may not use.
     * {@code ImportLimitCopiesTest} runs the same names through both, so the two can't drift apart.
     *
     * @param name The package's name
     * @throws ImportTooLarge if {@code name} has more than 1,000 characters or more than 64 dot-separated parts
     */
    static void checkSize(String name) {
        if (name.length() > MAX_IMPORT_LENGTH) {
            throw new ImportTooLarge(name, "Can't import '" + FactNames.quote(name) + "': it has " + name.length()
                    + " characters, and an import may have at most " + MAX_IMPORT_LENGTH);
        }
        long parts = name.chars().filter(c -> c == '.').count() + 1;
        if (parts > MAX_IMPORT_PARTS) {
            throw new ImportTooLarge(name, "Can't import '" + FactNames.quote(name) + "': it has " + parts
                    + " dot-separated parts, and an import may have at most " + MAX_IMPORT_PARTS);
        }
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
         * Imports a package an {@code import pkg.*;} in the expression's own text names, as MVEL does, once its name
         * is checked as the engine checks its own imports: MVEL looks each name the expression uses up in it, once for
         * each dot, as a nested class. The rule list's own packages are set, not added, so they aren't checked again.
         * An inline import of a class, or an {@code import_static}, isn't checked: MVEL looks its name up once or
         * twice, then rejects the expression if it isn't a class.
         *
         * @param packageName The package's name, as the expression writes it
         * @throws ImportTooLarge if the name has more than 1,000 characters or more than 64 dot-separated parts,
         *                        before it is looked up
         */
        @Override
        public void addPackageImport(String packageName) {
            checkSize(packageName);
            super.addPackageImport(packageName);
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

    /**
     * What the engine throws when an {@code import} in an expression's own text names a package too long, or with too
     * many parts, to look up, which the compiler reports as a compile error at the name. MVEL lets it through as it
     * is, as it does any {@link RuntimeException} that isn't its own.
     */
    static final class ImportTooLarge extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final String name;

        ImportTooLarge(String name, String message) {
            super(message);
            this.name = name;
        }

        /**
         * Returns the rejected name, as the expression writes it.
         *
         * @return The name
         */
        String rejectedName() {
            return name;
        }
    }
}
