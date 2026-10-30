package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ImportLimits;
import io.github.brantunger.unruly.api.language.MessageText;
import org.jspecify.annotations.Nullable;
import org.mvel2.ParserConfiguration;
import org.mvel2.compiler.AbstractParser;
import org.mvel2.util.MethodStub;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The imports a rule list is compiled with: whole packages ({@code java.util}) and single classes
 * ({@code java.time.LocalDate}), the class loader their classes are looked up with, and the names every compilation of
 * the rule list has found not to be classes, and the first parts of the names of the classes its compilations found,
 * and the names of those with no package. A plain class, not a record: nothing compares or prints one, and a
 * record's own methods would link through {@code ObjectMethods}, which can fail for good when first called deep in
 * the stack (#996).
 */
// Each accessor is named after the field it returns, as a record's are.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class Imports {
    private final Set<String> packages;
    private final Set<Class<?>> classes;
    private final ExactNameClassLoader classLoader;
    private final Set<String> notClasses;
    private final Map<String, Class<?>> inputs;
    private final ClassNameRoots classNameRoots;

    /**
     * Creates the imports for one rule list, with nothing known yet about which names aren't classes, and no strong
     * typing. The class loader is wrapped once here, for every expression of the rule list, in an
     * {@link ExactNameClassLoader}.
     *
     * @param packages    Package names, imported with all their classes
     * @param classes     Classes imported one by one
     * @param classLoader The application's class loader that finds the classes in {@code packages}
     */
    Imports(Set<String> packages, Set<Class<?>> classes, ClassLoader classLoader) {
        this(packages, classes, new ExactNameClassLoader(classLoader, new ClassNameRoots()),
                ConcurrentHashMap.newKeySet(), Map.of());
    }

    /**
     * Creates the imports from each part as it is: the rule list's class loader, already wrapped, and the names found
     * not to be classes. The first parts of the names of the classes it finds, and whether a name is looked up as a
     * class file first, are the class loader's own.
     *
     * @param packages    Package names, imported with all their classes
     * @param classes     Classes imported one by one
     * @param classLoader The class loader that finds the classes in {@code packages}, which asks the application's
     *                    class loader for them
     * @param notClasses  Names found not to be a class in any of {@code packages}, shared by the configurations
     *                    created with {@link #newConfiguration()}
     * @param inputs      The type of each name an expression may refer to, for compiling with strong typing, or an
     *                    empty map to compile as MVEL does by default. See {@link DeclaredTypes}.
     */
    Imports(Set<String> packages, Set<Class<?>> classes, ExactNameClassLoader classLoader, Set<String> notClasses,
            Map<String, Class<?>> inputs) {
        this.packages = packages;
        this.classes = classes;
        this.classLoader = classLoader;
        this.notClasses = notClasses;
        this.inputs = inputs;
        this.classNameRoots = classLoader.classNameRoots();
    }

    /** Package names, imported with all their classes. */
    Set<String> packages() {
        return packages;
    }

    /** Classes imported one by one. */
    Set<Class<?>> classes() {
        return classes;
    }

    /** The class loader that finds the classes in {@link #packages()}. */
    ExactNameClassLoader classLoader() {
        return classLoader;
    }

    /** Names found not to be a class in any of {@link #packages()}, shared by the configurations created. */
    Set<String> notClasses() {
        return notClasses;
    }

    /** The type of each name an expression may refer to, or an empty map to compile as MVEL does by default. */
    Map<String, Class<?>> inputs() {
        return inputs;
    }

    /** The first part of the name of each class found while the rule list's expressions compiled, and the rest. */
    ClassNameRoots classNameRoots() {
        return classNameRoots;
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
    SharedLookupConfiguration newConfiguration() {
        return newConfiguration(-1, 0);
    }

    /**
     * Creates a configuration for compiling one expression that then runs, with these imports and class loader
     * registered, which MVEL may ask for its class loader only so many times in each run, and only so many times from
     * one place (see {@link SharedLookupConfiguration#startRun}). MVEL's compiled expression keeps the configuration,
     * and asks it on every round of a loop that never ends, from the same place, and never checks for an interrupt or
     * the run's deadline, as it builds what reads a value the first time the expression runs (#857). Once a run has
     * asked more times, the configuration throws a {@link RunLoop}. Each run has the whole limit: an expression whose
     * run fails while MVEL builds what reads a value asks again in every run, and would run out after enough of them.
     * While the expression compiles, MVEL may ask any number of times.
     *
     * @param callsPerRun  How many times MVEL may ask in each run
     * @param callsPerSite How many times MVEL may ask from one place in each run (see {@link CallSites}), once it has
     *                     asked {@value CallSites#UNCOUNTED_CALLS} times, or 0 for no limit
     * @return A new configuration, used by one compilation and the runs of what it compiled only
     */
    SharedLookupConfiguration newRunConfiguration(long callsPerRun, long callsPerSite) {
        return newConfiguration(callsPerRun, callsPerSite);
    }

    private SharedLookupConfiguration newConfiguration(long callsPerRun, long callsPerSite) {
        // A name is looked up as a class file first exactly when the class loader refuses one it has none for.
        SharedLookupConfiguration configuration = new SharedLookupConfiguration(notClasses,
                classLoader.classFileFirst(), callsPerRun, callsPerSite);
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
     * Rejects an import too long, or with too many parts, to look up, as the engine rejects one it is built with: the
     * same limits, and the same message, the quoted import and the reason {@link ImportLimits#importSizeProblem}
     * gives. {@link ExactNameClassLoader} bounds the names it looks up from the same limits.
     *
     * @param name The package's name
     * @throws ImportRejected if {@code name} has more than {@value ImportLimits#MAX_IMPORT_LENGTH} characters or more
     *                        than {@value ImportLimits#MAX_IMPORT_PARTS} dot-separated parts
     */
    static void checkSize(String name) {
        String problem = ImportLimits.importSizeProblem(name);
        if (problem != null) {
            throw new ImportRejected(name, "': " + problem);
        }
    }

    /**
     * Returns what to throw for a {@link NullPointerException} from MVEL's {@code addPackageImport}: for a class with
     * a public static field whose value is {@code null}, an {@link ImportRejected} that names the import and the first
     * such field, such as {@code Can't import 'acme.Bar.*': its public static field 'X' is null}; else the exception
     * itself. MVEL imports each public static field declared in a class that isn't an enum, none it inherits, by
     * putting its value in a map that can't hold {@code null}, and reports the exception that throws as an expression
     * that is "not a statement, or badly formed structure". The class is looked up as MVEL looks it up, through the
     * class loader of MVEL's own classes (see {@link ExceptionReads#MVEL_LOADER}), and its fields are read as MVEL
     * reads them, in the order it reads them.
     *
     * <p>
     * The field's name, which can hold any character a class file allows, is escaped and takes at most
     * {@value MessageText#MAX_NAME_LENGTH} characters, the note of what was left out included, so the import's own name
     * keeps most of the room the message has.
     * </p>
     *
     * @param packageName The package's name, as the expression writes it
     * @param e           What MVEL threw
     * @return The exception to throw
     */
    static RuntimeException nullStaticField(String packageName, NullPointerException e) {
        Class<?> imported;
        try {
            imported = Class.forName(packageName, false, ExceptionReads.MVEL_LOADER);
        } catch (ClassNotFoundException notClass) {
            return e;
        }
        // MVEL imports an enum's constants, never null, rather than its fields.
        if (imported.isEnum()) {
            return e;
        }
        int publicStatic = Modifier.PUBLIC | Modifier.STATIC;
        for (Field field : imported.getDeclaredFields()) {
            if ((field.getModifiers() & publicStatic) == publicStatic && isNull(field)) {
                return new ImportRejected(packageName, ".*': its public static field '"
                        + MessageText.quoteWithin(field.getName(), MessageText.MAX_NAME_LENGTH) + "' is null");
            }
        }
        return e;
    }

    /**
     * Tells whether a static field's value is {@code null}, for {@link #nullStaticField}.
     *
     * @param field The field
     * @return {@code true} if its value is {@code null}; {@code false} if it isn't, or this module can't read it, in
     *         which case it isn't one MVEL put a {@code null} for: MVEL would have failed on reading it, with an
     *         exception of its own
     */
    private static boolean isNull(Field field) {
        try {
            return field.get(null) == null;
        } catch (IllegalAccessException unreadable) {
            return false;
        }
    }

    /**
     * A configuration that shares the answer "this name isn't a class" with every other configuration created from
     * the same imports. MVEL keeps that answer only on the configuration that looked the name up, by trying to load it
     * from every imported package, and each condition and action is compiled with a configuration of its own, so one
     * rule can't change how another compiles. Without sharing, a name was looked up again for every expression that
     * used it, and again for the compiled copy of every session that runs it.
     */
    // Package-private, not private, so MvelExpressionLanguage.prepare() can name it to load it (#1210).
    static final class SharedLookupConfiguration extends ParserConfiguration {

        private static final long serialVersionUID = 1L;

        private final transient Set<String> notClasses;
        // The names found not to be classes once the expression imported a package of its own, which only it has.
        // Bounded by the names the one expression uses.
        private final transient Set<String> ownNotClasses = new HashSet<>();
        // The names the expression's own inline imports brought in: a class imported by name, a static method imported
        // with import_static, a class MVEL found in a package the expression imports, or one nested in such a class,
        // and a static field or enum constant MVEL looked up (see staticMemberNames). Bounded by the imports and names
        // the one expression has.
        private final transient Set<String> inlineNames = new HashSet<>();
        // The names of each public static field declared in a class the expression imports with `import a.B.*;`, or
        // of each constant of an enum it imports so, which MVEL puts in its imports without calling addImport. Bounded
        // by those classes' fields.
        private final transient Set<String> staticMemberNames = new HashSet<>();
        // Whether a name is looked up as a class file before MVEL loads it: only for a class loader that serves every
        // class file (see ExactNameClassLoader.ClassLookup.servesEveryClassFile).
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
        // limit, which the guard counts as Long.MAX_VALUE: a configuration read back from a form written before there
        // was one has 0.
        private long classLoaderCallsPerSite;
        // Where MVEL asked from, and what was thrown, in its analysis or the run under way, or null until it is first
        // needed. Transient: what a pass or run has counted means nothing to a copy read back.
        private transient @Nullable LoopGuard loopGuard;
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
         * Returns what tells MVEL went round in a loop, created the first time it is needed, such as in a copy read
         * back, with this configuration's limits.
         *
         * @return The guard for the analysis or the run under way
         */
        private LoopGuard guard() {
            if (loopGuard == null) {
                loopGuard = new LoopGuard(classLoaderCallsPerRun, classLoaderCallsPerSite);
            }
            return loopGuard;
        }

        /**
         * Limits how many times MVEL may ask this configuration, one {@link #newConfiguration()} created, for its
         * class loader, in all and from one place, for MVEL's analysis pass over one expression, which is the only one
         * to use the configuration: MVEL asks on every round of a loop in its analysis that never ends, from the same
         * place, and never checks for an interrupt (#840). Once a call passes either limit, the configuration throws
         * an {@link AnalysisLoop}.
         *
         * @param calls        How many times MVEL may ask
         * @param callsPerSite How many times MVEL may ask from one place, told by the whole stack (see
         *                     {@link CallSites}), once it has asked {@value CallSites#UNCOUNTED_CALLS} times, or 0 for
         *                     no limit
         */
        void limitClassLoaderCalls(long calls, long callsPerSite) {
            classLoaderCallsLeft = calls;
            classLoaderCallsPerSite = callsPerSite;
            guard().limit(callsPerSite);
        }

        /**
         * Starts a run of the expression compiled with this configuration, one {@link #newRunConfiguration} created:
         * MVEL may ask it for its class loader as many times as the configuration allows each run, in all and from one
         * place, and nothing has been thrown. A run after one that went round in a loop may not reach the text that
         * loops, such as in a branch it doesn't take, and one that does goes round again, until it has asked as many
         * times. It needs no synchronization: the configuration belongs to one session's compiled copy of the
         * expression, and the engine lets one run at a time use a session, one after another when they are on
         * different threads.
         */
        void startRun() {
            classLoaderCallsLeft = classLoaderCallsPerRun;
            guard().startRun();
        }

        /**
         * Returns what this configuration, one {@link #newRunConfiguration} created, threw once the run under way had
         * asked it for its class loader more times than it allows, whether MVEL let it out of the run, or caught it,
         * or threw it wrapped in an exception of its own, as it does for what is thrown while it compiles part of the
         * expression as the expression runs.
         *
         * @return What it threw, or {@code null} if the run stayed within the limit
         */
        @Nullable RunLoop runLoop() {
            return guard().runLoop();
        }

        /**
         * Returns what this configuration threw once MVEL's analysis had asked it for its class loader as many times
         * as its limit allows (see {@link #limitClassLoaderCalls}).
         *
         * @return What it threw, or {@code null} if MVEL stayed within the limit
         */
        @Nullable AnalysisLoop analysisLoop() {
            return guard().analysisLoop();
        }

        /**
         * Returns how many times MVEL has asked this configuration for its class loader in its analysis, or in the
         * run under way, since the calls were limited (see {@link #limitClassLoaderCalls} and {@link #startRun}), up
         * to the one that passed the limit for one place, or the last the limit in all allows. Only for tests.
         *
         * @return The calls counted
         */
        long classLoaderCalls() {
            return guard().calls();
        }

        /**
         * Returns the names the expression compiled with this configuration imported in its own text, as MVEL brought
         * them in: the simple name of a class it imports, the name of a static method it imports with
         * {@code import_static}, the name of each class MVEL found, while it compiled, in a package it imports, or
         * nested in such a class, and the name of each public static field declared in a class, or each constant of
         * an enum, it imports with {@code import a.B.*;} that MVEL looked up while it compiled. Such a field or
         * constant counts too where MVEL took its name as one of the parser context's inputs or variables: a name the
         * expression uses before the import isn't among the imports yet as MVEL compiles that use, so MVEL takes it
         * as an input or a variable, and the expression reads the field by it as it runs, as it does by a name used
         * after the import. That holds at the expression's top level, in an if, foreach, while or with block and for
         * a read in a for body, but not in a def or closure body, nor for an assignment in a for body, which MVEL
         * compiles in a context of its own. A class in one of its packages that MVEL never looked up isn't among them.
         *
         * @param inputs    The parser context's inputs, which MVEL creates before it reads the expression, as it
         *                  verifies it
         * @param variables The parser context's variables, which it creates so too
         * @return The names, in a set of their own, empty if the expression imports nothing of its own
         */
        Set<String> inlineImportedNames(Map<String, ?> inputs, Map<String, ?> variables) {
            Set<String> names = new HashSet<>(inlineNames);
            if (!staticMemberNames.isEmpty()) {
                addMembers(inputs, names);
                addMembers(variables, names);
            }
            return names;
        }

        // Adds to names each name declared that is a static field's or an enum constant's the expression imports.
        private void addMembers(Map<String, ?> declared, Set<String> names) {
            for (String name : declared.keySet()) {
                if (staticMemberNames.contains(name)) {
                    names.add(name);
                }
            }
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
                throw guard().looped();
            }
            if (classLoaderCallsLeft > 0) {
                classLoaderCallsLeft--;
                if (guard().counted()) {
                    classLoaderCallsLeft = 0;
                    throw guard().looped();
                }
            }
            return super.getClassLoader();
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
         * loader serves the class file of every class it finds itself (see
         * {@link ExactNameClassLoader.ClassLookup#servesEveryClassFile}), as the JDK's own do, and a plain
         * {@code URLClassLoader} whose parents are plain ones up to one of the JDK's or the boot loader, MVEL is only
         * asked once it has the class file for one of those names (see
         * {@link ExactNameClassLoader.ClassLookup#anyClassFile}), and a name that has none isn't a class. A name the
         * rule list's class loader refuses to look up (see {@link ExactNameClassLoader.ClassLookup#refuses}) isn't
         * looked for at all then, as it can't be a class either, nor is one MVEL rejects outright, which doesn't start
         * as a Java identifier does. Any other class loader, such as a subclass of {@code URLClassLoader}, a
         * framework's or an application server's, may define a class it serves no class file for, so it is asked as
         * MVEL asks it, except for a name in a {@code java.} package the JDK serves no class file for, which the rule
         * list's class loader refuses (see {@link ExactNameClassLoader}). Each name it is asked for may leave a lock
         * object in it, and in the JDK's application class loader, for the life of the JVM, if it asks that one first.
         * </p>
         *
         * <p>
         * So a class defined at run time in one of the loaders that serve every class file, such as with
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
            // A name imported inline by this expression is in its own imports, whatever other expressions found.
            if (imports.containsKey(name)) {
                // A static field or enum constant counts as the expression's only once MVEL looks it up.
                if (staticMemberNames.contains(name)) {
                    inlineNames.add(name);
                }
                return true;
            }
            if (importNested(name)) {
                return true;
            }
            Set<String> misses = packageCount() == sharedPackageCount ? notClasses : ownNotClasses;
            if (misses.contains(name)) {
                return false;
            }
            boolean found = (!classFileFirst || AbstractParser.CLASS_LITERALS.containsKey(name)
                    || ExactNameClassLoader.ClassLookup.anyClassFile(getClassLoader(), getPackageImports(), name))
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
         *         built with strict reachability metadata has no metadata for, or one the class loader throws any
         *         other {@link Error} for that isn't a {@link VirtualMachineError}, which is thrown (see
         *         {@link ExactNameClassLoader.ClassLookup#isNoClass}); an error reading the found class's declaring
         *         class, such as an {@link IncompatibleClassChangeError}, is thrown
         */
        // PMD asks for the context class loader instead, which is the one that may not see the imported class.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private @Nullable Class<?> declaredClass(Class<?> outer, String name) {
            String binaryName = outer.getName() + '$' + name;
            ClassLoader outerLoader = outer.getClassLoader();
            ClassLoader loader = outerLoader != null ? outerLoader : getClassLoader();
            if (!ExactNameClassLoader.ClassLookup.mayBeClass(loader, binaryName)) {
                return null;
            }
            // Null for a class file found for a name that differs in case, a NoClassDefFoundError for a class it
            // needs that is missing, a native image's error for a name it has no metadata for, a LinkageError in
            // GraalVM for JDK 25 but not in GraalVM for JDK 21, or any other error the load throws bar the JVM's own.
            Class<?> nested = ExactNameClassLoader.ClassLookup.forNameOrNull(binaryName, loader);
            return nested != null && nested.getDeclaringClass() == outer ? nested : null;
        }

        /**
         * Imports a package an {@code import pkg.*;} in the expression's own text names, as MVEL does, once its name
         * is checked as the engine checks its own imports: MVEL looks each name the expression uses up in it, once for
         * each dot, as a nested class. The rule list's own packages are set, not added, so they aren't checked again.
         * An inline import of a class, or an {@code import_static}, isn't checked: MVEL looks its name up once or
         * twice, then rejects the expression if it isn't a class.
         *
         * <p>
         * When the name is a class, MVEL also imports each public static field declared in it, none it inherits, or
         * each constant of an enum, by its simple name, straight into its imports, without calling
         * {@link #addImport(String, Class)}. The names it adds are remembered, and each counts as one the expression's
         * own imports brought in (see {@link #inlineImportedNames}) once MVEL looks it up while it compiles the
         * expression, as a class MVEL finds in a package does, or once MVEL takes it as an input or a variable.
         * </p>
         *
         * <p>
         * MVEL can't import a public static field whose value is {@code null}, and fails with a
         * {@link NullPointerException} it reports as a malformed expression, so the import is rejected with a message
         * that names the field instead (see {@link Imports#nullStaticField}).
         * </p>
         *
         * @param packageName The package's name, as the expression writes it
         * @throws ImportRejected if the name has more than {@value ImportLimits#MAX_IMPORT_LENGTH} characters or more
         *                        than {@value ImportLimits#MAX_IMPORT_PARTS} dot-separated parts, before it is looked
         *                        up, or names a class with a public static field whose value is {@code null}
         */
        @Override
        public void addPackageImport(String packageName) {
            checkSize(packageName);
            Set<String> before = new HashSet<>(imports.keySet());
            try {
                super.addPackageImport(packageName);
            } catch (NullPointerException e) {
                // The fields MVEL imported before the null one, and the package, stay: the configuration goes with
                // the expression, which fails to compile.
                throw nullStaticField(packageName, e);
            }
            for (String name : imports.keySet()) {
                if (!before.contains(name)) {
                    staticMemberNames.add(name);
                }
            }
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

        /**
         * Imports a class as {@code name}, as MVEL does, and remembers the name as one the expression's own imports
         * brought in (see {@link #inlineImportedNames}) when it isn't one of the rule list's: an {@code import a.B;}
         * in the expression's text, a class MVEL found in a package it imports, or one nested in a class MVEL found in
         * such a package. A class MVEL finds in one of the rule list's packages, while the expression imports none of
         * its own, is the rule list's, which {@link MvelFactNames} already rejects. With a package of its own imported
         * too, a class found in one of the rule list's is remembered as well, which changes nothing:
         * {@link MvelFactNames} rejects it either way.
         *
         * @param name The name the class is imported as
         * @param cls  The class
         */
        // MVEL's method takes a raw Class, which an override must too.
        @SuppressWarnings("rawtypes")
        @Override
        public void addImport(String name, Class cls) {
            super.addImport(name, cls);
            if (sharedPackageCount >= 0 && !(lookingUp && packageCount() == sharedPackageCount)) {
                inlineNames.add(name);
            }
        }

        /**
         * Imports a static method as {@code name}, as MVEL does for an {@code import_static} in the expression's text,
         * and remembers the name as one the expression's own imports brought in (see {@link #inlineImportedNames}).
         *
         * @param name   The name the method is imported as
         * @param method The method
         */
        @Override
        public void addImport(String name, MethodStub method) {
            super.addImport(name, method);
            inlineNames.add(name);
        }

        private int packageCount() {
            Set<String> packageImports = getPackageImports();
            return packageImports != null ? packageImports.size() : 0;
        }
    }

    /**
     * What tells that MVEL went round in a loop as it asks a configuration for its class loader: where it asked from in
     * MVEL's analysis or in the run under way (see {@link CallSites}), by the whole stack for a configuration that only
     * compiles and by the stack's top and depth for one whose runs are limited, and what the configuration threw once a
     * call passed a limit. The limits in all are the configuration's own, written out with it: this is transient, as
     * what a pass or run has counted means nothing to a copy read back, which creates its own. It links no lambda or
     * method reference, which the JVM would link the first time it runs, maybe deep in a stack (#1099).
     */
    // Each accessor is named after the field it returns, as a record's are.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    static final class LoopGuard {

        // How many times MVEL may ask in each run, or -1 for a configuration that only compiles.
        private final long callsPerRun;
        // How many times MVEL may ask from one place, or 0 for no limit.
        private long callsPerSite;
        // Where MVEL asked from while the calls are limited, or null until it is first needed.
        private @Nullable CallSites callSites;
        // What MVEL's analysis threw once a call passed a limit, or null.
        private @Nullable AnalysisLoop analysisLoop;
        // What the run under way threw once a call passed a limit, or null (see startRun).
        private @Nullable RunLoop runLoop;

        /**
         * Creates the guard for a configuration's analysis pass, or for each of its runs.
         *
         * @param callsPerRun  How many times MVEL may ask in each run, or -1 for a configuration that only compiles
         * @param callsPerSite How many times MVEL may ask from one place, or 0 for no limit
         */
        LoopGuard(long callsPerRun, long callsPerSite) {
            this.callsPerRun = callsPerRun;
            this.callsPerSite = callsPerSite;
        }

        // The count of where MVEL asked from, created the first time it is needed: by the whole stack for a
        // configuration that only compiles, and by the stack's top and depth for one whose runs are limited.
        private CallSites sites() {
            if (callSites == null) {
                callSites = new CallSites(callsPerRun < 0, callsPerSite > 0 ? callsPerSite : Long.MAX_VALUE);
            }
            return callSites;
        }

        /**
         * Limits how many times MVEL's analysis may ask from one place, and counts afresh. What the analysis threw, if
         * anything, is kept.
         *
         * @param callsPerSite How many times MVEL may ask from one place, or 0 for no limit
         */
        @SuppressWarnings("PMD.NullAssignment")
        void limit(long callsPerSite) {
            this.callsPerSite = callsPerSite;
            // A count afresh, which sites() creates.
            callSites = null;
        }

        /**
         * Starts a run: the count of where MVEL asked from starts again, and nothing has been thrown.
         */
        @SuppressWarnings("PMD.NullAssignment")
        void startRun() {
            sites().restart();
            // What a run before this one threw, if one went round in a loop, isn't this run's.
            runLoop = null;
        }

        /**
         * Counts a call for the class loader, at the site the stack tells, as {@link CallSites#counted()} does, in its
         * place: a run's site is told by the frames at the stack's top, which are as many as they would be with it.
         *
         * @return {@code true} if the call's site has now made more calls than it may, counted at every call, or more
         *         sites were walked than the count may keep
         */
        boolean counted() {
            return sites().countedAt(callsPerRun < 0 ? CallSites.WHOLE_STACK : CallSites.TOP_AND_DEPTH);
        }

        /**
         * Returns what MVEL went round in a loop in, the same each time in a pass or a run: its analysis, for a
         * configuration that only compiles, or the run under way.
         *
         * @return The {@link AnalysisLoop} or {@link RunLoop} to throw
         */
        Error looped() {
            if (callsPerRun < 0) {
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

        /** What MVEL's analysis threw once a call passed a limit, or {@code null}. */
        @Nullable AnalysisLoop analysisLoop() {
            return analysisLoop;
        }

        /** What the run under way threw once a call passed a limit, or {@code null}. */
        @Nullable RunLoop runLoop() {
            return runLoop;
        }

        /** The calls counted in the analysis or the run under way (see {@link CallSites#calls()}). Only for tests. */
        long calls() {
            return sites().calls();
        }
    }

    /**
     * What a configuration throws once MVEL's analysis pass has asked it for its class loader more times than its
     * limit allows (see {@link SharedLookupConfiguration#limitClassLoaderCalls}), as it does on every round of a loop
     * that never ends (#840). It is an {@link Error}, as MVEL catches every {@link Exception} where it asks in that
     * loop, and goes round again.
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
     * many parts, to look up, or a class with a public static field whose value is {@code null}, which MVEL can't
     * import, which the compiler reports as a compile error at the name. MVEL lets it through as it is, as it does any
     * {@link RuntimeException} that isn't its own.
     */
    static final class ImportRejected extends IllegalArgumentException {

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
        ImportRejected(String name, String after) {
            super(START + MessageText.quote(name) + after);
            this.name = name;
            this.after = after;
        }

        /**
         * Returns the exception's message with the name escaped within what the rest leaves of a room (see
         * {@link MessageQuotes#quotedWithin(String, String, String, int)}), for a message about the expression: the
         * message itself if it fits.
         *
         * @param room The most characters the message may take
         * @return The message, within the room
         */
        String describedWithin(int room) {
            return MessageQuotes.quotedWithin(START, name, after, room);
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
