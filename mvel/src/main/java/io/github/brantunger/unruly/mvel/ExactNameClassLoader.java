package io.github.brantunger.unruly.mvel;

import org.mvel2.MVEL;

/**
 * The class loader MVEL compiles rules with. It asks the application's class loader for every class, but reports a
 * class file whose name only matches in a different case as a missing class, and links the code MVEL's JIT generates
 * against the MVEL running the rules.
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
 * {@link #loadClass(String)}, which asks the application's class loader for every class.
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
 * a {@link ClassNotFoundException}. Every rule that loaded without the bound and names no class of more than 80
 * parts still loads, and runs the same. A member read through a class of more than 80 parts, such as
 * {@code pkg.Outer.Inner.FIELD} with 81 parts before the field, is read as properties, as MVEL gives up on the chain
 * before it looks up the class.
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

    // MVEL's lookup of a nested class, which turns the dots of a name into $ one at a time, and the class it is in.
    private static final String NESTED_LOOKUP_CLASS = ExceptionReads.MVEL_PACKAGE + "util.ParseTools";
    private static final String NESTED_LOOKUP_METHOD = "findInnerClass";

    static {
        registerAsParallelCapable();
    }

    /**
     * Creates a class loader that asks {@code parent} for every class, apart from those MVEL's own class loader has
     * when the JVM links an accessor MVEL's JIT compiled.
     *
     * @param parent The application's class loader
     */
    ExactNameClassLoader(ClassLoader parent) {
        super(parent);
    }

    /**
     * Looks a class up in the application's class loader, unless its name is too long, or has too many parts, to be
     * a class the engine can import.
     *
     * @param name The class's binary name
     * @return The class
     * @throws ClassNotFoundException if the application's class loader has no class by that name, or only a class
     *                                file whose name differs in case, or, before the application's class loader is
     *                                asked, if {@code name} has more than {@value #MAX_NAME_LENGTH} characters, or
     *                                more than {@value #MAX_NAME_PARTS} dot-separated parts and no {@code $}
     * @throws NameTooLarge           if MVEL's lookup of a nested class asks for {@code name}, which has a {@code $}
     *                                and more than {@value #MAX_NAME_PARTS} parts, counting one for the {@code $},
     *                                before the application's class loader is asked
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        checkSize(name);
        try {
            return getParent().loadClass(name);
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
        long parts = name.chars().filter(c -> c == '.').count() + 1;
        if (name.indexOf('$') >= 0) {
            if (parts > MAX_NAME_PARTS - 1) {
                String refusal = "Can't look up '" + FactNames.quote(name) + "': it has " + parts
                        + " dot-separated parts and a '$', and a class name with a '$' may have at most "
                        + (MAX_NAME_PARTS - 1);
                if (inNestedLookup()) {
                    throw new NameTooLarge(refusal);
                }
                throw new ClassNotFoundException(refusal);
            }
        } else if (parts > MAX_NAME_PARTS) {
            throw new ClassNotFoundException("Can't look up '" + FactNames.quote(name) + "': it has " + parts
                    + " dot-separated parts, and a class name may have at most " + MAX_NAME_PARTS);
        }
        // Refused, and not for its parts, so for its length.
        throw new ClassNotFoundException("Can't look up '" + FactNames.quote(name) + "': it has " + name.length()
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
        long parts = name.chars().filter(c -> c == '.').count() + 1;
        long mostParts = name.indexOf('$') >= 0 ? MAX_NAME_PARTS - 1 : MAX_NAME_PARTS;
        return name.length() > MAX_NAME_LENGTH || parts > mostParts;
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
