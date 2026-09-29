package io.github.brantunger.unruly.mvel;

/**
 * A class whose static initialiser records the thread's context class loader, for
 * {@link CompileContextClassLoaderTest}. No other test refers to it, so the rule that names it initialises it.
 */
public final class ClassInitProbe {

    /** The thread's context class loader when the class was initialised. */
    public static final ClassLoader SEEN = Thread.currentThread().getContextClassLoader();

    /** What the rule reads. */
    public static final int VALUE = 1;

    private ClassInitProbe() {
    }
}
