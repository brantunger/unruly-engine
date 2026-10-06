package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.MVEL;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MvelExpressionLanguage")
class MvelExpressionLanguageTest {

    @Test
    @DisplayName("is named mvel")
    void named() {
        assertEquals("mvel", new MvelExpressionLanguage().name());
        assertEquals("mvel", MvelExpressionLanguage.LANGUAGE_NAME);
    }

    @Test
    @DisplayName("newCompiler names a null context")
    void newCompilerRejectsNull() {
        MvelExpressionLanguage language = new MvelExpressionLanguage();

        assertEquals("context must not be null",
                assertThrows(NullPointerException.class, () -> language.newCompiler(null)).getMessage());
    }

    @Test
    @DisplayName("prepare's warm-up is done when it finishes")
    void warmUpFinished() {
        assertTrue(MvelExpressionLanguage.warmedUp(() -> { }));
    }

    @Test
    @DisplayName("prepare's warm-up is skipped when it overflows (#1042)")
    void warmUpOverflowSkipped() {
        assertFalse(MvelExpressionLanguage.warmedUp(() -> {
            throw new StackOverflowError();
        }));
    }

    @Test
    @DisplayName("prepare's warm-up is skipped when it throws an overflow in a cause chain (#1042)")
    void warmUpWrappedOverflowSkipped() {
        assertFalse(MvelExpressionLanguage.warmedUp(() -> {
            throw new IllegalStateException(new InvocationTargetException(new StackOverflowError()));
        }));
    }

    @Test
    @DisplayName("prepare's warm-up is skipped when a method MVEL calls overflows, which MVEL wraps (#1042)")
    void warmUpMvelWrappedOverflowSkipped() {
        Map<String, Object> variables = Map.of("v", new Overflowing());
        Runnable call = () -> MVEL.executeExpression(MVEL.compileExpression("v.call()"), (Object) null, variables);

        Throwable thrown = assertThrows(RuntimeException.class, call::run);
        assertInstanceOf(StackOverflowError.class, ExceptionReads.rootCause(thrown), "MVEL wraps the overflow");
        assertFalse(MvelExpressionLanguage.warmedUp(call));
    }

    @Test
    @DisplayName("prepare's warm-up throws anything else as it is, so prepare() fails (#1042)")
    void warmUpOtherFailureThrown() {
        IllegalStateException failure = new IllegalStateException("no access", new IllegalAccessException());
        OutOfMemoryError fatal = new OutOfMemoryError();

        assertSame(failure, assertThrows(IllegalStateException.class, () -> MvelExpressionLanguage.warmedUp(() -> {
            throw failure;
        })));
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> MvelExpressionLanguage.warmedUp(() -> {
            throw fatal;
        })));
    }

    @Test
    @DisplayName("prepare's warm-up evaluates through MVEL and leaves the context class loader as it was")
    void warmUpRestoresContextClassLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();

        assertTrue(MvelExpressionLanguage.warmedUp(MvelExpression::warmUp));
        assertSame(previous, thread.getContextClassLoader());
    }

    /** A value whose method overflows, as one called deep in a stack may. */
    public static final class Overflowing {
        /**
         * Overflows.
         *
         * @return Nothing: it always throws
         */
        public Object call() {
            throw new StackOverflowError();
        }
    }
}
