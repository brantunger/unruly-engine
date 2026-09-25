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
 */
final class ExactNameClassLoader extends ClassLoader {

    /** The package of MVEL's classes, taken from MVEL itself so that a relocated copy is matched too. */
    private static final String MVEL_PACKAGE = MVEL.class.getPackageName() + ".";

    // The class loader of the MVEL running the rules. PMD asks for the context class loader instead, which is the one
    // that may not see MVEL.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static final ClassLoader MVEL_CLASS_LOADER = MVEL.class.getClassLoader();

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

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
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
        if (name.startsWith(MVEL_PACKAGE)) {
            try {
                return Class.forName(name, false, MVEL_CLASS_LOADER);
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
        return super.loadClass(name, resolve);
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
}
