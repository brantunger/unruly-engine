package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.MessageText;
import org.mvel2.MVEL;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The class loader MVEL compiles rules with. It asks the application's class loader for every class, unless the name
 * can't be one, but reports a class file whose name only matches in a different case as a missing class, and links the
 * code MVEL's JIT generates against the MVEL running the rules.
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
 * more than {@value #MAX_NAME_LENGTH} characters or more than {@value #MAX_NAME_PARTS} parts: its dot-separated
 * parts, and one more for a name with a {@code $}, which names a nested class. MVEL reads a dotted chain in a rule,
 * such as {@code a.b.c.d == 1}, by looking the chain up as a class, then again with its dots turned into {@code $} one
 * at a time from the right, as a nested class, and again for each shorter chain, so a chain of n parts cost lookups
 * that grew with n squared, each of which a parallel-capable class loader keeps a lock object for. Only the dots
 * make that loop longer, so only they are counted: MVEL also looks a property up as a class nested in the type it is
 * read from, such as {@code java.lang.Object$x$y} for {@code m.x$y}, and a property may have any number of
 * {@code $}.
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
 * {@value #MAX_DOLLAR_NAME_PARTS} parts still loads, and runs the same. A member read through a class of more than
 * {@value #MAX_DOLLAR_NAME_PARTS} parts, such as {@code pkg.Outer.Inner.FIELD} with {@value #MAX_NAME_PARTS} parts
 * before the field, is read as properties, as MVEL gives up on the chain before it looks up the class.
 * </p>
 *
 * <p>
 * A name looked up with this loader itself is also refused, as a {@link ClassNotFoundException}, when no class can have
 * it (#752). MVEL looks up much that isn't a class: every prefix of a property chain such as {@code f.p7.q}, with its
 * dots turned into {@code $}, while it compiles, and, after an {@code import pkg.*;} in the rule's text, whole
 * statements such as {@code java.util.output.put('k', x)} when it first runs. A parallel-capable class loader, as the
 * JDK's are, keeps a lock object for every name it is asked for, for as long as it lives, so a rule list whose rules
 * differ in their names or literals left more on every load or first run. A name with a character that is neither a
 * {@code .} nor one a Java identifier may have is refused with any application class loader. When the application's is
 * one of the JDK's own (see {@link Imports#isJdkLoader}), a name it serves no class file for is refused too, as the
 * class file lookup takes no lock (see {@link FactNames#mayBeClass}). Neither the class file of a class it loaded nor
 * the lack of one is looked up again, for up to {@value #MAX_CACHED_CLASSES} names of each. Such a loader serves a
 * class file for every class it loads from the class path, the module path or the JDK, but not for a class defined in
 * it at run time, such as with {@code MethodHandles.Lookup.defineClass}: a rule can't import that class inline, nor
 * name it fully qualified where MVEL looks it up while the rule compiles, as with strong typing. A class import the
 * engine was built with still finds it, as it is loaded as a {@code Class} and never looked up by name here. Any other
 * application class loader, which may define a class it serves no class file for, is asked for every well-formed name,
 * as before. Lookups the JIT's class loader passes on aren't checked. After this loader refuses a name, MVEL's
 * {@code ParseTools.createClass} asks the thread's context class loader for it, unless that is this loader, as it is
 * while MVEL compiles a rule (see {@link MvelExpression}), so a property read through a value MVEL types as
 * {@code Object}, such as {@code java.lang.Object$p7} for {@code f.p7}, leaves no lock object (#807). A class a rule
 * creates with {@code new} is still asked of the running thread's context class loader when the rule runs.
 * </p>
 */
final class ExactNameClassLoader extends ClassLoader {

    // The class loader of the MVEL running the rules. PMD asks for the context class loader instead, which is the one
    // that may not see MVEL.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static final ClassLoader MVEL_CLASS_LOADER = MVEL.class.getClassLoader();

    /**
     * The most characters a name looked up with this loader may have: as many as an import may have, for a package,
     * and as many again for a class in it.
     */
    static final int MAX_NAME_LENGTH = 2 * Imports.MAX_IMPORT_LENGTH;

    /**
     * The most parts a name looked up with this loader may have, counting its dot-separated parts and one more for a
     * {@code $}: as many as an import may have, for a package, 16 more for a class in it and the classes nested in
     * that one, and one more for a member read through the innermost, such as {@code pkg.Outer.Inner.FIELD}, which
     * MVEL looks up as a class first.
     */
    static final int MAX_NAME_PARTS = Imports.MAX_IMPORT_PARTS + 17;

    /**
     * The most dot-separated parts a name with a {@code $} looked up with this loader may have: one fewer than
     * {@link #MAX_NAME_PARTS}, as the {@code $} counts as a part.
     */
    static final int MAX_DOLLAR_NAME_PARTS = MAX_NAME_PARTS - 1;

    /**
     * How many names of classes the application's class loader has loaded through this one are remembered, so their
     * class file isn't looked up again: as many as {@link FactNames} remembers names that aren't classes. Only a class
     * that loaded is remembered, so no name a rule makes up can take a place, and once the count is reached no more
     * are: a class it didn't remember is looked up as before. Threads adding at once may pass it by as many as they
     * are.
     */
    static final int MAX_CACHED_CLASSES = FactNames.MAX_CACHED_MISSES;

    /**
     * The longest name remembered as having no class file, so that the names remembered, at most
     * {@value #MAX_CACHED_CLASSES} of them, take no more characters than {@link FactNames} remembers.
     */
    static final int MAX_CACHED_MISS_LENGTH = FactNames.MAX_CACHED_MISS_CHARS / FactNames.MAX_CACHED_MISSES;

    // MVEL's lookup of a nested class, which turns the dots of a name into $ one at a time, and the class it is in.
    private static final String NESTED_LOOKUP_CLASS = ExceptionReads.MVEL_PACKAGE + "util.ParseTools";
    private static final String NESTED_LOOKUP_METHOD = "findInnerClass";

    static {
        registerAsParallelCapable();
    }

    // Whether the application's class loader is one of the JDK's own, which serves the class file of every class it
    // can load by name, so a name without one is refused before it is asked.
    private final boolean classFileFirst;

    // The names of classes the application's class loader has loaded through this one, when it is one of the JDK's
    // own: a rule list compiles the same few classes' names again and again, and each class file lookup walks the
    // class path.
    private final Set<String> classes = ConcurrentHashMap.newKeySet();

    // The names the application's class loader, one of the JDK's own, serves no class file for. A class defined at
    // run time has none, so remembering one changes nothing for it. A class file that appears later, such as in a
    // class directory or through Instrumentation.appendToSystemClassLoaderSearch, isn't found through this loader:
    // the engine makes one for each load() or validate(), so a name stays remembered for that rule list's compiling
    // and runs only, and the next load() starts with none.
    private final Set<String> noClassFiles = ConcurrentHashMap.newKeySet();

    private final int mostClasses;

    /**
     * Creates a class loader that asks {@code parent} for every class a name may be, apart from those MVEL's own
     * class loader has when the JVM links an accessor MVEL's JIT compiled.
     *
     * @param parent The application's class loader
     */
    ExactNameClassLoader(ClassLoader parent) {
        this(parent, MAX_CACHED_CLASSES);
    }

    /**
     * Creates a class loader that asks {@code parent} for every class a name may be, and remembers at most
     * {@code mostClasses} of the classes it loads.
     *
     * @param parent      The application's class loader
     * @param mostClasses How many names of classes loaded to remember
     */
    ExactNameClassLoader(ClassLoader parent, int mostClasses) {
        this(parent, mostClasses, Imports.isJdkLoader(parent));
    }

    /**
     * Creates a class loader that asks {@code parent} for every class a name may be, first for its class file if
     * {@code classFileFirst}, and remembers at most {@code mostClasses} names of each kind.
     *
     * @param parent         The application's class loader
     * @param mostClasses    How many names of classes loaded, and of names with no class file, to remember
     * @param classFileFirst Whether to refuse a name {@code parent} serves no class file for, as for one of the JDK's
     *                       own loaders
     */
    ExactNameClassLoader(ClassLoader parent, int mostClasses, boolean classFileFirst) {
        super(parent);
        this.classFileFirst = classFileFirst;
        this.mostClasses = mostClasses;
    }

    /**
     * Looks a class up in the application's class loader, unless its name is too long, or has too many parts, to be
     * a class the engine can import, or can't be a class's name.
     *
     * @param name The class's binary name
     * @return The class
     * @throws ClassNotFoundException if the application's class loader has no class by that name, or only a class
     *                                file whose name differs in case, or, before the application's class loader is
     *                                asked, if {@code name} has more than {@value #MAX_NAME_LENGTH} characters, or
     *                                more than {@value #MAX_NAME_PARTS} dot-separated parts and no {@code $}, or a
     *                                character that is neither a {@code .} nor one a Java identifier may have, or if
     *                                the application's class loader is one of the JDK's own and serves no class file
     *                                for it
     * @throws NameTooLarge           if MVEL's lookup of a nested class asks for {@code name}, which has a {@code $}
     *                                and more than {@value #MAX_NAME_PARTS} parts, counting one for the {@code $},
     *                                before the application's class loader is asked
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        checkSize(name);
        if (!isBinaryName(name)
                || classFileFirst && !classes.contains(name) && !hasClassFile(name)) {
            throw new ClassNotFoundException(name);
        }
        try {
            Class<?> loaded = getParent().loadClass(name);
            if (classFileFirst && classes.size() < mostClasses) {
                classes.add(name);
            }
            return loaded;
        } catch (NoClassDefFoundError e) {
            if (isWrongName(e)) {
                throw new ClassNotFoundException(name, e);
            }
            throw e;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(ExceptionReads.MVEL_PACKAGE)) {
            try {
                return Class.forName(name, false, MVEL_CLASS_LOADER);
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
     * @throws ClassNotFoundException if {@code name} has more than {@value #MAX_NAME_LENGTH} characters, more than
     *                                {@value #MAX_NAME_PARTS} dot-separated parts and no {@code $}, or a {@code $}
     *                                and more than {@value #MAX_NAME_PARTS} parts, counting one for the {@code $},
     *                                and MVEL's lookup of a nested class isn't what asks for it
     * @throws NameTooLarge           if MVEL's lookup of a nested class asks for {@code name}, which has a {@code $}
     *                                and more than {@value #MAX_NAME_PARTS} parts, counting one for the {@code $}
     */
    private static void checkSize(String name) throws ClassNotFoundException {
        if (!refuses(name)) {
            return;
        }
        long parts = RuleText.dottedParts(name);
        if (name.indexOf('$') >= 0) {
            if (parts > MAX_DOLLAR_NAME_PARTS) {
                String refusal = "Can't look up '" + MessageText.quote(name) + "': it has " + parts
                        + " dot-separated parts and a '$', and a class name with a '$' may have at most "
                        + MAX_DOLLAR_NAME_PARTS;
                if (inNestedLookup()) {
                    throw new NameTooLarge(refusal);
                }
                throw new ClassNotFoundException(refusal);
            }
        } else if (parts > MAX_NAME_PARTS) {
            throw new ClassNotFoundException("Can't look up '" + MessageText.quote(name) + "': it has " + parts
                    + " dot-separated parts, and a class name may have at most " + MAX_NAME_PARTS);
        }
        // Refused, and not for its parts, so for its length.
        throw new ClassNotFoundException("Can't look up '" + MessageText.quote(name) + "': it has " + name.length()
                + " characters, and a class name may have at most " + MAX_NAME_LENGTH);
    }

    /**
     * Tells whether a name looked up with this loader is refused, before the application's class loader is asked:
     * whether it has more than {@value #MAX_NAME_LENGTH} characters, more than {@value #MAX_NAME_PARTS} dot-separated
     * parts and no {@code $}, or a {@code $} and more than {@value #MAX_NAME_PARTS} parts, counting one for the
     * {@code $}. No class can be found by such a name.
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
     * Tells whether the application's class loader serves a class file for a name, remembering a name it serves none
     * for, unless {@value #MAX_CACHED_CLASSES} are remembered already or the name has more than
     * {@value #MAX_CACHED_MISS_LENGTH} characters: MVEL looks the same names up again and again while a rule list
     * compiles, and each lookup of a class file that isn't there walks the whole class path.
     *
     * @param name The class's binary name
     * @return {@code false} if the application's class loader has no class by that name
     */
    private boolean hasClassFile(String name) {
        if (noClassFiles.contains(name)) {
            return false;
        }
        boolean found = FactNames.mayBeClass(getParent(), name);
        if (!found && name.length() <= MAX_CACHED_MISS_LENGTH && noClassFiles.size() < mostClasses) {
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
     * Tells whether a name has only characters a class's binary name can have: a {@code .}, or one a Java identifier
     * may have, which {@code $} is. A name with any other, such as a quote, a parenthesis or a space, is a piece of a
     * statement or a call that MVEL tries as a class, and no class compiled from Java has it.
     *
     * @param name The name to look up
     * @return {@code false} if no class can have the name
     */
    static boolean isBinaryName(String name) {
        return name.codePoints().allMatch(c -> c == '.' || Character.isJavaIdentifierPart(c));
    }

    /**
     * Tells whether MVEL's lookup of a nested class is what asks for a name. Only a name already refused asks, so the
     * stack is walked only for a name a rule has no use for.
     *
     * @return {@code true} if {@code ParseTools.findInnerClass} is on the calling thread's stack
     */
    private static boolean inNestedLookup() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame
                -> NESTED_LOOKUP_CLASS.equals(frame.getClassName())
                && NESTED_LOOKUP_METHOD.equals(frame.getMethodName())));
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
}
