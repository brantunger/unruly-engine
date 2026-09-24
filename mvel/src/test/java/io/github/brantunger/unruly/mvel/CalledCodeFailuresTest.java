package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The exception chains {@link CalledCodeFailures} leaves as MVEL threw them that no rule makes MVEL throw: those it
 * can't read, and an {@link InvocationTargetException} under MVEL's exceptions that reflection didn't make.
 */
@DisplayName("an exception chain that can't be read is left as MVEL threw it")
class CalledCodeFailuresTest {

    private static StackTraceElement[] frameIn(String className) {
        return new StackTraceElement[] {new StackTraceElement(className, "invoke", null, 1)};
    }

    /** A plain RuntimeException, as MVEL throws one, with {@code cause} if it isn't null. */
    private static RuntimeException fromMvel(Throwable cause) {
        RuntimeException mvel = new RuntimeException("cannot invoke method");
        if (cause != null) {
            mvel.initCause(cause);
        }
        mvel.setStackTrace(frameIn("org.mvel2.optimizers.impl.refl.nodes.MethodAccessor"));
        return mvel;
    }

    private static InvocationTargetException fromReflection(Throwable target) {
        InvocationTargetException call = new InvocationTargetException(target);
        call.setStackTrace(frameIn("jdk.internal.reflect.DirectMethodHandleAccessor"));
        return call;
    }

    @Test
    @DisplayName("the unwrapping chain itself: MVEL's exception over reflection's")
    void unwrapped() {
        IllegalStateException target = new IllegalStateException("target");

        assertSame(target, CalledCodeFailures.thrownByCalledCode(fromMvel(fromReflection(target))));
    }

    @Test
    @DisplayName("a chain of MVEL's exceptions that loops back on itself")
    void cyclicChain() {
        RuntimeException outer = fromMvel(null);
        RuntimeException inner = fromMvel(outer);
        outer.initCause(inner);

        assertSame(outer, CalledCodeFailures.thrownByCalledCode(outer));
    }

    @Test
    @DisplayName("a RuntimeException without a stack trace, as with -XX:-StackTraceInThrowable")
    void noStackTrace() {
        RuntimeException bare = new RuntimeException("no frames",
                fromReflection(new IllegalStateException("target")));
        bare.setStackTrace(new StackTraceElement[0]);

        assertSame(bare, CalledCodeFailures.thrownByCalledCode(bare));
    }

    @Test
    @DisplayName("an InvocationTargetException without a stack trace")
    void invocationWithoutAStackTrace() {
        InvocationTargetException call = new InvocationTargetException(new IllegalStateException("target"));
        call.setStackTrace(new StackTraceElement[0]);
        RuntimeException mvel = fromMvel(call);

        assertSame(mvel, CalledCodeFailures.thrownByCalledCode(mvel));
    }

    @Test
    @DisplayName("an InvocationTargetException reflection didn't make")
    void invocationNotFromReflection() {
        InvocationTargetException call = new InvocationTargetException(new IllegalStateException("target"));
        call.setStackTrace(frameIn("com.example.Invoker"));
        RuntimeException mvel = fromMvel(call);

        assertSame(mvel, CalledCodeFailures.thrownByCalledCode(mvel));
    }

    @Test
    @DisplayName("an InvocationTargetException without a cause")
    void invocationWithoutACause() {
        RuntimeException mvel = fromMvel(fromReflection(null));

        assertSame(mvel, CalledCodeFailures.thrownByCalledCode(mvel));
    }

    @Test
    @DisplayName("an InvocationTargetException whose getCause() throws an Error")
    void throwingGetCause() {
        InvocationTargetException call = new InvocationTargetException(new IllegalStateException("target")) {
            @Override
            public Throwable getCause() {
                throw new NoClassDefFoundError("com/acme/Missing");
            }
        };
        call.setStackTrace(frameIn("jdk.internal.reflect.DirectMethodHandleAccessor"));
        RuntimeException mvel = fromMvel(call);

        assertSame(mvel, CalledCodeFailures.thrownByCalledCode(mvel));
    }

    @Test
    @DisplayName("an InvocationTargetException whose getStackTrace() throws")
    void throwingGetStackTrace() {
        InvocationTargetException call = new InvocationTargetException(new IllegalStateException("target")) {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("broken getStackTrace");
            }
        };
        RuntimeException mvel = fromMvel(call);

        assertSame(mvel, CalledCodeFailures.thrownByCalledCode(mvel));
    }
}
