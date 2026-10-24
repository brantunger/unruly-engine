package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.Throwables;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;

/**
 * Reads the stack trace of an exception the module didn't create, such as one a class's static initializer or the
 * application's class loader threw, without letting what its accessor throws escape. It isn't final, and a failure to
 * read it says nothing about the failure being reported, which is still reported as it would be. The accessor is
 * called in a {@code try} of its own rather than through a lambda or method reference, so the JVM's first failure links
 * no call site, which could overflow deep in a stack (#1093). The module reads an exception's message and causes, and
 * tells an overflow, with the engine's readers in {@link Throwables}, which read them the same way.
 */
final class ExceptionReads {

    /** The package of MVEL's classes, taken from MVEL itself so that a relocated copy is matched too. */
    static final String MVEL_PACKAGE = MVEL.class.getPackageName() + ".";

    /**
     * The class loader of the MVEL running the rules: the one MVEL's {@code addPackageImport} loads a class with,
     * through {@code Class.forName}, so {@link Imports#nullStaticField} looks the class up with it too; the context
     * class loader while MVEL's optimizer sets up and while the warm-up runs (see {@link MvelExpression}); and the one
     * {@link ExactNameClassLoader} takes a class in MVEL's package from first, when the JIT's class loader links an
     * accessor against it.
     */
    // PMD asks for the context class loader instead, which is the one that may not see MVEL.
    @SuppressWarnings("PMD.UseProperClassLoader")
    static final ClassLoader MVEL_LOADER = ParserConfiguration.class.getClassLoader();

    private ExceptionReads() {
    }

    /**
     * Reads an exception's stack trace as {@link Throwables#messageOf} reads its message.
     *
     * @param e The exception
     * @return Its stack trace, which is empty if {@code getStackTrace()} throws or returns {@code null}
     */
    // One that can't be read is taken for one that is null, so both are read as empty.
    @SuppressWarnings("PMD.NullAssignment")
    static StackTraceElement[] stackTraceOf(Throwable e) {
        StackTraceElement[] frames;
        try {
            frames = e.getStackTrace();
        } catch (Throwable thrown) {
            frames = null;
        }
        return frames == null ? new StackTraceElement[0] : frames;
    }

    /**
     * Tells whether an exception was created in a class of a package, going by the top frame of its stack trace.
     *
     * @param e             The exception
     * @param packagePrefix The package's name, ending in a dot
     * @return {@code false} if the top frame is in another package, or the stack trace is empty or can't be read
     */
    static boolean thrownFrom(Throwable e, String packagePrefix) {
        return topFrameIn(e, packagePrefix, false);
    }

    /**
     * Tells whether an exception was created in a class of a package, going by the top frame of its stack trace, with
     * what an empty stack trace answers given. A stack trace that can't be read, because {@code getStackTrace()}
     * throws, returns {@code null} or has {@code null} for its top frame, answers {@code false}: only a subclass can
     * give one.
     *
     * @param e             The exception
     * @param packagePrefix The package's name, ending in a dot
     * @param ifEmpty       What an empty stack trace answers
     * @return {@code true} if the top frame is in the package, or {@code ifEmpty} if there is none
     */
    static boolean topFrameIn(Throwable e, String packagePrefix, boolean ifEmpty) {
        StackTraceElement[] frames;
        try {
            frames = e.getStackTrace();
        } catch (Throwable thrown) {
            return false;
        }
        if (frames == null) {
            return false;
        }
        if (frames.length == 0) {
            return ifEmpty;
        }
        return frames[0] != null && frames[0].getClassName().startsWith(packagePrefix);
    }
}
