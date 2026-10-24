package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ImportLimits;
import io.github.brantunger.unruly.api.language.MessageText;
import io.github.brantunger.unruly.api.language.Throwables;

import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The class loader MVEL compiles rules with. It asks the application's class loader for every class, unless the name
 * can't be one, but reports a class file found for the name only in a different case as a missing class, and links the
 * code MVEL's JIT generates against the MVEL running the rules.
 *
 * <p>
 * While compiling {@code applicant.creditScore}, MVEL checks whether {@code applicant} is a class. In a class directory
 * on a case-insensitive file system (the default on Windows and macOS), the lookup for {@code applicant.class} finds
 * {@code Applicant.class}, and the JVM throws {@code NoClassDefFoundError: applicant (wrong name: Applicant)}. MVEL
 * doesn't catch it, so the rule would fail to compile. Reported as a {@link ClassNotFoundException}, it tells MVEL
 * that {@code applicant} isn't a class, so it reads it as the fact. Any other linkage error is passed on unchanged,
 * including a "wrong name" error for a class the class looked up depends on, such as its superclass, which the JVM
 * names in its message instead: that class exists but can't be loaded.
 * </p>
 *
 * <p>
 * In a native image built with strict reachability metadata, the application's class loader throws GraalVM's
 * {@code MissingReflectionRegistrationError}, an {@link Error}, for every name the image has no metadata for, existing
 * or not, where the JVM and any other build throw {@link ClassNotFoundException}. MVEL looks up many names that aren't
 * classes, as above, and in most of those lookups reads only a {@link ClassNotFoundException} as "not a class", so a
 * rule as plain as {@code applicant.creditScore} failed to compile. That error is reported as a
 * {@link ClassNotFoundException} too, with the image's error as its cause, so MVEL reads each name as on the JVM.
 * </p>
 *
 * <p>
 * It's registered as parallel-capable. Otherwise the JVM would hold this loader's lock through every class lookup made
 * with it, so the threads compiling the rule list's expressions would look up even different names one at a time,
 * each waiting virtual thread pinned to its carrier. The application's class loader still decides what waits: the
 * JDK's own loaders serialize lookups of the same name, a loader that isn't parallel-capable serializes every lookup,
 * and a virtual thread stays on its carrier while the JVM loads a class.
 * </p>
 *
 * <p>
 * Once MVEL's JIT compiles an accessor, it defines the accessor's class in a new class loader whose parent is this one,
 * and the JVM links that class against MVEL's own classes, such as {@code org.mvel2.compiler.Accessor}. The
 * application's class loader may not see MVEL, and may see a copy of its own that MVEL can't cast the accessor to, so a
 * class in MVEL's package is taken from the class loader of the MVEL running the rules first, and only asked of the
 * application's class loader if that one hasn't got it. Only a lookup that the JIT's class loader passes on to its
 * parent comes through {@link #loadClass(String, boolean)}. A lookup made with this loader itself, as MVEL's while it
 * compiles a rule or {@link Class#forName(String, boolean, ClassLoader)} with this loader, calls
 * {@link #loadClass(String)}, which asks the application's class loader for every class a name may be.
 * </p>
 *
 * <p>
 * A name looked up with this loader itself is refused, before the application's class loader is asked, when it has
 * more than {@value ClassLookup#MAX_NAME_LENGTH} characters or more than {@value ClassLookup#MAX_NAME_PARTS} parts:
 * its dot-separated parts, and one more for a name with a {@code $}, which names a nested class. MVEL reads a dotted
 * chain in a rule, such as {@code a.b.c.d == 1}, by looking the chain up as a class, then again with its dots turned
 * into {@code $} one at a time from the right, as a nested class, and again for each shorter chain, so a chain of n
 * parts cost lookups that grew with n squared, each of which a parallel-capable class loader keeps a lock object for.
 * Only the dots make that loop longer, so only they are counted: MVEL also looks a property up as a class nested in
 * the type it is read from, such as {@code java.lang.Object$x$y} for {@code m.x$y}, and a property may have any
 * number of {@code $}.
 * </p>
 *
 * <p>
 * A refused name is reported as a {@link ClassNotFoundException}, so MVEL reads it as it reads any name that isn't a
 * class: a chain as properties, {@code (a.b...c)}, which MVEL first tries as a cast, as the expression in
 * parentheses, and {@code new a.b...c$D()} as a class it can't find when it runs. The one exception is a name with
 * too many parts and a {@code $} that MVEL's lookup of a nested class tries, which is reported as a
 * {@link NameTooLarge}. That lookup tries the next name with a {@code $} after a {@link ClassNotFoundException}, but
 * gives up on the chain after any other exception, which every caller of it catches and reads as "not a class", so a
 * chain costs lookups that grow with n. The first name with a {@code $} it tries is refused, as turning the last dot
 * into a {@code $} leaves the count as it is. A name too long alone doesn't make that loop any longer, so it is always
 * a {@link ClassNotFoundException}. Every rule that loaded without the bound and names no class of more than
 * {@value ClassLookup#MAX_DOLLAR_NAME_PARTS} parts still loads, and runs the same. A member read through a class of
 * more than {@value ClassLookup#MAX_DOLLAR_NAME_PARTS} parts, such as {@code pkg.Outer.Inner.FIELD} with
 * {@value ClassLookup#MAX_NAME_PARTS} parts before the field, is read as properties, as MVEL gives up on the chain
 * before it looks up the class.
 * </p>
 *
 * <p>
 * A name looked up with this loader itself is also refused, as a {@link ClassNotFoundException}, when no class can have
 * it (#752). MVEL looks up much that isn't a class: every prefix of a property chain such as {@code f.p7.q}, with its
 * dots turned into {@code $}, while it compiles, and, after an {@code import pkg.*;} in the rule's text, whole
 * statements such as {@code java.util.output.put('k', x)} when it first runs. A parallel-capable class loader, as the
 * JDK's are, keeps a lock object for every name it is asked for, for as long as it lives, so a rule list whose rules
 * differ in their names or literals left more on every load or first run. A name with a character that is neither a
 * {@code .} nor one a Java identifier may have is refused with any application class loader. When the application's
 * serves the class file of every class it finds itself (see {@link ClassLookup#servesEveryClassFile}), as the JDK's
 * own do, and so does a plain {@code URLClassLoader}, such as a plugin's or a tenant's, whose parents are plain ones up
 * to one of the JDK's or the boot loader (#1130), a name it serves no class file for is refused too, as the class file
 * lookup takes no lock (see {@link ClassLookup#mayBeClass}). Neither the class file of a class it loaded nor the lack
 * of one is looked up again, for up to {@value ClassLookup#MAX_REMEMBERED_NAMES} names of each. Such a loader serves
 * no class file for a class defined in it at run time, such as with {@code MethodHandles.Lookup.defineClass}: a rule
 * can't import that class inline, nor name it fully qualified where MVEL looks it up while the rule compiles, as with
 * strong typing. A class import the engine was built with still finds it, as it is loaded as a {@code Class} and
 * never looked up by name here. Any other application class loader, such as a subclass of {@code URLClassLoader}, may
 * define a class it serves no class file for, so it is asked for every well-formed name, except one in a
 * {@code java.} package that the platform class loader serves no class file for, as the JVM lets no other class loader
 * define a class there. Each name it is asked for may leave a lock object in it, and in the JDK's application class
 * loader, for the life of the JVM, if it asks that one first. Lookups the JIT's class loader passes on aren't checked.
 * After this loader refuses a name, MVEL's {@code ParseTools.createClass} asks the thread's context class loader for
 * it, unless that is this loader, as it is while MVEL compiles a rule (see {@link MvelExpression}), so a property read
 * through a value MVEL types as {@code Object}, such as {@code java.lang.Object$p7} for {@code f.p7}, leaves no lock
 * object (#807). A class a rule creates with {@code new} is still asked of the running thread's context class loader
 * when the rule runs.
 * </p>
 *
 * <p>
 * The name of each class the application's class loader loads through this one, by a name looked up with this loader
 * itself, is given to the rule list's {@link ClassNameRoots}, which records its first part while an expression
 * compiles, or its name if it has no package: a fact can't then have that name (see {@link MvelFactNames}).
 * </p>
 */
final class ExactNameClassLoader extends ClassLoader {

    // MVEL's lookup of a nested class, which turns the dots of a name into $ one at a time, and the class it is in.
    private static final String NESTED_LOOKUP_CLASS = ExceptionReads.MVEL_PACKAGE + "util.ParseTools";
    private static final String NESTED_LOOKUP_METHOD = "findInnerClass";
    // What reads the stack for that lookup: a class of its own, not a lambda, which the JVM links the first time it
    // runs, and linking one makes a class. The first refused name with a '$' may be looked up deep in a stack, where
    // that could overflow (#1099). Created here, so preparing MVEL, which initializes this class, loads it.
    private static final Function<Stream<StackWalker.StackFrame>, Boolean> NESTED_LOOKUP = new NestedLookup();

    // The start of every name in a package only the JDK may define classes in: the JVM lets no class loader but the
    // platform class loader define one there.
    private static final String JDK_ONLY_PACKAGES = "java.";

    // The class loader that serves the class file of every class in those packages, through the boot loader. Taken
    // once, while the class initializes, rather than each time a name is looked up, maybe deep in a stack.
    private static final ClassLoader JDK_CLASS_FILES = ClassLoader.getPlatformClassLoader();

    static {
        registerAsParallelCapable();
    }

    // Whether the application's class loader serves the class file of every class it finds itself, as the JDK's
    // own and a plain URLClassLoader do (see ClassLookup.servesEveryClassFile), so a name without one is refused before
    // it is asked. Its accessor is named after it, as a record's is.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    private final boolean classFileFirst;

    // The names of classes the application's class loader has loaded through this one, when it serves every class
    // file: a rule list compiles the same few classes' names again and again, and each class file lookup walks the
    // class path.
    private final Set<String> classes = ConcurrentHashMap.newKeySet();

    // The names the application's class loader, when it serves every class file, or else the platform class loader,
    // for a name in a java. package, serves no class file for. A class defined at run time has none, so remembering
    // one changes nothing for it. A class file that appears later, such as in a class directory or through
    // Instrumentation.appendToSystemClassLoaderSearch, isn't found through this loader: the engine makes one for each
    // load() or validate(), so a name stays remembered for that rule list's compiling and runs only, and the next
    // load() starts with none.
    private final Set<String> noClassFiles = ConcurrentHashMap.newKeySet();

    private final int mostClasses;

    // Told the name of each class the application's class loader loads through this one, and records the first part
    // of it, or the name of one with no package, while an expression compiles.
    private final ClassNameRoots roots;

    /**
     * Creates a class loader that asks {@code parent} for every class a name may be, apart from those MVEL's own
     * class loader has when the JVM links an accessor MVEL's JIT compiled.
     *
     * @param parent The application's class loader
     */
    ExactNameClassLoader(ClassLoader parent) {
        this(parent, new ClassNameRoots());
    }

    /**
     * Creates a class loader that asks {@code parent} for every class a name may be, and tells {@code roots} the name
     * of each class it loads.
     *
     * @param parent The application's class loader
     * @param roots  Records the first part of the name of each class loaded while an expression compiles, or the
     *               name of one with no package
     */
    ExactNameClassLoader(ClassLoader parent, ClassNameRoots roots) {
        this(parent, ClassLookup.MAX_REMEMBERED_NAMES, ClassLookup.servesEveryClassFile(parent), roots);
    }

    /**
     * Creates a class loader that asks {@code parent} for every class a name may be, and remembers at most
     * {@code mostClasses} of the classes it loads.
     *
     * @param parent      The application's class loader
     * @param mostClasses How many names of classes loaded to remember
     */
    ExactNameClassLoader(ClassLoader parent, int mostClasses) {
        this(parent, mostClasses, ClassLookup.servesEveryClassFile(parent));
    }

    /**
     * Creates a class loader that asks {@code parent} for every class a name may be, first for its class file if
     * {@code classFileFirst}, and remembers at most {@code mostClasses} names of each kind.
     *
     * @param parent         The application's class loader
     * @param mostClasses    How many names of classes loaded, and of names with no class file, to remember
     * @param classFileFirst Whether to refuse a name {@code parent} serves no class file for, as for one that serves
     *                       the class file of every class it finds itself, or else only a name in a {@code java.}
     *                       package the platform class loader serves none for
     */
    ExactNameClassLoader(ClassLoader parent, int mostClasses, boolean classFileFirst) {
        this(parent, mostClasses, classFileFirst, new ClassNameRoots());
    }

    private ExactNameClassLoader(ClassLoader parent, int mostClasses, boolean classFileFirst, ClassNameRoots roots) {
        super(parent);
        this.classFileFirst = classFileFirst;
        this.mostClasses = mostClasses;
        this.roots = roots;
    }

    /**
     * Returns what this class loader tells the name of each class it loads, which records the first part of each such
     * name, or the name of one with no package, while an expression compiles.
     *
     * @return The class name roots
     */
    ClassNameRoots classNameRoots() {
        return roots;
    }

    /**
     * Tells whether this class loader refuses a name the application's class loader serves no class file for, as for
     * one that serves the class file of every class it finds itself (see {@link ClassLookup#servesEveryClassFile}).
     *
     * @return {@code true} if a name is looked up as a class file before the application's class loader is asked
     */
    boolean classFileFirst() {
        return classFileFirst;
    }

    /**
     * Looks a class up in the application's class loader, unless its name is too long, or has too many parts, to be
     * a class the engine can import, or can't be a class's name.
     *
     * @param name The class's binary name
     * @return The class
     * @throws ClassNotFoundException if the application's class loader has no class by that name, or only a class
     *                                file found for that name in a different case, or, in a native image built with
     *                                strict reachability metadata, no metadata for it, or, before the application's
     *                                class loader is asked, if {@code name} has more than
     *                                {@value ClassLookup#MAX_NAME_LENGTH} characters, or more than
     *                                {@value ClassLookup#MAX_NAME_PARTS} dot-separated parts and no {@code $}, or a
     *                                character that is neither a {@code .} nor one a Java identifier may have, or if
     *                                the application's class loader serves the class file of every class it loads and
     *                                none for it, or, with any other, if it is in a {@code java.} package and the
     *                                platform class loader serves no class file for it
     * @throws NameTooLarge           if MVEL's lookup of a nested class asks for {@code name}, which has a {@code $}
     *                                and more than {@value ClassLookup#MAX_NAME_PARTS} parts, counting one for the
     *                                {@code $}, before the application's class loader is asked
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        checkSize(name);
        if (!ClassLookup.isBinaryName(name)
                || classFileFirst && !classes.contains(name) && !hasClassFile(getParent(), name)
                || !classFileFirst && name.startsWith(JDK_ONLY_PACKAGES) && !hasClassFile(JDK_CLASS_FILES, name)) {
            throw new ClassNotFoundException(name);
        }
        try {
            Class<?> loaded = getParent().loadClass(name);
            roots.loaded(name);
            if (classFileFirst && classes.size() < mostClasses) {
                classes.add(name);
            }
            return loaded;
        } catch (NoClassDefFoundError e) {
            // HotSpot cuts the message at 1023 bytes of UTF-8, so for a name over about 1,000 ASCII characters, or 500
            // two-byte ones, the "wrong name" error isn't recognised, and MAX_NAME_LENGTH is past the cut even for an
            // ASCII name: the class then can't be loaded rather than being missing, an error, never a wrong answer
            // (#1001).
            if (Throwables.isWrongName(e, name)) {
                throw new ClassNotFoundException(name, e);
            }
            throw e;
        } catch (Error e) {
            if (Throwables.isMissingRegistration(e)) {
                throw new ClassNotFoundException(name, e);
            }
            throw e;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(ExceptionReads.MVEL_PACKAGE)) {
            try {
                return Class.forName(name, false, ExceptionReads.MVEL_LOADER);
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
        return super.loadClass(name, resolve);
    }

    /**
     * Refuses a name too long, or with too many parts, to look up, as {@link Imports#checkSize} refuses an import: as
     * a class that isn't there, unless it has too many parts and a {@code $} and MVEL's lookup of a nested class asks
     * for it, when a {@link NameTooLarge} stops that lookup. Each message says why, such as
     * {@code Can't look up 'a.a.a...$B': it has 81 dot-separated parts and a '$', and a class name with a '$' may have
     * at most 80}.
     *
     * @param name The name to look up
     * @throws ClassNotFoundException if {@code name} has more than {@value ClassLookup#MAX_NAME_LENGTH} characters,
     *                                more than {@value ClassLookup#MAX_NAME_PARTS} dot-separated parts and no
     *                                {@code $}, or a {@code $} and more than {@value ClassLookup#MAX_NAME_PARTS}
     *                                parts, counting one for the {@code $}, and MVEL's lookup of a nested class isn't
     *                                what asks for it
     * @throws NameTooLarge           if MVEL's lookup of a nested class asks for {@code name}, which has a {@code $}
     *                                and more than {@value ClassLookup#MAX_NAME_PARTS} parts, counting one for the
     *                                {@code $}
     */
    private static void checkSize(String name) throws ClassNotFoundException {
        if (!ClassLookup.refuses(name)) {
            return;
        }
        long parts = RuleText.dottedParts(name);
        if (name.indexOf('$') >= 0) {
            if (parts > ClassLookup.MAX_DOLLAR_NAME_PARTS) {
                String refusal = "Can't look up '" + MessageText.quote(name) + "': it has " + parts
                        + " dot-separated parts and a '$', and a class name with a '$' may have at most "
                        + ClassLookup.MAX_DOLLAR_NAME_PARTS;
                if (inNestedLookup()) {
                    throw new NameTooLarge(refusal);
                }
                throw new ClassNotFoundException(refusal);
            }
        } else if (parts > ClassLookup.MAX_NAME_PARTS) {
            throw new ClassNotFoundException("Can't look up '" + MessageText.quote(name) + "': it has " + parts
                    + " dot-separated parts, and a class name may have at most " + ClassLookup.MAX_NAME_PARTS);
        }
        // Refused, and not for its parts, so for its length.
        throw new ClassNotFoundException("Can't look up '" + MessageText.quote(name) + "': it has " + name.length()
                + " characters, and a class name may have at most " + ClassLookup.MAX_NAME_LENGTH);
    }

    /**
     * Tells whether a class loader serves a class file for a name, remembering a name it serves none for, unless
     * {@value ClassLookup#MAX_REMEMBERED_NAMES} are remembered already or the name has more than
     * {@value ClassLookup#MAX_REMEMBERED_NAME_LENGTH} characters: MVEL looks the same names up again and again while a
     * rule list compiles, and each lookup of a class file that isn't there walks the whole class path. The class
     * loader is always the application's, when it serves the class file of every class it finds itself, or else always
     * the platform class loader, asked only for a name in a {@code java.} package, so the names remembered are all of
     * one.
     *
     * @param loader The application's class loader, or the platform class loader
     * @param name   The class's binary name
     * @return {@code false} if {@code loader} has no class by that name
     */
    private boolean hasClassFile(ClassLoader loader, String name) {
        if (noClassFiles.contains(name)) {
            return false;
        }
        boolean found = ClassLookup.mayBeClass(loader, name);
        if (!found && name.length() <= ClassLookup.MAX_REMEMBERED_NAME_LENGTH && noClassFiles.size() < mostClasses) {
            noClassFiles.add(name);
        }
        return found;
    }

    /**
     * Returns how many names with no class file are remembered, so their class file isn't looked up again.
     *
     * @return The count
     */
    int cachedMisses() {
        return noClassFiles.size();
    }

    /**
     * Returns how many names of classes loaded are remembered, so their class file isn't looked up again.
     *
     * @return The count
     */
    int cachedClasses() {
        return classes.size();
    }

    /**
     * Tells whether MVEL's lookup of a nested class is what asks for a name. Only a name already refused asks, so the
     * stack is walked only for a name a rule has no use for.
     *
     * @return {@code true} if {@code ParseTools.findInnerClass} is on the calling thread's stack
     */
    private static boolean inNestedLookup() {
        return StackWalker.getInstance().walk(NESTED_LOOKUP);
    }

    /**
     * What every lookup of a class by name in the rule list shares: the bounds on a name looked up, whether a name can
     * be a class's binary name, whether a class loader serves the class file for one, and which class loaders serve
     * the class file of every class they find. This loader, {@link MvelFactNames}' check of a fact's name in each
     * imported package and the configuration's lookup of a name among the imports (see {@link Imports}) read them,
     * each with its own checks, in its own order, and each with its own answer for what a load throws. It holds no
     * state and has no static initializer, and links no lambda or method reference: every name MVEL looks up comes
     * here, the JVM's first maybe deep in a stack (#1103).
     */
    static final class ClassLookup {

        /**
         * The most characters a name looked up with this loader may have: as many as an import may have, for a
         * package, and as many again for a class in it.
         */
        static final int MAX_NAME_LENGTH = 2 * ImportLimits.MAX_IMPORT_LENGTH;

        /**
         * The most parts a name looked up with this loader may have, counting its dot-separated parts and one more
         * for a {@code $}: as many as an import may have, for a package, 16 more for a class in it and the classes
         * nested in that one, and one more for a member read through the innermost, such as
         * {@code pkg.Outer.Inner.FIELD}, which MVEL looks up as a class first.
         */
        static final int MAX_NAME_PARTS = ImportLimits.MAX_IMPORT_PARTS + 17;

        /**
         * The most dot-separated parts a name with a {@code $} looked up with this loader may have: one fewer than
         * {@link #MAX_NAME_PARTS}, as the {@code $} counts as a part.
         */
        static final int MAX_DOLLAR_NAME_PARTS = MAX_NAME_PARTS - 1;

        /**
         * How many names of each kind a rule list's lookups remember: the names of classes the application's class
         * loader has loaded through this loader, and the names it serves no class file for, so their class file isn't
         * looked up again, and the names {@link MvelFactNames} remembers as not being classes. Of the classes loaded,
         * only a class that loaded is remembered, so no name a rule makes up can take a place, and once the count is
         * reached no more are: a class it didn't remember is looked up as before. Threads adding loaded classes at
         * once may pass that count by as many as they are.
         */
        static final int MAX_REMEMBERED_NAMES = 4096;

        /**
         * The longest name this loader remembers as having no class file, so that the names it remembers, at most
         * {@value #MAX_REMEMBERED_NAMES} of them, take no more characters than {@link MvelFactNames} remembers.
         */
        static final int MAX_REMEMBERED_NAME_LENGTH = 255;

        // The class every class loader of the JDK's own extends (see isJdkLoader).
        private static final String JDK_LOADER = "jdk.internal.loader.BuiltinClassLoader";

        // The plain URLClassLoader that URLClassLoader.newInstance returns on JDK 21 to 23 (see servesEveryClassFile).
        private static final String FACTORY_URL_LOADER = "java.net.FactoryURLClassLoader";

        private ClassLookup() {
        }

        /**
         * Tells whether a name looked up with this loader is refused, before the application's class loader is asked:
         * whether it has more than {@value #MAX_NAME_LENGTH} characters, more than {@value #MAX_NAME_PARTS}
         * dot-separated parts and no {@code $}, or a {@code $} and more than {@value #MAX_NAME_PARTS} parts, counting
         * one for the {@code $}. No class can be found by such a name.
         *
         * @param name The name to look up
         * @return {@code true} if the name is refused
         */
        static boolean refuses(String name) {
            long parts = RuleText.dottedParts(name);
            long mostParts = name.indexOf('$') >= 0 ? MAX_DOLLAR_NAME_PARTS : MAX_NAME_PARTS;
            return name.length() > MAX_NAME_LENGTH || parts > mostParts;
        }

        /**
         * Tells whether a name has only characters a class's binary name can have: a {@code .}, or one a Java
         * identifier may have, which {@code $} is. A name with any other, such as a quote, a parenthesis or a space,
         * is a piece of a statement or a call that MVEL tries as a class, and no class compiled from Java has it. Read
         * with a loop rather than a stream's {@code allMatch}, whose first use would link a lambda and load the JDK's
         * stream classes: every name MVEL looks up comes here, and the JVM's first may be deep in a stack (#1103).
         *
         * @param name The name to look up
         * @return {@code false} if no class can have the name
         */
        static boolean isBinaryName(String name) {
            int c;
            for (int i = 0; i < name.length(); i += Character.charCount(c)) {
                c = name.codePointAt(i);
                if (c != '.' && !Character.isJavaIdentifierPart(c)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Tells whether a class loader may have a class by a name, without asking it to load one: whether it serves
         * the class file, which keeps no lock object in a parallel-capable loader, as asking it to load the class
         * would (see {@code MvelFactNames.isClass}). In a native image, which serves no class file it wasn't
         * configured to, and whose loaders keep no such lock, any name may be a class. A name this answers
         * {@code true} for may still not be a class, such as {@code date} for {@code Date.class} in a class directory
         * on a case-insensitive file system, so only loading it tells.
         *
         * @param loader     The class loader
         * @param binaryName The class's binary name, such as {@code java.util.Map$Entry}
         * @return {@code false} if the class loader has no class by that name
         */
        static boolean mayBeClass(ClassLoader loader, String binaryName) {
            return inNativeImage() || loader.getResource(binaryName.replace('.', '/') + ".class") != null;
        }

        /**
         * Tells whether this call is running in a native image, as GraalVM's own {@code ImageInfo.inImageRuntimeCode}
         * does, without a dependency on its SDK. The property is {@code "runtime"} only in an image, and
         * {@code "buildtime"} while one is being built, where class loading is an ordinary JVM's and the lock the
         * class file lookup avoids is real, so the value is compared and not merely tested for. It is read on every
         * call and never into a field: a field an image's build filled in would answer {@code "buildtime"} for the
         * image's whole life. Only a name no cache knows gets this far: neither {@link MvelFactNames}' cache nor, for
         * a name in a rule, the rule list's own (see {@link Imports}).
         */
        private static boolean inNativeImage() {
            return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
        }

        /**
         * Loads a class by its binary name, as {@link Class#forName(String, boolean, ClassLoader)} does. What it throws
         * is the caller's to read.
         *
         * @param name       The class's binary name
         * @param initialize Whether to initialize the class
         * @param loader     The class loader to load it with
         * @return The class
         * @throws ClassNotFoundException if {@code loader} has no class by that name
         */
        static Class<?> forName(String name, boolean initialize, ClassLoader loader) throws ClassNotFoundException {
            return Class.forName(name, initialize, loader);
        }

        /**
         * Tells whether a class loader has the class file for one of the names MVEL looks a name up by in imported
         * packages: each package's name, a dot and the name, then that with each dot from the right turned into
         * {@code $}, one at a time, as MVEL's {@code ParseTools.findInnerClass} does. The package's dots are turned
         * too, as MVEL turns them. With no package imported, nothing is looked for, nor is a name MVEL rejects
         * outright, as it doesn't start as a Java identifier does, such as the {@code [Lpkg.Foo;} of an array type, nor
         * one this loader refuses (see {@link #refuses}). Once it refuses a name with a {@code $}, MVEL gives up on the
         * rest: each has as many characters, and as many parts as it counts them, or fewer by one.
         *
         * @param loader   The class loader whose class files to look up
         * @param packages The imported packages' names, or {@code null} if none are imported
         * @param name     The name, as the expression writes it
         * @return {@code true} if MVEL may find a class by the name in one of {@code packages}
         */
        static boolean anyClassFile(ClassLoader loader, Set<String> packages, String name) {
            if (packages == null || !Character.isJavaIdentifierStart(name.charAt(0))) {
                return false;
            }
            for (String pkg : packages) {
                String className = pkg + '.' + name;
                if (!refuses(className) && mayBeClass(loader, className)) {
                    return true;
                }
                for (int dot = className.lastIndexOf('.'); dot > 0; dot = className.lastIndexOf('.')) {
                    className = className.substring(0, dot) + '$' + className.substring(dot + 1);
                    if (refuses(className)) {
                        break;
                    }
                    if (mayBeClass(loader, className)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Tells whether a class loader is one of the JDK's own, the application or the platform class loader, which
         * both extend the JDK's internal {@code jdk.internal.loader.BuiltinClassLoader}, and delegate to the boot
         * loader. Each serves the class file of every class it loads from the class path, the module path or the
         * JDK's own modules, but not of a class defined in it at run time, such as with
         * {@code MethodHandles.Lookup.defineClass} by a code generator. None can be extended outside the JDK, whose
         * package isn't exported, so a class loader of an application or a framework never is one, even one set as
         * the system class loader with {@code -Djava.system.class.loader}. The class is matched by name, which needs
         * no access to the JDK's internals. A {@code null} loader, the boot loader, isn't one here: the rule list's
         * class loader, which asks its parent for every class, can't load through it.
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
         * Tells whether a class loader serves the class file of every class it finds itself: one of the JDK's own
         * (see {@link #isJdkLoader}), or a plain {@link URLClassLoader}, of that class exactly, whose parents are
         * plain ones too, up to one of the JDK's own or the boot loader. A plain {@code URLClassLoader} asks its
         * parent first, for a class and for a class file alike, and defines a class only from a class file at one of
         * its URLs. The one {@code URLClassLoader.newInstance} returns on JDK 21 to 23, the JDK's own
         * {@code java.net.FactoryURLClassLoader}, which only adds a security manager's check of the package, is plain
         * too, matched by name, as it isn't public. Like the JDK's own, a plain one serves no class file for a class
         * defined in it at run time, such as with {@code MethodHandles.Lookup.defineClass}, which it still loads by
         * name. A subclass may load classes any way it likes, so it isn't one, nor is a chain with any other class
         * loader in it. A {@code null} loader isn't one either, as for {@link #isJdkLoader}.
         *
         * @param loader The class loader, or {@code null}
         * @return {@code true} if a name the loader serves no class file for is taken as no class
         */
        static boolean servesEveryClassFile(ClassLoader loader) {
            for (ClassLoader each = loader; each != null; each = each.getParent()) {
                if (isJdkLoader(each)) {
                    return true;
                }
                if (each.getClass() != URLClassLoader.class && !FACTORY_URL_LOADER.equals(each.getClass().getName())) {
                    return false;
                }
            }
            // The boot loader, past a chain of plain URL class loaders, or no loader at all.
            return loader != null;
        }
    }

    /**
     * What the class loader throws, in place of a lookup, when MVEL's lookup of a nested class asks for a name with a
     * {@code $} and too many dot-separated parts. The lookup gives up on the name after it, as after any exception
     * that isn't a {@link ClassNotFoundException}, and each of its callers in MVEL catches it and reads the name as
     * properties or as no import.
     */
    static final class NameTooLarge extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NameTooLarge(String message) {
            super(message);
        }
    }

    /**
     * Tells whether {@code ParseTools.findInnerClass} is among the frames of a stack, read with a loop rather than a
     * stream's {@code anyMatch}, whose first use would link the JDK's own lambdas (#1099).
     */
    private static final class NestedLookup implements Function<Stream<StackWalker.StackFrame>, Boolean> {

        @Override
        public Boolean apply(Stream<StackWalker.StackFrame> frames) {
            for (Iterator<StackWalker.StackFrame> each = frames.iterator(); each.hasNext(); ) {
                StackWalker.StackFrame frame = each.next();
                if (NESTED_LOOKUP_CLASS.equals(frame.getClassName())
                        && NESTED_LOOKUP_METHOD.equals(frame.getMethodName())) {
                    return true;
                }
            }
            return false;
        }
    }
}
