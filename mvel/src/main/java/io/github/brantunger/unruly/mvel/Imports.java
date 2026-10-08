package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.MessageText;
import org.jspecify.annotations.Nullable;
import org.mvel2.ParserConfiguration;
import org.mvel2.compiler.AbstractParser;
import org.mvel2.util.MethodStub;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The imports a rule list is compiled with: whole packages ({@code java.util}) and single classes
 * ({@code java.time.LocalDate}), the class loader their classes are looked up with, and the names every compilation of
 * the rule list has found not to be classes, and the first parts of the names of the classes its compilations found.
 *
 * @param packages       Package names, imported with all their classes
 * @param classes        Classes imported one by one
 * @param classLoader    The class loader that finds the classes in {@code packages}, as an
 *                       {@link ExactNameClassLoader}
 * @param notClasses     Names found not to be a class in any of {@code packages}, shared by the configurations
 *                       created with {@link #newConfiguration()}
 * @param inputs         The type of each name an expression may refer to, for compiling with strong typing, or an
 *                       empty map to compile as MVEL does by default. See {@link DeclaredTypes}.
 * @param classNameRoots The first part of the name of each class {@code classLoader} found while the rule list's
 *                       expressions compiled, such as {@code java} for {@code java.lang.Integer}, which
 *                       {@link FactNames} rejects as a fact's name
 */
record Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader, Set<String> notClasses,
               Map<String, Class<?>> inputs, ClassNameRoots classNameRoots) {

    // The most characters and dot-separated parts an import in an expression's own text may have, as the engine's
    // core.ImportResolver allows an engine's imports, which the mvel package may not use. ExactNameClassLoader bounds
    // the names it looks up from these.
    static final int MAX_IMPORT_LENGTH = 1_000;
    static final int MAX_IMPORT_PARTS = 64;

    // The class every class loader of the JDK's own extends (see isJdkLoader).
    private static final String JDK_LOADER = "jdk.internal.loader.BuiltinClassLoader";

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
        this(packages, classes, classLoader, inputs, new ClassNameRoots());
    }

    // The class loader is wrapped with the class name roots it records into, which the imports keep too.
    private Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader, Map<String, Class<?>> inputs,
                    ClassNameRoots classNameRoots) {
        this(packages, classes, new ExactNameClassLoader(classLoader, classNameRoots), ConcurrentHashMap.newKeySet(),
                Map.copyOf(inputs), classNameRoots);
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
        return newConfiguration(-1, 0);
    }

    /**
     * Creates a configuration for compiling one expression that then runs, with these imports and class loader
     * registered, which MVEL may ask for its class loader only so many times in each run, and only so many times from
     * one place (see {@link #startRun}). MVEL's compiled expression keeps the configuration, and asks it on every
     * round of a loop that never ends, from the same place, and never checks for an interrupt or the run's deadline,
     * as it builds what reads a value the first time the expression runs (#857). Once a run has asked more times, the
     * configuration throws a {@link RunLoop}. Each run has the whole limit: an expression whose run fails while MVEL
     * builds what reads a value asks again in every run, and would run out after enough of them. While the expression
     * compiles, MVEL may ask any number of times.
     *
     * @param callsPerRun  How many times MVEL may ask in each run
     * @param callsPerSite How many times MVEL may ask from one place in each run (see {@link CallSites}), once it has
     *                     asked {@value CallSites#UNCOUNTED_CALLS} times, or 0 for no limit
     * @return A new configuration, used by one compilation and the runs of what it compiled only
     */
    ParserConfiguration newRunConfiguration(long callsPerRun, long callsPerSite) {
        return newConfiguration(callsPerRun, callsPerSite);
    }

    private ParserConfiguration newConfiguration(long callsPerRun, long callsPerSite) {
        // The class loader is an ExactNameClassLoader, which asks its parent, the application's, for every class a
        // name may be.
        SharedLookupConfiguration configuration = new SharedLookupConfiguration(notClasses,
                isJdkLoader(classLoader.getParent()), callsPerRun, callsPerSite);
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
     * Tells whether a class loader is one of the JDK's own, the application or the platform class loader, which both
     * extend the JDK's internal {@code jdk.internal.loader.BuiltinClassLoader}, and delegate to the boot loader. Each
     * serves the class file of every class it loads from the class path, the module path or the JDK's own modules,
     * but not of a class defined in it at run time, such as with {@code MethodHandles.Lookup.defineClass} by a code
     * generator. None can be extended outside the JDK, whose package isn't exported, so a class loader of an
     * application or a framework never is one, even one set as the system class loader with
     * {@code -Djava.system.class.loader}. The class is matched by name, which needs no access to the JDK's internals. A
     * {@code null} loader, the boot loader, isn't one here: the rule list's class loader, which asks its parent for
     * every class, can't load through it.
     *
     * @param loader The class loader, or {@code null}
     * @return {@code true} if the loader is the JDK's own
     */
    static boolean isJdkLoader(ClassLoader loader) {
        for (Class<?> type = loader == null ? null : loader.getClass(); type != null; type = type.getSuperclass()) {
            if (JDK_LOADER.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rejects an import too long, or with too many parts, to look up, with the same message as the engine's
     * {@code core.ImportResolver.checkSize}, which the {@code mvel} package may not use.
     * {@code ImportLimitCopiesTest} runs the same names through both, so the two can't drift apart.
     *
     * @param name The package's name
     * @throws ImportTooLarge if {@code name} has more than {@value #MAX_IMPORT_LENGTH} characters or more than
     *                        {@value #MAX_IMPORT_PARTS} dot-separated parts
     */
    static void checkSize(String name) {
        if (name.length() > MAX_IMPORT_LENGTH) {
            throw new ImportTooLarge(name, "': it has " + name.length() + " characters, and an import may have at "
                    + "most " + MAX_IMPORT_LENGTH);
        }
        long parts = RuleText.dottedParts(name);
        if (parts > MAX_IMPORT_PARTS) {
            throw new ImportTooLarge(name, "': it has " + parts + " dot-separated parts, and an import may have at "
                    + "most " + MAX_IMPORT_PARTS);
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
        // The names found not to be classes once the expression imported a package of its own, which only it has.
        // Bounded by the names the one expression uses.
        private final transient Set<String> ownNotClasses = new HashSet<>();
        // Whether a name is looked up as a class file before MVEL loads it: only for the JDK's own class loaders.
        private final boolean classFileFirst;
        // How many packages are imported once the rule list's imports are registered, or -1 until then. An inline
        // `import pkg.*;` in the expression adds another, where a name that isn't a class elsewhere may be one.
        private int sharedPackageCount = -1;
        // How many more times MVEL may ask for the class loader, or -1 for any number: in MVEL's analysis (see
        // limitClassLoaderCalls), or in the run under way (see startRun). Not transient: a copy read back would
        // otherwise have 0 left.
        private long classLoaderCallsLeft = -1;
        // How many times MVEL may ask for the class loader in each run of the expression compiled with the
        // configuration, or -1 for a configuration that only compiles, such as for MVEL's analysis (see
        // newRunConfiguration). Set once, so which of the two is thrown doesn't depend on the order of the calls.
        private final long classLoaderCallsPerRun;
        // How many times MVEL may ask for the class loader from one place, in its analysis or in each run, or 0 for no
        // limit, which sites() counts as Long.MAX_VALUE: a configuration read back from a form written before there
        // was one has 0.
        private long classLoaderCallsPerSite;
        // Where MVEL asked from, in its analysis or the run under way, while the calls are limited, or null until it is
        // first needed. Transient: what a pass or run has counted means nothing to a copy read back.
        private transient @Nullable CallSites callSites;
        // What MVEL's analysis threw once a call passed a limit, or null.
        private transient AnalysisLoop analysisLoop;
        // What the run under way threw once a call passed a limit, or null (see startRun).
        private transient RunLoop runLoop;
        // Whether the configuration is looking a name up among the imports, whose calls don't count (see hasImport).
        private transient boolean lookingUp;

        SharedLookupConfiguration(Set<String> notClasses, boolean classFileFirst, long classLoaderCallsPerRun,
                                  long classLoaderCallsPerSite) {
            this.notClasses = notClasses;
            this.classFileFirst = classFileFirst;
            this.classLoaderCallsPerRun = classLoaderCallsPerRun;
            this.classLoaderCallsPerSite = classLoaderCallsPerSite;
        }

        /**
         * Returns the count of where MVEL asked from, created the first time it is needed, such as in a copy read
         * back: by the whole stack for a configuration that only compiles, and by the stack's top and depth for one
         * whose runs are limited.
         *
         * @return The count for the analysis or the run under way
         */
        private CallSites sites() {
            if (callSites == null) {
                callSites = new CallSites(classLoaderCallsPerRun < 0,
                        classLoaderCallsPerSite > 0 ? classLoaderCallsPerSite : Long.MAX_VALUE);
            }
            return callSites;
        }

        void startSharing() {
            sharedPackageCount = packageCount();
        }

        /**
         * Returns the class loader, as MVEL's configuration does, once for each time MVEL asks for it, up to the limits
         * set with {@link #limitClassLoaderCalls}, or for each run, for a configuration {@link #newRunConfiguration}
         * created: how many times in all, and how many from one place (see {@link CallSites}). MVEL asks on every round
         * of the loop in its analysis that never ends (#840), and of the one in what it builds as the expression runs
         * (#857), from the same place each round. Once a call passes either limit, it and every call after it throws
         * the same {@link AnalysisLoop}, or {@link RunLoop} as the expression runs: MVEL catches every
         * {@link Exception} where it asks in those loops, and some {@link Throwable}s elsewhere, so it is thrown again
         * until it gets out. The limit in all is counted down before a call is counted by its place, so deep in a
         * stack, where a walk of the stack has no room and the call counts at no place (#1102), it still stops a loop,
         * with the same error. The calls made while a name is looked up among the imports (see {@link #hasImport})
         * don't count, and never throw: they are made once for each imported package and each name, not on each round
         * of the loop, and MVEL's lookup would catch what was thrown and remember a class's name, for every expression
         * of the rule list, as a name that isn't one.
         *
         * @return The class loader
         * @throws AnalysisLoop if MVEL's analysis asked more times than a limit allows
         * @throws RunLoop      if MVEL asked more times than a limit allows in one run of the expression
         */
        @Override
        public ClassLoader getClassLoader() {
            if (lookingUp) {
                return super.getClassLoader();
            }
            if (classLoaderCallsLeft == 0) {
                throw looped();
            }
            if (classLoaderCallsLeft > 0) {
                classLoaderCallsLeft--;
                if (sites().counted()) {
                    classLoaderCallsLeft = 0;
                    throw looped();
                }
            }
            return super.getClassLoader();
        }

        // What MVEL went round in a loop in: its analysis, for a configuration that only compiles, or a run.
        private Error looped() {
            if (classLoaderCallsPerRun < 0) {
                if (analysisLoop == null) {
                    analysisLoop = new AnalysisLoop();
                }
                return analysisLoop;
            }
            if (runLoop == null) {
                runLoop = new RunLoop();
            }
            return runLoop;
        }

        /**
         * Tells whether a name is an imported class, as MVEL does, with two differences.
         *
         * <p>
         * A name such as {@code Outer.Nested}, whose first part is a class imported on its own, is that class's
         * nested class, as in Java: MVEL finds one only through a package import, as {@code pkg.Outer$Nested}. It is
         * loaded by that name only once the class loader has its class file, so no name that isn't a class is looked
         * up.
         * </p>
         *
         * <p>
         * MVEL looks a name up in each imported package by loading it as a class, then with each dot from the right
         * turned into {@code $}, as a nested class. A parallel-capable class loader, as the JDK's own loaders are,
         * keeps a lock object for every name it is asked to load, found or not, for as long as it lives, so every
         * name in a rule that isn't a class left some, for each package imported. So when the application's class
         * loader is one of the JDK's own (see {@link #isJdkLoader}), MVEL is only asked once it has the class file for
         * one of those names (see {@link FactNames#mayBeClass}), and a name that has none isn't a class: the JDK's
         * loaders serve the class file of every class they load from the class path, the module path or the JDK's
         * modules. A name the rule list's class loader refuses to look up (see {@link ExactNameClassLoader#refuses})
         * isn't looked for at all then, as it can't be a class either, nor is one MVEL rejects outright, which doesn't
         * start as a Java identifier does. Any other class loader, such as a framework's or an application server's,
         * is asked as MVEL asks it, and keeps its lock objects, as it may define a class it serves no class file for.
         * </p>
         *
         * <p>
         * So a class defined at run time in one of the JDK's loaders, such as with
         * {@code MethodHandles.Lookup.defineClass} by a code generator, which has no class file, isn't found through
         * an import of its package: it is imported by its class, with an import of the class itself in the engine,
         * which finds it as before. An inline import of it in the rule's text doesn't (see
         * {@link ExactNameClassLoader}).
         * </p>
         *
         * <p>
         * A name found not to be a class is remembered, for every expression compiled with the same imports, or, once
         * the expression imports a package of its own, for the rest of the expression, as MVEL asks for a name more
         * than once.
         * </p>
         *
         * @param name The name, as the expression writes it, such as {@code ArrayList} or {@code Outer.Nested}
         * @return {@code true} if the name is an imported class
         */
        @Override
        public boolean hasImport(String name) {
            lookingUp = true;
            try {
                return lookUp(name);
            } finally {
                lookingUp = false;
            }
        }

        private boolean lookUp(String name) {
            // A class imported inline by this expression is in its own imports, whatever other expressions found.
            if (imports.containsKey(name) || importNested(name)) {
                return true;
            }
            Set<String> misses = packageCount() == sharedPackageCount ? notClasses : ownNotClasses;
            if (misses.contains(name)) {
                return false;
            }
            boolean found = (!classFileFirst || AbstractParser.CLASS_LITERALS.containsKey(name) || hasClassFile(name))
                    && super.hasImport(name);
            if (!found) {
                misses.add(name);
            }
            return found;
        }

        /**
         * Imports a name such as {@code Outer.Nested}, whose first part is a class imported on its own, as that
         * class's nested class, and each further part as a class nested in the one before, if there is one.
         *
         * @param name The name, as the expression writes it
         * @return {@code true} if the name is a nested class of an imported class, now imported by that name
         */
        private boolean importNested(String name) {
            int dot = name.indexOf('.');
            if (dot < 0 || !(imports.get(name.substring(0, dot)) instanceof Class<?> outer)) {
                return false;
            }
            Class<?> nested = outer;
            for (String part : name.substring(dot + 1).split("\\.", -1)) {
                nested = declaredClass(nested, part);
                if (nested == null) {
                    return false;
                }
            }
            addImport(name, nested);
            return true;
        }

        /**
         * Finds a class declared in another by its simple name: the class named by its binary name,
         * {@code Outer$Nested}, loaded only once the class loader has its class file, whichever the class loader, so
         * no name that isn't a class is ever loaded, and only if it is declared in the other. The other classes the
         * other declares aren't loaded, so one that can't be doesn't matter. It is looked for with the other's own
         * class loader, which defined it and its nested classes: the engine resolved the imported class when it was
         * built, maybe on a thread whose class loader isn't the one the rules load with. A class of the boot loader,
         * such as {@code java.util.Map}, which has none, is looked for with the rule list's.
         *
         * @param outer The class the nested class is declared in
         * @param name  The nested class's simple name
         * @return The nested class, or {@code null} if {@code outer} declares none by that name the class loader can
         *         load, such as one whose class file is missing, or which needs a class that is, or one a native image
         *         built with strict reachability metadata has no metadata for
         */
        // PMD asks for the context class loader instead, which is the one that may not see the imported class.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private Class<?> declaredClass(Class<?> outer, String name) {
            String binaryName = outer.getName() + '$' + name;
            ClassLoader outerLoader = outer.getClassLoader();
            ClassLoader loader = outerLoader != null ? outerLoader : getClassLoader();
            if (!FactNames.mayBeClass(loader, binaryName)) {
                return null;
            }
            try {
                Class<?> nested = Class.forName(binaryName, false, loader);
                return nested.getDeclaringClass() == outer ? nested : null;
            } catch (ClassNotFoundException | LinkageError e) {
                // Such as a class file found for a name that differs in case, or a NoClassDefFoundError for a class
                // it needs that is missing.
                return null;
            } catch (Error e) {
                // A native image's error for a name it has no metadata for, a LinkageError in GraalVM for JDK 25, so
                // caught above, but not in GraalVM for JDK 21.
                if (ExactNameClassLoader.isMissingRegistration(e)) {
                    return null;
                }
                throw e;
            }
        }

        /**
         * Tells whether the class loader has the class file for one of the names MVEL looks a name up by in the
         * imported packages: each package's name, a dot and the name, then that with each dot from the right turned
         * into {@code $}, one at a time, as MVEL's {@code ParseTools.findInnerClass} does. The package's dots are
         * turned too, as MVEL turns them. A name MVEL rejects outright, as it doesn't start as a Java identifier does,
         * such as the {@code [Lpkg.Foo;} of an array type, isn't looked for, and neither is one the rule list's class
         * loader refuses (see {@link ExactNameClassLoader#refuses}). Once it refuses a name with a {@code $}, MVEL
         * gives up on the rest: each has as many characters, and as many parts as it counts them, or fewer by one.
         *
         * @param name The name, as the expression writes it
         * @return {@code true} if MVEL may find a class by the name in an imported package
         */
        private boolean hasClassFile(String name) {
            Set<String> packageImports = getPackageImports();
            if (packageImports == null || !Character.isJavaIdentifierStart(name.charAt(0))) {
                return false;
            }
            for (String pkg : packageImports) {
                String className = pkg + '.' + name;
                if (!ExactNameClassLoader.refuses(className) && FactNames.mayBeClass(getClassLoader(), className)) {
                    return true;
                }
                for (int dot = className.lastIndexOf('.'); dot > 0; dot = className.lastIndexOf('.')) {
                    className = className.substring(0, dot) + '$' + className.substring(dot + 1);
                    if (ExactNameClassLoader.refuses(className)) {
                        break;
                    }
                    if (FactNames.mayBeClass(getClassLoader(), className)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Imports a package an {@code import pkg.*;} in the expression's own text names, as MVEL does, once its name
         * is checked as the engine checks its own imports: MVEL looks each name the expression uses up in it, once for
         * each dot, as a nested class. The rule list's own packages are set, not added, so they aren't checked again.
         * An inline import of a class, or an {@code import_static}, isn't checked: MVEL looks its name up once or
         * twice, then rejects the expression if it isn't a class.
         *
         * @param packageName The package's name, as the expression writes it
         * @throws ImportTooLarge if the name has more than {@value Imports#MAX_IMPORT_LENGTH} characters or more than
         *                        {@value Imports#MAX_IMPORT_PARTS} dot-separated parts, before it is looked up
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
     * Limits how many times MVEL may ask a configuration {@link #newConfiguration()} created for its class loader, in
     * all and from one place, for MVEL's analysis pass over one expression, which is the only one to use the
     * configuration: MVEL asks on every round of a loop in its analysis that never ends, from the same place, and
     * never checks for an interrupt (#840). Once a call passes either limit, the configuration throws an
     * {@link AnalysisLoop}.
     *
     * @param configuration A configuration {@link #newConfiguration()} created
     * @param calls         How many times MVEL may ask
     * @param callsPerSite  How many times MVEL may ask from one place, told by the whole stack (see
     *                      {@link CallSites}), once it has asked {@value CallSites#UNCOUNTED_CALLS} times, or 0 for no
     *                      limit
     */
    @SuppressWarnings("PMD.NullAssignment")
    static void limitClassLoaderCalls(ParserConfiguration configuration, long calls, long callsPerSite) {
        SharedLookupConfiguration shared = (SharedLookupConfiguration) configuration;
        shared.classLoaderCallsLeft = calls;
        shared.classLoaderCallsPerSite = callsPerSite;
        // A count afresh, which sites() creates.
        shared.callSites = null;
    }

    /**
     * Starts a run of the expression compiled with a configuration {@link #newRunConfiguration} created: MVEL may ask
     * it for its class loader as many times as the configuration allows each run, in all and from one place, and
     * nothing has been thrown. A run after one that went round in a loop may not reach the text that loops, such as in
     * a branch it doesn't take, and one that does goes round again, until it has asked as many times. It needs no
     * synchronization: the configuration belongs to one session's compiled copy of the expression, and the engine
     * lets one run at a time use a session, one after another when they are on different threads.
     *
     * @param configuration A configuration {@link #newRunConfiguration} created
     */
    @SuppressWarnings("PMD.NullAssignment")
    static void startRun(ParserConfiguration configuration) {
        SharedLookupConfiguration shared = (SharedLookupConfiguration) configuration;
        shared.classLoaderCallsLeft = shared.classLoaderCallsPerRun;
        shared.sites().restart();
        // What a run before this one threw, if one went round in a loop, isn't this run's.
        shared.runLoop = null;
    }

    /**
     * Returns what a configuration {@link #newRunConfiguration} created threw once the run under way had asked it for
     * its class loader more times than it allows, whether MVEL let it out of the run, or caught it, or threw it wrapped
     * in an exception of its own, as it does for what is thrown while it compiles part of the expression as the
     * expression runs.
     *
     * @param configuration A configuration {@link #newRunConfiguration} created
     * @return What it threw, or {@code null} if the run stayed within the limit
     */
    static @Nullable RunLoop runLoop(ParserConfiguration configuration) {
        return ((SharedLookupConfiguration) configuration).runLoop;
    }

    /**
     * Returns how many times MVEL has asked a configuration for its class loader in its analysis, or in the run under
     * way, since the calls were limited (see {@link #limitClassLoaderCalls} and {@link #startRun}), up to the one that
     * passed the limit for one place, or the last the limit in all allows. Only for tests.
     *
     * @param configuration A configuration whose calls are limited
     * @return The calls counted
     */
    static long classLoaderCalls(ParserConfiguration configuration) {
        return ((SharedLookupConfiguration) configuration).sites().calls();
    }

    /**
     * Returns what a configuration threw once MVEL's analysis had asked it for its class loader as many times as its
     * limit allows (see {@link #limitClassLoaderCalls}).
     *
     * @param configuration A configuration {@link #newConfiguration()} created
     * @return What it threw, or {@code null} if MVEL stayed within the limit
     */
    static @Nullable AnalysisLoop analysisLoop(ParserConfiguration configuration) {
        return ((SharedLookupConfiguration) configuration).analysisLoop;
    }

    /**
     * What a configuration throws once MVEL's analysis pass has asked it for its class loader more times than its
     * limit allows (see {@link #limitClassLoaderCalls}), as it does on every round of a loop that never ends (#840).
     * It is an {@link Error}, as MVEL catches every {@link Exception} where it asks in that loop, and goes round again.
     */
    static final class AnalysisLoop extends Error {

        private static final long serialVersionUID = 1L;

        AnalysisLoop() {
            super("MVEL's analysis asked for the class loader more times than the expression allows");
        }
    }

    /**
     * What a configuration {@link #newRunConfiguration} created throws once MVEL has asked it for its class loader more
     * times than it allows in one run of the expression compiled with it, as it does on every round of a loop that
     * never ends (#857). It is an {@link Error}, as MVEL catches every {@link Exception} where it asks in that loop,
     * and goes round again. The engine reports an {@link Error} from an expression as its rule's failure, even once
     * the run's deadline has passed.
     */
    static final class RunLoop extends Error {

        private static final long serialVersionUID = 1L;

        RunLoop() {
            super("MVEL went round in a loop while running the expression, as it can for a call after a class named "
                    + "with its package and a non-ASCII space, such as java.lang.String.class, then U+00A0 (no-break "
                    + "space), then (2)");
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

        private static final String START = "Can't import '";

        private final String name;
        private final String after;

        /**
         * Creates the exception, whose message is {@code Can't import '}, the name quoted, and what follows it.
         *
         * @param name  The rejected name, as the expression writes it
         * @param after What the message says after the name, from its closing quote on
         */
        ImportTooLarge(String name, String after) {
            super(START + MessageText.quote(name) + after);
            this.name = name;
            this.after = after;
        }

        /**
         * Returns the exception's message with the name escaped within what the rest leaves of a room (see
         * {@link FactNames#quotedWithin(String, String, String, int)}), for a message about the expression: the
         * message itself if it fits.
         *
         * @param room The most characters the message may take
         * @return The message, within the room
         */
        String describedWithin(int room) {
            return FactNames.quotedWithin(START, name, after, room);
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

    // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods, which
    // can fail for good when first called deep in the stack (#996). equals compares the components last first, as
    // ObjectMethods does.
    @Override
    public final boolean equals(@Nullable Object other) {
        return this == other || other instanceof Imports that && Objects.equals(classNameRoots, that.classNameRoots)
                && Objects.equals(inputs, that.inputs)
                && Objects.equals(notClasses, that.notClasses) && Objects.equals(classLoader, that.classLoader)
                && Objects.equals(classes, that.classes) && Objects.equals(packages, that.packages);
    }

    @Override
    public final int hashCode() {
        int hash = Objects.hashCode(packages);
        hash = hash * 31 + Objects.hashCode(classes);
        hash = hash * 31 + Objects.hashCode(classLoader);
        hash = hash * 31 + Objects.hashCode(notClasses);
        hash = hash * 31 + Objects.hashCode(inputs);
        return hash * 31 + Objects.hashCode(classNameRoots);
    }

    @Override
    public final String toString() {
        return "Imports[packages=" + packages + ", classes=" + classes + ", classLoader=" + classLoader
                + ", notClasses=" + notClasses + ", inputs=" + inputs + ", classNameRoots=" + classNameRoots + "]";
    }
}
