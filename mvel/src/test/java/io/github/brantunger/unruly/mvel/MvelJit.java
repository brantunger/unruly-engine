package io.github.brantunger.unruly.mvel;

import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.dynamic.DynamicOptimizer;

import java.lang.invoke.MethodHandles;

/**
 * Makes MVEL's JIT optimizer MVEL's default again, for the whole JVM, as an application may, for the tests of what
 * the engine does once the JIT compiles an accessor; {@link #off()} makes the optimizer that was the default before,
 * the reflective one the engine makes the default as {@link MvelExpression} initializes, the default again. A thread's
 * own optimizer doesn't do: most of what MVEL optimizes asks for the default. The tests of a module run one at a time,
 * so none sees another's.
 */
final class MvelJit {

    // The default before on(), which off() restores; null when on() hasn't run since. Restored, not set to the
    // reflective optimizer: on code that leaves MVEL's JIT the default, as before #1161, setting the reflective one
    // here made the tests that ran after these pass by their order alone, and hid the failures they check for.
    private static String previous;

    private MvelJit() {
    }

    /**
     * Makes the JIT MVEL's default, once {@link MvelExpression} has initialized, so its static initializer can't make
     * the reflective optimizer the default again after this.
     *
     * @throws IllegalStateException if called again before {@link #off()}, which would lose the default to restore
     */
    static void on() {
        if (previous != null) {
            throw new IllegalStateException("MvelJit.on() called again before off()");
        }
        try {
            MethodHandles.lookup().ensureInitialized(MvelExpression.class);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        previous = OptimizerFactory.getDefaultAccessorCompiler() instanceof DynamicOptimizer
                ? OptimizerFactory.DYNAMIC : OptimizerFactory.SAFE_REFLECTIVE;
        setDefault(OptimizerFactory.DYNAMIC);
    }

    /** Makes the optimizer that was MVEL's default before {@link #on()} the default again, and clears the thread's. */
    static void off() {
        if (previous != null) {
            setDefault(previous);
            previous = null;
        }
        OptimizerFactory.clearThreadAccessorOptimizer();
    }

    // As MvelExpression's static initializer does it: the JIT, as it sets up, makes a JVM-wide class loader whose
    // parent is the thread's context class loader, and keeps it until the JIT is made the default again, as here, which
    // makes a new one whose parent is that caller's context class loader.
    private static void setDefault(String name) {
        Thread thread = Thread.currentThread();
        ClassLoader context = thread.getContextClassLoader();
        thread.setContextClassLoader(OptimizerFactory.class.getClassLoader());
        try {
            OptimizerFactory.setDefaultOptimizer(name);
        } finally {
            thread.setContextClassLoader(context);
        }
    }
}
